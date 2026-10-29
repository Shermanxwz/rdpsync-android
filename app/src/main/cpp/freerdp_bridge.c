/** FreeRDP Android bridge (built against the pinned 3.32.2 headers).
 * One protocol owner thread executes input, display requests and disconnect.
 * JNI only publishes commands or copies the mutex-protected RGBA snapshot.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <time.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/constants.h>
#include <freerdp/input.h>
#include <freerdp/update.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/version.h>
#include <freerdp/graphics.h>
#include <freerdp/client.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/client/disp.h>
#include <freerdp/channels/rdpei.h>
#include <freerdp/autodetect.h>
#include <winpr/synch.h>
#include "rdpsync_geometry.h"
#include "rdpsync_certificate.h"

#define TAG "FreeRDP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define INPUT_CAPACITY 4096

typedef enum { INPUT_MOVE, INPUT_CLICK, INPUT_BUTTON, INPUT_WHEEL, INPUT_HWHEEL,
               INPUT_TOUCH, INPUT_KEY, INPUT_UNICODE } InputKind;
typedef struct { InputKind kind; int x, y, code, action; } InputCommand;
typedef struct RdpSession RdpSession;
typedef struct { rdpClientContext common; RdpSession* session; } RdpSyncContext;
struct RdpSession {
    pthread_mutex_t mutex;
    pthread_mutex_t channel_mutex;
    atomic_int connected, terminating;
    char status[1024], last_error[4096], diag[4096];
    int fb_width, fb_height;
    size_t fb_size;
    uint32_t* fb_pixels; /* Android native RGBA byte order; no repeated R/B swaps. */
    RdpDirtyRect dirty;
    int64_t frame_id, bitmap_copies, dirty_pixels, paint_copy_us, bitmap_copy_us;
    int64_t rtt_ms, bandwidth_kbps, network_sample_ms;
    int64_t input_drops, generation;
    freerdp* instance;
    pthread_t thread;
    int thread_started;
    HANDLE wake_event;
    InputCommand commands[INPUT_CAPACITY];
    size_t command_head, command_count;
    DispClientContext* disp;
    int disp_state; /* -1 disabled, 0 unnegotiated, 1 waiting caps, 2 ready, -2 failed */
    uint64_t max_monitor_area;
    int resize_pending, resize_width, resize_height, resize_scale;
    int touch_available;
    char expected_fingerprint[65], endpoint_host[256], endpoint_name[256];
    int endpoint_port, expected_pin_state, certificate_verified, certificate_challenge, certificate_superseded;
    RdpCertificateInfo certificate;
    char certificate_reason[32];
    uint8_t held_keys[3][256], held_buttons[3], held_touches[256];
    int touch_x[256], touch_y[256], mouse_x, mouse_y;
};
static RdpSession* g_session;
static int64_t g_next_generation;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
/* Serializes connection replacement without holding g_lock while joining. */
static pthread_mutex_t g_lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_startup_error[1024];
static char g_runtime_home[PATH_MAX], g_runtime_cache[PATH_MAX];

/* Android has no shell HOME. FreeRDP requires it while allocating settings,
 * before any connection callback. Only the Application's private directories
 * are supplied here; never substitute /tmp or the read-only native library dir. */
static int private_directory(const char* path, char resolved[PATH_MAX]) {
    struct stat info;
    return path && path[0] == '/' && realpath(path, resolved) &&
        stat(resolved, &info) == 0 && S_ISDIR(info.st_mode) && info.st_uid == geteuid() &&
        !(info.st_mode & S_IWOTH) &&
        (!(info.st_mode & S_IWGRP) || info.st_gid == getegid()) &&
        access(resolved, R_OK | W_OK | X_OK) == 0;
}
static int initialize_runtime(const char* home, const char* cache) {
    char resolved_home[PATH_MAX], resolved_cache[PATH_MAX];
    if (!private_directory(home, resolved_home) || !private_directory(cache, resolved_cache))
        return 0;
    pthread_mutex_lock(&g_lifecycle_lock);
    int result = 0;
    if (g_runtime_home[0]) {
        /* Idempotent, but never mutate process environment under live threads. */
        result = !strcmp(g_runtime_home, resolved_home) && !strcmp(g_runtime_cache, resolved_cache);
    } else {
        pthread_mutex_lock(&g_lock);
        const int no_session = g_session == NULL;
        pthread_mutex_unlock(&g_lock);
        if (no_session && setenv("HOME", resolved_home, 1) == 0 &&
            setenv("TMPDIR", resolved_cache, 1) == 0 &&
            setenv("XDG_CONFIG_HOME", resolved_home, 1) == 0 &&
            setenv("XDG_DATA_HOME", resolved_home, 1) == 0 &&
            setenv("XDG_CACHE_HOME", resolved_cache, 1) == 0 &&
            setenv("XDG_RUNTIME_DIR", resolved_cache, 1) == 0) {
            snprintf(g_runtime_home, sizeof(g_runtime_home), "%s", resolved_home);
            snprintf(g_runtime_cache, sizeof(g_runtime_cache), "%s", resolved_cache);
            result = 1;
        }
    }
    pthread_mutex_unlock(&g_lifecycle_lock);
    return result;
}

static int64_t monotonic_us(void) {
    struct timespec ts;
    /* Match Android elapsedRealtime: sleeping a phone must age network samples. */
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}
static RdpSession* s_from_ctx(rdpContext* ctx) {
    return ctx ? ((RdpSyncContext*)ctx)->session : NULL;
}
static void s_status(RdpSession* s, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    pthread_mutex_lock(&s->mutex);
    vsnprintf(s->status, sizeof(s->status), fmt, ap);
    pthread_mutex_unlock(&s->mutex); va_end(ap);
}
static void s_error(RdpSession* s, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    atomic_store(&s->connected, 0);
    pthread_mutex_lock(&s->mutex);
    vsnprintf(s->last_error, sizeof(s->last_error), fmt, ap);
    snprintf(s->status, sizeof(s->status), "连接失败");
    pthread_mutex_unlock(&s->mutex); va_end(ap);
}
static void s_diag(RdpSession* s, const char* line) {
    pthread_mutex_lock(&s->mutex);
    size_t used = strlen(s->diag);
    if (used < sizeof(s->diag) - 1)
        snprintf(s->diag + used, sizeof(s->diag) - used, "%s\n", line);
    pthread_mutex_unlock(&s->mutex);
}

static BOOL begin_paint(rdpContext* ctx) { (void)ctx; return TRUE; }
static BOOL end_paint(rdpContext* ctx) {
    RdpSession* s = s_from_ctx(ctx);
    rdpGdi* gdi = ctx ? ctx->gdi : NULL;
    if (!s || !gdi || !gdi->primary_buffer) return TRUE;
    const int width = gdi->width, height = gdi->height;
    if (!rdpsync_valid_frame_size(width, height)) {
        s_error(s, "服务器桌面超出安全尺寸限制");
        return FALSE;
    }
    HGDI_WND wnd = gdi->primary && gdi->primary->hdc ? gdi->primary->hdc->hwnd : NULL;
    RdpDirtyRect changed = {0};
    if (wnd && wnd->invalid && !wnd->invalid->null) {
        changed = (RdpDirtyRect){wnd->invalid->x, wnd->invalid->y,
                                 wnd->invalid->w, wnd->invalid->h};
    }
    const int64_t started = monotonic_us();
    pthread_mutex_lock(&s->mutex);
    if (width != s->fb_width || height != s->fb_height || !s->fb_pixels) {
        const size_t count = (size_t)width * height;
        uint32_t* pixels = calloc(count, sizeof(uint32_t));
        if (!pixels) { pthread_mutex_unlock(&s->mutex); return FALSE; }
        free(s->fb_pixels);
        s->fb_pixels = pixels; s->fb_size = count;
        s->fb_width = width; s->fb_height = height;
        s->dirty = (RdpDirtyRect){0};
        changed = (RdpDirtyRect){0, 0, width, height};
    }
    if (rdpsync_clip_rect(&changed, width, height)) {
        for (int row = 0; row < changed.height; ++row) {
            const uint8_t* src = gdi->primary_buffer +
                (size_t)(changed.y + row) * gdi->stride + (size_t)changed.x * 4;
            uint32_t* dst = s->fb_pixels + (size_t)(changed.y + row) * width + changed.x;
            memcpy(dst, src, (size_t)changed.width * 4);
        }
        /* Union ALL damage since last bitmap copy, not only the newest frame. */
        rdpsync_union_rect(&s->dirty, changed);
        ++s->frame_id;
        s->dirty_pixels += (int64_t)changed.width * changed.height;
        s->paint_copy_us += monotonic_us() - started;
    }
    pthread_mutex_unlock(&s->mutex);
    if (wnd && wnd->invalid) { wnd->invalid->null = TRUE; wnd->ninvalid = 0; }
    return TRUE;
}
static BOOL desktop_resize(rdpContext* ctx) {
    UINT32 width = freerdp_settings_get_uint32(ctx->settings, FreeRDP_DesktopWidth);
    UINT32 height = freerdp_settings_get_uint32(ctx->settings, FreeRDP_DesktopHeight);
    if (!rdpsync_valid_frame_size((int)width, (int)height)) return FALSE;
    return !ctx->gdi || gdi_resize(ctx->gdi, width, height);
}

// ====== Pointer handling ======
typedef struct { rdpPointer pointer; size_t size; void* data; } AppPointer;

static BOOL ptr_New(rdpContext* context, rdpPointer* pointer) {
    AppPointer* p = (AppPointer*)pointer;
    if(!p||!context->gdi) return FALSE;
    p->size = 4ULL * pointer->width * pointer->height;
    p->data = winpr_aligned_malloc(p->size, 16);
    if(!p->data) return FALSE;
    if(!freerdp_image_copy_from_pointer_data(p->data,PIXEL_FORMAT_BGRA32,0,0,0,
        pointer->width,pointer->height,pointer->xorMaskData,pointer->lengthXorMask,
        pointer->andMaskData,pointer->lengthAndMask,pointer->xorBpp,&context->gdi->palette))
    { winpr_aligned_free(p->data); p->data=NULL; return FALSE; }
    return TRUE;
}
static void ptr_Free(rdpContext* context, rdpPointer* pointer) {
    AppPointer* p = (AppPointer*)pointer;
    if(p&&p->data){winpr_aligned_free(p->data);p->data=NULL;}
}
static BOOL ptr_Set(rdpContext* context, rdpPointer* pointer) { (void)context;(void)pointer; return TRUE; }
static BOOL ptr_SetNull(rdpContext* context) { (void)context; return TRUE; }
static BOOL ptr_SetDefault(rdpContext* context) { (void)context; return TRUE; }
static BOOL ptr_SetPosition(rdpContext* context, UINT32 x, UINT32 y) { (void)context;(void)x;(void)y; return TRUE; }

static BOOL register_pointer(rdpGraphics* graphics) {
    rdpPointer pointer = {0};
    pointer.size = sizeof(AppPointer);
    pointer.New = ptr_New;
    pointer.Free = ptr_Free;
    pointer.Set = ptr_Set;
    pointer.SetNull = ptr_SetNull;
    pointer.SetDefault = ptr_SetDefault;
    pointer.SetPosition = ptr_SetPosition;
    graphics_register_pointer(graphics, &pointer);
    return TRUE;
}


static UINT display_caps(DispClientContext* disp, UINT32 count, UINT32 factor_a, UINT32 factor_b) {
    RdpSession* s = disp ? disp->custom : NULL;
    if (!s) return CHANNEL_RC_OK;
    pthread_mutex_lock(&s->mutex);
    /* Cap the advertised budget before multiplying by monitor count. Malformed
     * 32-bit caps must not overflow a 64-bit product or our mobile memory cap. */
    uint64_t area = (uint64_t)factor_a * factor_b;
    if (count && area > RDPSYNC_MAX_PIXELS / count) area = RDPSYNC_MAX_PIXELS;
    else area *= count;
    s->max_monitor_area = area;
    s->disp_state = count && factor_a && factor_b ? 2 : -2;
    pthread_mutex_unlock(&s->mutex);
    s_diag(s, "display-control=caps-received");
    SetEvent(s->wake_event);
    return CHANNEL_RC_OK;
}
static BOOL network_characteristics(rdpAutoDetect* autodetect, RDP_TRANSPORT_TYPE transport,
    UINT16 sequence, const rdpNetworkCharacteristicsResult* result) {
    (void)transport; (void)sequence;
    RdpSession* s = s_from_ctx(autodetect->context);
    if (!s || !result) return TRUE;
    pthread_mutex_lock(&s->mutex);
    s->rtt_ms = result->averageRTT;
    s->bandwidth_kbps = -1; /* RTT-only reports must not rejuvenate stale bandwidth. */
    if (result->type == RDP_NETCHAR_RESULT_TYPE_BW_AVG_RTT ||
        result->type == RDP_NETCHAR_RESULT_TYPE_BASE_RTT_BW_AVG_RTT)
        s->bandwidth_kbps = result->bandwidth;
    s->network_sample_ms = monotonic_us() / 1000;
    pthread_mutex_unlock(&s->mutex);
    return TRUE;
}
static void channel_connected(void* context, const ChannelConnectedEventArgs* event) {
    if (!context || !event || !event->name) return;
    RdpSession* s = s_from_ctx(context);
    if (!s) return;
    pthread_mutex_lock(&s->channel_mutex);
    freerdp_client_OnChannelConnectedEventHandler(context, event);
    if (strcmp(event->name, DISP_DVC_CHANNEL_NAME) == 0) {
        s->disp = event->pInterface;
        if (s->disp) {
            s->disp->custom = s;
            s->disp->DisplayControlCaps = display_caps;
            pthread_mutex_lock(&s->mutex); s->disp_state = 1; pthread_mutex_unlock(&s->mutex);
        }
    }
    if (strcmp(event->name, RDPEI_DVC_CHANNEL_NAME) == 0) {
        pthread_mutex_lock(&s->mutex); s->touch_available = 1; pthread_mutex_unlock(&s->mutex);
    }
    pthread_mutex_unlock(&s->channel_mutex);
}
static void channel_disconnected(void* context, const ChannelDisconnectedEventArgs* event) {
    if (!context || !event || !event->name) return;
    RdpSession* s = s_from_ctx(context);
    if (!s) return;
    pthread_mutex_lock(&s->channel_mutex);
    if (strcmp(event->name, DISP_DVC_CHANNEL_NAME) == 0) {
        if (s->disp) { s->disp->custom = NULL; s->disp->DisplayControlCaps = NULL; }
        s->disp = NULL;
        pthread_mutex_lock(&s->mutex); s->disp_state = 0; s->resize_pending = 0; pthread_mutex_unlock(&s->mutex);
    }
    if (strcmp(event->name, RDPEI_DVC_CHANNEL_NAME) == 0) {
        pthread_mutex_lock(&s->mutex); s->touch_available = 0; pthread_mutex_unlock(&s->mutex);
    }
    freerdp_client_OnChannelDisconnectedEventHandler(context, event);
    pthread_mutex_unlock(&s->channel_mutex);
}
static BOOL pre_connect(freerdp* instance) {
    if (PubSub_SubscribeChannelConnected(instance->context->pubSub, channel_connected) != CHANNEL_RC_OK ||
        PubSub_SubscribeChannelDisconnected(instance->context->pubSub, channel_disconnected) != CHANNEL_RC_OK)
        return FALSE;
    rdpAutoDetect* autodetect = autodetect_get(instance->context);
    if (autodetect) autodetect->NetworkCharacteristicsResult = network_characteristics;
    /* FreeRDP 3.32's common ClientNew installs LoadChannels. Core calls it via
     * utils_reload_channels after settings validation; loading here as well
     * duplicates/mutates addin arguments and can enable unwanted audio flags. */
    return TRUE;
}
static BOOL post_connect(freerdp* instance) {
    RdpSession* session = s_from_ctx(instance->context);
    if (!session) return FALSE;
    pthread_mutex_lock(&session->mutex);
    const int verified = session->certificate_verified && !session->certificate_superseded;
    pthread_mutex_unlock(&session->mutex);
    if (!verified) return FALSE;
    /* Reject a server override before GDI allocates its primary surface. */
    const UINT32 width = freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopWidth);
    const UINT32 height = freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopHeight);
    if (!rdpsync_valid_frame_size((int)width, (int)height)) return FALSE;
    if (!gdi_init(instance, PIXEL_FORMAT_RGBA32)) return FALSE;
    if (!register_pointer(instance->context->graphics)) return FALSE;
    instance->context->update->BeginPaint = begin_paint;
    instance->context->update->EndPaint = end_paint;
    instance->context->update->DesktopResize = desktop_resize;
    return TRUE;
}
static void post_disconnect(freerdp* instance) { gdi_free(instance); }

// ====== Authentication callbacks ======
static BOOL authenticate_ex(freerdp* instance, char** username, char** password, char** domain, rdp_auth_reason reason) {
    if (reason == AUTH_RDP) return FALSE; /* Defense in depth: no certificate-less login. */
    if (!instance || !instance->context || !instance->context->settings) return FALSE;
    rdpSettings* settings = instance->context->settings;
    const char* user = freerdp_settings_get_string(settings, FreeRDP_Username);
    const char* pass = freerdp_settings_get_string(settings, FreeRDP_Password);
    const char* dom = freerdp_settings_get_string(settings, FreeRDP_Domain);
    if (username && (!*username || strlen(*username) == 0) && user && strlen(user) > 0) {
        *username = strdup(user);
    }
    if (password && (!*password || strlen(*password) == 0) && pass && strlen(pass) > 0) {
        *password = strdup(pass);
    }
    if (domain && (!*domain || strlen(*domain) == 0) && dom && strlen(dom) > 0) {
        *domain = strdup(dom);
    }
    return TRUE;
}

static int verify_x509_certificate(freerdp* instance, const BYTE* pem, size_t length,
    const char* hostname, UINT16 port, DWORD flags) {
    RdpSession* s = instance && instance->context ? s_from_ctx(instance->context) : NULL;
    if (!s || atomic_load(&s->terminating)) return 0;
    pthread_mutex_lock(&s->mutex);
    if (s->certificate_superseded) { pthread_mutex_unlock(&s->mutex); return 0; }
    s->certificate_verified = 0; s->certificate_challenge = 0;
    pthread_mutex_unlock(&s->mutex);
    /* Pins never authorize a gateway, redirect or a different endpoint. Those
     * need their own explicit product flow; this app supports direct hosts. */
    if (!hostname || strcmp(hostname, s->endpoint_name) != 0 || port != s->endpoint_port ||
        (flags & (VERIFY_CERT_FLAG_GATEWAY | VERIFY_CERT_FLAG_REDIRECT | VERIFY_CERT_FLAG_LEGACY))) {
        s_error(s, "证书验证失败：连接目标已改变");
        return 0;
    }
    RdpCertificateInfo info;
    if (!rdpsync_certificate_info(pem, length, &info)) {
        s_error(s, "证书验证失败：无法读取服务器证书");
        return 0;
    }
    pthread_mutex_lock(&s->mutex);
    if (s->certificate_superseded || atomic_load(&s->terminating)) {
        pthread_mutex_unlock(&s->mutex); return 0;
    }
    const int trusted = s->expected_pin_state == 1 &&
        CRYPTO_memcmp(s->expected_fingerprint, info.fingerprint, 64) == 0;
    if (trusted) {
        s->certificate_verified = 1;
        s->certificate_challenge = 0;
    } else {
        s->certificate_verified = 0;
        s->certificate_challenge = 1;
        s->certificate = info;
        snprintf(s->certificate_reason, sizeof(s->certificate_reason), "%s",
            s->expected_pin_state < 0 ? "invalid-pin" : s->expected_pin_state ? "changed" : "untrusted");
        snprintf(s->last_error, sizeof(s->last_error), "%s", s->expected_pin_state ?
            "服务器证书已改变，已阻止连接，请核对 SHA-256 指纹" :
            "首次连接需核对服务器证书 SHA-256 指纹");
    }
    pthread_mutex_unlock(&s->mutex);
    /* Do not block a native thread waiting for UI. Reject now; explicit approval
     * may start a NEW connection with a new immutable expected-pin snapshot. */
    return trusted ? 2 : 0;
}
static BOOL reject_redirect(freerdp* instance) {
    RdpSession* s = instance && instance->context ? s_from_ctx(instance->context) : NULL;
    if (s) s_error(s, "已阻止服务器重定向；请为新目标创建独立连接并验证证书");
    return FALSE;
}
static BOOL configure_security(rdpSettings* settings) {
    /* Mandatory for every mode, including compatibility retries. A legacy RDP
     * connection has no TLS certificate callback and must never be negotiated. */
#define SECURITY_SETTING(type, key, value) do { \
    if (!freerdp_settings_set_##type(settings, key, value)) { \
        LOGE("Security setting initialization failed: %s", #key); return FALSE; \
    } \
} while (0)
    SECURITY_SETTING(bool, FreeRDP_ExternalCertificateManagement, TRUE);
    SECURITY_SETTING(bool, FreeRDP_IgnoreCertificate, FALSE);
    SECURITY_SETTING(bool, FreeRDP_AutoAcceptCertificate, FALSE);
    SECURITY_SETTING(bool, FreeRDP_AutoDenyCertificate, FALSE);
    SECURITY_SETTING(bool, FreeRDP_RdpSecurity, FALSE);
    SECURITY_SETTING(bool, FreeRDP_UseRdpSecurityLayer, FALSE);
    SECURITY_SETTING(bool, FreeRDP_TlsSecurity, TRUE);
    SECURITY_SETTING(bool, FreeRDP_NlaSecurity, TRUE);
    SECURITY_SETTING(bool, FreeRDP_NegotiateSecurityLayer, TRUE);
    /* X.224 precedes TLS; suppress its optional plaintext username cookie. */
    SECURITY_SETTING(bool, FreeRDP_MstscCookieMode, FALSE);
    SECURITY_SETTING(uint32, FreeRDP_CookieMaxLength, 0);
    SECURITY_SETTING(bool, FreeRDP_SendPreconnectionPdu, FALSE);
    SECURITY_SETTING(uint32, FreeRDP_AuthenticationLevel, 2);
    SECURITY_SETTING(string, FreeRDP_CertificateAcceptedFingerprints, NULL);
    SECURITY_SETTING(uint32, FreeRDP_AcceptedCertLength, 0);
    SECURITY_SETTING(uint32, FreeRDP_GatewayAcceptedCertLength, 0);
    SECURITY_SETTING(uint32, FreeRDP_RedirectionAcceptedCertLength, 0);
#undef SECURITY_SETTING
    return TRUE;
}
/* The real connect path and Android's offline runtime gate MUST use identical
 * display/channel options, otherwise a missing addin goes undetected by smoke. */
static BOOL configure_session_profile(rdpSettings* settings, int width, int height, BOOL touch, BOOL compatibility) {
#define PROFILE_SETTING(type, key, value) do { \
    if (!freerdp_settings_set_##type(settings, key, value)) { \
        LOGE("Session profile initialization failed: %s", #key); return FALSE; \
    } \
} while (0)
    PROFILE_SETTING(bool, FreeRDP_AutoLogonEnabled, TRUE);
    PROFILE_SETTING(uint32, FreeRDP_DesktopWidth, width);
    PROFILE_SETTING(uint32, FreeRDP_DesktopHeight, height);
    PROFILE_SETTING(uint16, FreeRDP_DesktopOrientation, ORIENTATION_LANDSCAPE);
    PROFILE_SETTING(uint32, FreeRDP_DesktopPhysicalWidth, width * 254 / 960);
    PROFILE_SETTING(uint32, FreeRDP_DesktopPhysicalHeight, height * 254 / 960);
    PROFILE_SETTING(bool, FreeRDP_SupportMonitorLayoutPdu, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_SupportDisplayControl, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_DynamicResolutionUpdate, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_MultiTouchInput, touch && !compatibility);
    PROFILE_SETTING(bool, FreeRDP_SynchronousDynamicChannels, TRUE);
    /* The established client transport is event-driven/nonblocking. Do not spin
     * inside each write waiting for a slow/zero-window peer to drain its socket;
     * FreeRDP's write-ready events flush the ordered buffered BIO instead. */
    PROFILE_SETTING(bool, FreeRDP_WaitForOutputBufferFlush, FALSE);
    PROFILE_SETTING(uint32, FreeRDP_DesktopScaleFactor, 100);
    PROFILE_SETTING(uint32, FreeRDP_DeviceScaleFactor, 100);
    PROFILE_SETTING(bool, FreeRDP_NetworkAutoDetect, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_SupportHeartbeatPdu, FALSE);
    PROFILE_SETTING(bool, FreeRDP_SupportMultitransport, FALSE);
    /* RDP8 autodetect requires the rdpdr transport, NOT actual redirected devices. */
    PROFILE_SETTING(bool, FreeRDP_DeviceRedirection, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_RedirectDrives, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectHomeDrive, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectClipboard, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectSmartCards, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectPrinters, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectSerialPorts, FALSE);
    PROFILE_SETTING(bool, FreeRDP_RedirectParallelPorts, FALSE);
    PROFILE_SETTING(uint32, FreeRDP_ColorDepth, 32);
    PROFILE_SETTING(uint32, FreeRDP_RequestedProtocols, 0x0000000b);
    /* RDPDR pulls in rdpsnd sys:fake for protocol compatibility, not playback.
     * Both microphone capture and actual playback remain explicitly disabled. */
    PROFILE_SETTING(bool, FreeRDP_AudioPlayback, FALSE);
    PROFILE_SETTING(bool, FreeRDP_AudioCapture, FALSE);
    PROFILE_SETTING(bool, FreeRDP_SupportGraphicsPipeline, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxH264, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxAVC444, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxAVC444v2, FALSE);
    PROFILE_SETTING(bool, FreeRDP_SupportDynamicChannels, !compatibility);
#undef PROFILE_SETTING
    return TRUE;
}
static BOOL load_session_channels(freerdp* instance) {
    if (!freerdp_client_load_channels(instance)) return FALSE;
    const rdpSettings* settings = instance->context->settings;
    /* Check AFTER upstream dependency expansion, not only our initial options.
     * A duplicate load can turn fake rdpsnd into AudioPlayback=TRUE. */
    if (freerdp_settings_get_bool(settings, FreeRDP_AudioPlayback) ||
        freerdp_settings_get_bool(settings, FreeRDP_AudioCapture) ||
        freerdp_settings_get_uint32(settings, FreeRDP_DeviceCount) != 0) {
        LOGE("Channel loading unexpectedly enabled audio or device redirection");
        RdpSession* session = s_from_ctx(instance->context);
        if (session) s_error(session, "通道隐私设置不一致，已阻止连接");
        return FALSE;
    }
    return TRUE;
}
static BOOL client_ctx_new(freerdp* instance, rdpContext* context) {
    (void)context;
    instance->LoadChannels = load_session_channels;
    instance->PreConnect = pre_connect;
    instance->PostConnect = post_connect;
    instance->PostDisconnect = post_disconnect;
    instance->AuthenticateEx = authenticate_ex;
    instance->VerifyX509Certificate = verify_x509_certificate;
    instance->Redirect = reject_redirect;
    return TRUE;
}
static freerdp* create_instance(RdpSession* session) {
    RDP_CLIENT_ENTRY_POINTS entry = {0};
    entry.Version = RDP_CLIENT_INTERFACE_VERSION;
    entry.Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
    entry.ContextSize = sizeof(RdpSyncContext);
    entry.ClientNew = client_ctx_new;
    rdpContext* ctx = freerdp_client_context_new(&entry);
    if (!ctx) return NULL;
    ((RdpSyncContext*)ctx)->session = session;
    return ctx->instance;
}
static int coordinate(int value, int limit) {
    if (value < 0) return 0;
    return value >= limit ? limit - 1 : value;
}
static UINT16 button_flag(int button) {
    return button == 1 ? PTR_FLAGS_BUTTON1 : button == 2 ? PTR_FLAGS_BUTTON2 : PTR_FLAGS_BUTTON3;
}
static BOOL execute_input(RdpSession* s, InputCommand command) {
    rdpContext* ctx = s->instance->context;
    rdpInput* input = ctx->input;
    const int width = (int)freerdp_settings_get_uint32(ctx->settings, FreeRDP_DesktopWidth);
    const int height = (int)freerdp_settings_get_uint32(ctx->settings, FreeRDP_DesktopHeight);
    const UINT16 x = (UINT16)coordinate(command.x, width), y = (UINT16)coordinate(command.y, height);
    switch (command.kind) {
        case INPUT_MOVE:
            s->mouse_x = x; s->mouse_y = y;
            return input->MouseEvent(input, PTR_FLAGS_MOVE, x, y);
        case INPUT_CLICK: {
            const UINT16 flag = button_flag(command.code);
            s->mouse_x = x; s->mouse_y = y;
            s->held_buttons[command.code - 1] = 1;
            BOOL down = input->MouseEvent(input, flag | PTR_FLAGS_DOWN, x, y);
            BOOL up = input->MouseEvent(input, flag, x, y);
            if (up) s->held_buttons[command.code - 1] = 0;
            return down && up;
        }
        case INPUT_BUTTON: {
            s->mouse_x = x; s->mouse_y = y;
            if (command.action) s->held_buttons[command.code - 1] = 1;
            BOOL ok = input->MouseEvent(input, button_flag(command.code) |
                (command.action ? PTR_FLAGS_DOWN : 0), x, y);
            if (ok && !command.action) s->held_buttons[command.code - 1] = 0;
            return ok;
        }
        case INPUT_WHEEL:
        case INPUT_HWHEEL: {
            int delta = command.code;
            /* Preserve accumulated motion rather than truncating it to one 255 unit PDU. */
            while (delta) {
                const int step = delta > 255 ? 255 : delta < -255 ? -255 : delta;
                const UINT16 flags = (command.kind == INPUT_WHEEL ? PTR_FLAGS_WHEEL : PTR_FLAGS_HWHEEL) |
                    (step < 0 ? PTR_FLAGS_WHEEL_NEGATIVE | ((0x100 + step) & 0xff) : step);
                if (!input->MouseEvent(input, flags, x, y)) return FALSE;
                delta -= step;
            }
            return TRUE;
        }
        case INPUT_TOUCH: {
            pthread_mutex_lock(&s->channel_mutex);
            RdpeiClientContext* rdpei = ((rdpClientContext*)ctx)->rdpei;
            UINT result = CHANNEL_RC_OK;
            if (rdpei && rdpei->TouchRawEvent) {
                INT32 contact_id = 0;
                const UINT32 flags[] = {
                    RDPINPUT_CONTACT_FLAG_DOWN | RDPINPUT_CONTACT_FLAG_INRANGE | RDPINPUT_CONTACT_FLAG_INCONTACT,
                    RDPINPUT_CONTACT_FLAG_UPDATE | RDPINPUT_CONTACT_FLAG_INRANGE | RDPINPUT_CONTACT_FLAG_INCONTACT,
                    RDPINPUT_CONTACT_FLAG_UP,
                    RDPINPUT_CONTACT_FLAG_UP | RDPINPUT_CONTACT_FLAG_CANCELED
                };
                result = rdpei->TouchRawEvent(rdpei, command.code, x, y, &contact_id,
                    flags[command.action], 0);
                if (result == CHANNEL_RC_OK) {
                    const int index = command.code - 1;
                    s->held_touches[index] = command.action <= 1;
                    s->touch_x[index] = x; s->touch_y[index] = y;
                }
            }
            pthread_mutex_unlock(&s->channel_mutex);
            if (result != CHANNEL_RC_OK) {
                pthread_mutex_lock(&s->mutex); s->touch_available = 0; pthread_mutex_unlock(&s->mutex);
                s_diag(s, "rdpei=send-failed; mouse fallback required");
            }
            /* A missing/failed optional touch channel must not tear down the desktop. */
            return TRUE;
        }
        case INPUT_KEY: {
            UINT16 flags = command.action ? 0 : KBD_FLAGS_RELEASE;
            int extended = 0;
            if ((command.code & 0xff00) == 0xe000) { flags |= KBD_FLAGS_EXTENDED; extended = 1; }
            else if ((command.code & 0xff00) == 0xe100) { flags |= KBD_FLAGS_EXTENDED1; extended = 2; }
            const UINT8 key = (UINT8)(command.code & 0xff);
            if (command.action) s->held_keys[extended][key] = 1;
            BOOL ok = input->KeyboardEvent(input, flags, key);
            if (ok && !command.action) s->held_keys[extended][key] = 0;
            return ok;
        }
        case INPUT_UNICODE: {
            BOOL down = input->UnicodeKeyboardEvent(input, 0, (UINT16)command.code);
            BOOL up = input->UnicodeKeyboardEvent(input, KBD_FLAGS_RELEASE, (UINT16)command.code);
            return down && up;
        }
    }
    return FALSE;
}
/* Called by the protocol owner before disconnect. JNI release enqueueing alone
 * is insufficient: terminating can discard that queue before an UP reaches the wire. */
static void release_remote_inputs(RdpSession* s) {
    rdpContext* ctx = s->instance->context;
    rdpInput* input = ctx->input;
    if (!input) return;
    for (int bank = 0; bank < 3; ++bank) for (int key = 0; key < 256; ++key) {
        if (!s->held_keys[bank][key]) continue;
        const UINT16 flags = KBD_FLAGS_RELEASE | (bank == 1 ? KBD_FLAGS_EXTENDED : bank == 2 ? KBD_FLAGS_EXTENDED1 : 0);
        (void)input->KeyboardEvent(input, flags, (UINT8)key);
        s->held_keys[bank][key] = 0;
    }
    for (int button = 0; button < 3; ++button) {
        if (!s->held_buttons[button]) continue;
        (void)input->MouseEvent(input, button_flag(button + 1), (UINT16)s->mouse_x, (UINT16)s->mouse_y);
        s->held_buttons[button] = 0;
    }
    pthread_mutex_lock(&s->channel_mutex);
    RdpeiClientContext* rdpei = ((rdpClientContext*)ctx)->rdpei;
    for (int contact = 0; contact < 256; ++contact) {
        if (!s->held_touches[contact]) continue;
        if (rdpei && rdpei->TouchRawEvent) {
            INT32 id = 0;
            (void)rdpei->TouchRawEvent(rdpei, contact + 1, s->touch_x[contact], s->touch_y[contact], &id,
                RDPINPUT_CONTACT_FLAG_UP | RDPINPUT_CONTACT_FLAG_CANCELED, 0);
        }
        s->held_touches[contact] = 0;
    }
    pthread_mutex_unlock(&s->channel_mutex);
    /* SynchronousDynamicChannels routes RDPEI's final buffered contacts here. */
    (void)freerdp_channels_check_fds(ctx->channels, s->instance);
}
static BOOL process_commands(RdpSession* s) {
    /* Bound each batch so a busy input stream cannot starve network decode. */
    for (size_t n = 0; n < 256; ++n) {
        if (atomic_load(&s->terminating)) break;
        pthread_mutex_lock(&s->mutex);
        if (!s->command_count) { pthread_mutex_unlock(&s->mutex); break; }
        InputCommand command = s->commands[s->command_head];
        s->command_head = (s->command_head + 1) % INPUT_CAPACITY;
        --s->command_count;
        pthread_mutex_unlock(&s->mutex);
        if (!execute_input(s, command)) return FALSE;
    }
    pthread_mutex_lock(&s->mutex);
    const int pending = s->resize_pending;
    const int width = s->resize_width, height = s->resize_height, scale = s->resize_scale;
    s->resize_pending = 0;
    if (!s->command_count) ResetEvent(s->wake_event);
    pthread_mutex_unlock(&s->mutex);
    if (pending && !atomic_load(&s->terminating)) {
        pthread_mutex_lock(&s->channel_mutex);
        UINT result = CHANNEL_RC_OK;
        if (s->disp && s->disp->SendMonitorLayout) {
            DISPLAY_CONTROL_MONITOR_LAYOUT layout = {0};
            layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
            layout.Width = width; layout.Height = height;
            /* Physical dimensions in mm, not pixels; valid nominal 96 dpi. */
            layout.PhysicalWidth = (UINT32)(width * 254 / 960);
            layout.PhysicalHeight = (UINT32)(height * 254 / 960);
            layout.Orientation = ORIENTATION_LANDSCAPE;
            layout.DesktopScaleFactor = scale; layout.DeviceScaleFactor = 100;
            result = s->disp->SendMonitorLayout(s->disp, 1, &layout);
        }
        pthread_mutex_unlock(&s->channel_mutex);
        if (result != CHANNEL_RC_OK) {
            pthread_mutex_lock(&s->mutex); s->disp_state = -2; pthread_mutex_unlock(&s->mutex);
            s_diag(s, "display-control=send-failed");
        }
    }
    return TRUE;
}
static void* thread_fn(void* arg) {
    RdpSession* s = arg;
    freerdp* instance = s->instance;
    s_status(s, "正在连接...");
    if (!freerdp_connect(instance)) {
        const UINT32 error = freerdp_get_last_error(instance->context);
        pthread_mutex_lock(&s->mutex);
        const int certificate_denial = s->certificate_challenge || s->last_error[0];
        pthread_mutex_unlock(&s->mutex);
        if (!atomic_load(&s->terminating) && !certificate_denial)
            s_error(s, "FreeRDP 连接失败 [0x%08x]: %s", error, freerdp_get_last_error_string(error));
        goto done;
    }
    atomic_store(&s->connected, 1);
    s_status(s, "已连接");
    while (!atomic_load(&s->terminating)) {
        if (!process_commands(s)) { s_error(s, "输入发送失败，连接已停止"); break; }
        HANDLE handles[MAXIMUM_WAIT_OBJECTS] = {0};
        DWORD count = freerdp_get_event_handles(instance->context, handles, MAXIMUM_WAIT_OBJECTS - 1);
        if (!count) { s_error(s, "连接中断"); break; }
        handles[count++] = s->wake_event;
        const DWORD status = WaitForMultipleObjects(count, handles, FALSE, 100);
        if (atomic_load(&s->terminating)) break;
        if (status == WAIT_FAILED) { s_error(s, "连接事件等待失败"); break; }
        if (status != WAIT_TIMEOUT && !freerdp_check_event_handles(instance->context)) {
            s_error(s, "连接中断"); break;
        }
        if (freerdp_shall_disconnect_context(instance->context)) break;
    }
    /* Best effort if transport still exists; no claim of delivery after network loss. */
    release_remote_inputs(s);
    freerdp_disconnect(instance);
done:
    atomic_store(&s->connected, 0);
    pthread_mutex_lock(&s->mutex);
    s->command_count = 0; s->resize_pending = 0; s->touch_available = 0;
    if (!s->last_error[0]) snprintf(s->status, sizeof(s->status), "已断开");
    pthread_mutex_unlock(&s->mutex);
    return NULL;
}
static void session_destroy(RdpSession* s) {
    if (!s) return;
    atomic_store(&s->terminating, 1);
    /* Abort a connecting socket, but let an established transport send releases. */
    if (s->instance && !atomic_load(&s->connected)) freerdp_abort_connect_context(s->instance->context);
    if (s->wake_event) SetEvent(s->wake_event);
    if (s->thread_started) pthread_join(s->thread, NULL);
    if (s->instance) freerdp_client_context_free(s->instance->context);
    if (s->wake_event) CloseHandle(s->wake_event);
    pthread_mutex_destroy(&s->channel_mutex);
    pthread_mutex_destroy(&s->mutex);
    free(s->fb_pixels); free(s);
}
static int enqueue_input(InputCommand command) {
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    int result = 0;
    if (!s || !atomic_load(&s->connected) || atomic_load(&s->terminating)) {
        pthread_mutex_unlock(&g_lock); return -1;
    }
    pthread_mutex_lock(&s->mutex);
    if (command.kind == INPUT_TOUCH && !s->touch_available) { result = -2; goto unlock; }
    /* Only consecutive moves for the same contact may be merged: never cross an edge. */
    if (s->command_count && (command.kind == INPUT_MOVE || (command.kind == INPUT_TOUCH && command.action == 1))) {
        InputCommand* last = &s->commands[(s->command_head + s->command_count - 1) % INPUT_CAPACITY];
        if (last->kind == command.kind && last->code == command.code && last->action == command.action) {
            *last = command; goto unlock;
        }
    }
    if (s->command_count == INPUT_CAPACITY) {
        ++s->input_drops;
        result = -5;
        /* Nonblocking backpressure: Kotlin retries atomic Unicode pairs on IO.
         * Never wait with g_lock held, which would block the UI's frame polling.
         * Other lost edges fail closed so a remote key cannot remain held. */
        if (command.kind != INPUT_UNICODE) {
            atomic_store(&s->terminating, 1);
            snprintf(s->last_error, sizeof(s->last_error), "输入队列过载，会话已停止");
            SetEvent(s->wake_event);
        }
        goto unlock;
    }
    s->commands[(s->command_head + s->command_count) % INPUT_CAPACITY] = command;
    ++s->command_count;
    SetEvent(s->wake_event);
unlock:
    pthread_mutex_unlock(&s->mutex);
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect5(JNIEnv*,jobject,jstring,jint,jstring,jstring,jstring,jstring,jint,jint,jboolean,jboolean,jstring);
JNIEXPORT jboolean JNICALL Java_com_rdp_sync_network_RdpConnector_nativeInitialize(
    JNIEnv* env, jobject obj, jstring jhome, jstring jcache) {
    (void)obj;
    if (!jhome || !jcache) return JNI_FALSE;
    const char* home = (*env)->GetStringUTFChars(env, jhome, NULL);
    if (!home) return JNI_FALSE;
    const char* cache = (*env)->GetStringUTFChars(env, jcache, NULL);
    const int result = cache && initialize_runtime(home, cache);
    if (cache) (*env)->ReleaseStringUTFChars(env, jcache, cache);
    (*env)->ReleaseStringUTFChars(env, jhome, home);
    pthread_mutex_lock(&g_lock);
    if (!g_session) {
        if (result) g_startup_error[0] = 0;
        else snprintf(g_startup_error, sizeof(g_startup_error), "远程桌面私有运行目录初始化失败");
    }
    pthread_mutex_unlock(&g_lock);
    if (!result) LOGE("Private runtime directory initialization failed");
    return result ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCheckRuntime(JNIEnv* env, jobject obj) {
    (void)env; (void)obj;
    /* Real context allocation without connect(), sockets, authentication or a
     * protocol thread. The instrumentation gate catches platform initialization
     * regressions that host mocks cannot (notably Android's missing HOME). */
    pthread_mutex_lock(&g_lifecycle_lock);
    pthread_mutex_lock(&g_lock);
    const int active = g_session != NULL;
    pthread_mutex_unlock(&g_lock);
    if (active) {
        pthread_mutex_unlock(&g_lifecycle_lock);
        return JNI_FALSE; /* Never touch an active session or its trust state. */
    }
    const char* failed = "运行目录";
    int result = 0;
    if (g_runtime_home[0]) {
        result = 1;
        /* Test normal (DISP/RDPEI/autodetect/RDPDR) and compatibility profiles.
         * No connect() call or credentials: addins are loaded but not connected. */
        for (int compatibility = 0; compatibility < 2 && result; ++compatibility) {
            failed = "FreeRDP 上下文";
            freerdp* instance = create_instance(NULL);
            if (!instance) { result = 0; break; }
            failed = "安全与通道设置";
            result = configure_security(instance->context->settings) &&
                configure_session_profile(instance->context->settings, 1280, 720, TRUE, compatibility);
            if (result) {
                failed = compatibility ? "兼容模式通道加载" : "完整模式通道加载";
                result = instance->LoadChannels && instance->LoadChannels(instance);
            }
            /* ContextFree only frees the channel container; Disconnect sends
             * CHANNEL_EVENT_TERMINATED to free loaded addins. There is no socket
             * or canceled-connect error here, so this cannot send network data. */
            (void)freerdp_disconnect(instance);
            freerdp_client_context_free(instance->context);
        }
    }
    pthread_mutex_lock(&g_lock);
    if (result) g_startup_error[0] = 0;
    else snprintf(g_startup_error, sizeof(g_startup_error), "远程桌面运行检查失败（阶段：%s）", failed);
    pthread_mutex_unlock(&g_lock);
    if (!result) LOGE("Runtime check failed at stage: %s", failed);
    pthread_mutex_unlock(&g_lifecycle_lock);
    return result ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSetLibDir(JNIEnv* env, jobject obj, jstring path) {
    (void)env; (void)obj; (void)path;
    /* FreeRDP uses WinPR's WITH_INTERNAL_MD4 implementation. Do not cast raw
     * MD4_CTX functions to EVP_MD_CTX callbacks: those structures are unrelated. */
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect5(
    JNIEnv* env, jobject obj, jstring jhost, jint port, jstring juser, jstring jpassword,
    jstring jdomain, jstring jserver_name, jint width, jint height, jboolean touch, jboolean compatibility,
    jstring jexpected_fingerprint) {
    (void)obj;
    /* Even a rejected new request invalidates an earlier UI trust challenge. */
    pthread_mutex_lock(&g_lock);
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        g_session->certificate_challenge = 0; g_session->certificate_verified = 0;
        g_session->certificate_superseded = 1;
        pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    if (!rdpsync_valid_size(width, height) || port < 1 || port > 65535) return 0;
    const char* host = (*env)->GetStringUTFChars(env, jhost, NULL);
    const char* user = (*env)->GetStringUTFChars(env, juser, NULL);
    const char* password = (*env)->GetStringUTFChars(env, jpassword, NULL);
    const char* domain = (*env)->GetStringUTFChars(env, jdomain, NULL);
    const char* server_name = jserver_name ? (*env)->GetStringUTFChars(env, jserver_name, NULL) : NULL;
    const char* expected_pin = jexpected_fingerprint ? (*env)->GetStringUTFChars(env, jexpected_fingerprint, NULL) : NULL;
    int result = 0;
    if (!host || !user || !password || !domain || (jserver_name && !server_name) ||
        (jexpected_fingerprint && !expected_pin)) goto release;
    if (!*host || strlen(host) >= 256 || (server_name && strlen(server_name) >= 256)) goto release;
    pthread_mutex_lock(&g_lifecycle_lock);
    const char* failure_stage = "运行目录";
    if (!g_runtime_home[0]) goto failure;
    pthread_mutex_lock(&g_lock);
    RdpSession* previous = g_session; g_session = NULL;
    g_startup_error[0] = 0;
    pthread_mutex_unlock(&g_lock);
    session_destroy(previous);
    failure_stage = "会话内存";
    RdpSession* session = calloc(1, sizeof(RdpSession));
    if (!session) goto failure;
    pthread_mutex_init(&session->mutex, NULL);
    pthread_mutex_init(&session->channel_mutex, NULL);
    atomic_init(&session->connected, 0); atomic_init(&session->terminating, 0);
    session->rtt_ms = -1; session->bandwidth_kbps = -1;
    session->disp_state = compatibility ? -1 : 0;
    snprintf(session->endpoint_host, sizeof(session->endpoint_host), "%s", host);
    snprintf(session->endpoint_name, sizeof(session->endpoint_name), "%s", server_name && *server_name ? server_name : host);
    session->endpoint_port = port;
    session->expected_pin_state = rdpsync_normalize_pin(expected_pin, session->expected_fingerprint);
    pthread_mutex_lock(&g_lock); session->generation = ++g_next_generation; pthread_mutex_unlock(&g_lock);
    failure_stage = "唤醒事件";
    session->wake_event = CreateEvent(NULL, TRUE, FALSE, NULL);
    if (!session->wake_event) { session_destroy(session); goto failure; }
    failure_stage = "FreeRDP 上下文";
    session->instance = create_instance(session);
    if (!session->instance) { session_destroy(session); goto failure; }
    rdpSettings* settings = session->instance->context->settings;
    failure_stage = "连接与安全设置";
    BOOL ok = freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host) &&
        freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, (UINT32)port) &&
        freerdp_settings_set_string(settings, FreeRDP_Username, user) &&
        freerdp_settings_set_string(settings, FreeRDP_Password, password) &&
        freerdp_settings_set_string(settings, FreeRDP_Domain, domain) &&
        freerdp_settings_set_string(settings, FreeRDP_UserSpecifiedServerName,
            server_name && *server_name ? server_name : NULL) && configure_security(settings);
    if (!ok) { session_destroy(session); goto failure; }
    failure_stage = "显示与通道设置";
    if (!configure_session_profile(settings, width, height, touch, compatibility)) {
        session_destroy(session); goto failure;
    }
    snprintf(session->diag, sizeof(session->diag), "engine=FreeRDP %s\ncodec=GDI (no H.264)\n", FREERDP_VERSION_FULL);
    s_diag(session, compatibility ? "connection=compatibility-retry" : "display-control=requested");
    s_diag(session, touch && !compatibility ? "rdpei=requested" : "rdpei=disabled");
    s_status(session, "正在连接...");
    failure_stage = "协议线程";
    if (pthread_create(&session->thread, NULL, thread_fn, session) != 0) {
        session_destroy(session); goto failure;
    }
    session->thread_started = 1;
    pthread_mutex_lock(&g_lock); g_session = session; pthread_mutex_unlock(&g_lock);
    result = 1;
    goto unlock;
failure:
    pthread_mutex_lock(&g_lock);
    snprintf(g_startup_error, sizeof(g_startup_error), "无法创建远程桌面会话（阶段：%s）", failure_stage);
    LOGE("Session initialization failed at stage: %s", failure_stage);
    pthread_mutex_unlock(&g_lock);
unlock:
    pthread_mutex_unlock(&g_lifecycle_lock);
release:
    if (host) (*env)->ReleaseStringUTFChars(env, jhost, host);
    if (user) (*env)->ReleaseStringUTFChars(env, juser, user);
    if (password) (*env)->ReleaseStringUTFChars(env, jpassword, password);
    if (domain) (*env)->ReleaseStringUTFChars(env, jdomain, domain);
    if (server_name) (*env)->ReleaseStringUTFChars(env, jserver_name, server_name);
    if (expected_pin) (*env)->ReleaseStringUTFChars(env, jexpected_fingerprint, expected_pin);
    return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect4(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jstring n,jint w,jint hh,jboolean t,jboolean compat) {
    /* Legacy API is intentionally untrusted, never a certificate-ignore escape. */
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect5(e,o,h,p,u,pw,d,n,w,hh,t,compat,NULL);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect3(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jstring n,jint w,jint hh,jboolean t) {
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect4(e,o,h,p,u,pw,d,n,w,hh,t,JNI_FALSE);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect2(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jstring n,jint w,jint hh) {
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect4(e,o,h,p,u,pw,d,n,w,hh,JNI_TRUE,JNI_FALSE);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jint w,jint hh) {
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect4(e,o,h,p,u,pw,d,NULL,w,hh,JNI_TRUE,JNI_FALSE);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeDisconnect(JNIEnv* e,jobject o) {
    (void)e; (void)o;
    pthread_mutex_lock(&g_lifecycle_lock);
    pthread_mutex_lock(&g_lock);
    RdpSession* old = g_session; g_session = NULL;
    pthread_mutex_unlock(&g_lock);
    session_destroy(old);
    pthread_mutex_unlock(&g_lifecycle_lock);
    return 0;
}
JNIEXPORT jobjectArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetCertificateChallenge(JNIEnv* env,jobject obj) {
    (void)obj;
    char generation[32], port[16], host[256], name[256], reason[32];
    RdpCertificateInfo info;
    int available = 0;
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (s) {
        pthread_mutex_lock(&s->mutex);
        if (s->certificate_challenge && !atomic_load(&s->terminating)) {
            available = 1;
            info = s->certificate;
            snprintf(generation, sizeof(generation), "%lld", (long long)s->generation);
            snprintf(port, sizeof(port), "%d", s->endpoint_port);
            snprintf(host, sizeof(host), "%s", s->endpoint_host);
            snprintf(name, sizeof(name), "%s", s->endpoint_name);
            snprintf(reason, sizeof(reason), "%s", s->certificate_reason);
        }
        pthread_mutex_unlock(&s->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    if (!available) return NULL;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, 8, string_class, NULL);
    (*env)->DeleteLocalRef(env, string_class);
    if (!result) return NULL;
    const char* values[8] = {generation, info.fingerprint, info.subject, info.issuer, host, port, name, reason};
    for (int i = 0; i < 8; ++i) {
        jstring value = (*env)->NewStringUTF(env, values[i]);
        if (!value) { (*env)->DeleteLocalRef(env, result); return NULL; }
        (*env)->SetObjectArrayElement(env, result, i, value);
        (*env)->DeleteLocalRef(env, value);
        if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, result); return NULL; }
    }
    return result;
}
JNIEXPORT jboolean JNICALL Java_com_rdp_sync_network_RdpConnector_nativeIsConnected(JNIEnv* e,jobject o) {
    (void)e; (void)o;
    pthread_mutex_lock(&g_lock);
    const int connected = g_session && atomic_load(&g_session->connected);
    pthread_mutex_unlock(&g_lock);
    return connected ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetStatus(JNIEnv* e,jobject o) {
    (void)o;
    char text[4096] = "未连接";
    pthread_mutex_lock(&g_lock);
    if (g_startup_error[0]) snprintf(text, sizeof(text), "%s", g_startup_error);
    else if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        snprintf(text, sizeof(text), "%s", g_session->last_error[0] ? g_session->last_error : g_session->status);
        pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return (*e)->NewStringUTF(e, text);
}
JNIEXPORT jstring JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetDiag(JNIEnv* e,jobject o) {
    (void)o;
    char text[6144] = "";
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (s) {
        pthread_mutex_lock(&s->mutex);
        snprintf(text, sizeof(text), "%srdpei=%s\ndisplay-state=%d\nframe=%dx%d\n"
                 "rtt-ms=%lld\nbandwidth-kbps=%lld\ninput-queue-drops=%lld\n",
                 s->diag, s->touch_available ? "connected" : "not-negotiated", s->disp_state,
                 s->fb_width, s->fb_height, (long long)s->rtt_ms, (long long)s->bandwidth_kbps,
                 (long long)s->input_drops);
        pthread_mutex_unlock(&s->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return (*e)->NewStringUTF(e, text);
}
static int frame_dimension(int horizontal) {
    pthread_mutex_lock(&g_lock);
    int result = 0;
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        result = horizontal ? g_session->fb_width : g_session->fb_height;
        pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetWidth(JNIEnv* e,jobject o) { (void)e;(void)o; return frame_dimension(1); }
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetHeight(JNIEnv* e,jobject o) { (void)e;(void)o; return frame_dimension(0); }
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetFrameId(JNIEnv* e,jobject o) {
    (void)e; (void)o;
    pthread_mutex_lock(&g_lock);
    int64_t result = 0;
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex); result = g_session->frame_id; pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(
    JNIEnv* e,jobject o,jobject bitmap,jintArray dirty,jboolean force_full) {
    (void)o;
    if (!bitmap || !dirty || (*e)->GetArrayLength(e, dirty) < 4) return 0;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(e, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    int64_t result = 0;
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (!s) goto done;
    pthread_mutex_lock(&s->mutex);
    if (!s->fb_pixels || (int)info.width != s->fb_width || (int)info.height != s->fb_height ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride < info.width * 4) goto unlock;
    RdpDirtyRect rect = force_full ? (RdpDirtyRect){0, 0, s->fb_width, s->fb_height} : s->dirty;
    if (!rdpsync_clip_rect(&rect, s->fb_width, s->fb_height)) goto unlock;
    void* pixels = NULL;
    const int64_t started = monotonic_us();
    if (AndroidBitmap_lockPixels(e, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) goto unlock;
    for (int row = 0; row < rect.height; ++row) {
        uint8_t* dst = (uint8_t*)pixels + (size_t)(rect.y + row) * info.stride + (size_t)rect.x * 4;
        const uint32_t* src = s->fb_pixels + (size_t)(rect.y + row) * s->fb_width + rect.x;
        memcpy(dst, src, (size_t)rect.width * 4);
    }
    if (AndroidBitmap_unlockPixels(e, bitmap) != ANDROID_BITMAP_RESULT_SUCCESS) goto unlock;
    const jint bounds[4] = {rect.x, rect.y, rect.width, rect.height};
    (*e)->SetIntArrayRegion(e, dirty, 0, 4, bounds);
    if (!(*e)->ExceptionCheck(e)) {
        result = s->frame_id;
        s->dirty = (RdpDirtyRect){0};
        ++s->bitmap_copies;
        s->bitmap_copy_us += monotonic_us() - started;
    }
unlock:
    pthread_mutex_unlock(&s->mutex);
done:
    pthread_mutex_unlock(&g_lock);
    return result;
}
/* Legacy int-array APIs remain binary compatible. They return Android ARGB ints
 * (different from RGBA bytes), converting only for legacy callers. */
static int64_t copy_argb(JNIEnv* e, RdpSession* s, jintArray buffer) {
    if (!buffer || !s->fb_pixels || (size_t)(*e)->GetArrayLength(e, buffer) < s->fb_size) return 0;
    jint* pixels = (*e)->GetIntArrayElements(e, buffer, NULL);
    if (!pixels) return 0;
    for (size_t i = 0; i < s->fb_size; ++i) pixels[i] = (jint)rdpsync_rgba_to_argb(s->fb_pixels[i]);
    (*e)->ReleaseIntArrayElements(e, buffer, pixels, 0);
    return s->frame_id;
}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameArgb(JNIEnv* e,jobject o,jintArray buffer) {
    (void)o;
    int64_t result = 0;
    pthread_mutex_lock(&g_lock);
    if (g_session) { pthread_mutex_lock(&g_session->mutex); result = copy_argb(e, g_session, buffer); pthread_mutex_unlock(&g_session->mutex); }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameDirtyArgb(JNIEnv* e,jobject o,jintArray buffer,jintArray dirty) {
    (void)o;
    if (!dirty || (*e)->GetArrayLength(e, dirty) < 4) return 0;
    int64_t result = 0;
    pthread_mutex_lock(&g_lock);
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        result = copy_argb(e, g_session, buffer);
        const jint bounds[4] = {0, 0, g_session->fb_width, g_session->fb_height};
        if (result) (*e)->SetIntArrayRegion(e, dirty, 0, 4, bounds);
        pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jintArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetFrameArgb(JNIEnv* e,jobject o,jint w,jint h) {
    (void)o; (void)w; (void)h;
    jintArray result = NULL;
    pthread_mutex_lock(&g_lock);
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        if (g_session->fb_pixels) {
            result = (*e)->NewIntArray(e, (jsize)g_session->fb_size);
            if (result) copy_argb(e, g_session, result);
        }
        pthread_mutex_unlock(&g_session->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeRequestResize(JNIEnv* e,jobject o,jint width,jint height,jint scale) {
    (void)e; (void)o;
    if (!rdpsync_valid_size(width, height) || scale < 100 || scale > 500) return -3;
    int result = 0;
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (!s || !atomic_load(&s->connected) || atomic_load(&s->terminating)) { result = -1; goto done; }
    pthread_mutex_lock(&s->mutex);
    if (s->disp_state != 2) result = -2;
    else if ((uint64_t)width * height > s->max_monitor_area) result = -4;
    else {
        s->resize_width = width; s->resize_height = height; s->resize_scale = scale;
        s->resize_pending = 1;
        SetEvent(s->wake_event);
    }
    pthread_mutex_unlock(&s->mutex);
done:
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetDisplayControlState(JNIEnv* e,jobject o) {
    (void)e; (void)o;
    int result = 0;
    pthread_mutex_lock(&g_lock);
    if (g_session) { pthread_mutex_lock(&g_session->mutex); result = g_session->disp_state; pthread_mutex_unlock(&g_session->mutex); }
    pthread_mutex_unlock(&g_lock);
    return result;
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetTelemetry(JNIEnv* e,jobject o) {
    (void)o;
    jlong values[12] = {0, 0, 0, 0, 0, -1, -1, -1, 0, 0, 0, 0};
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (s) {
        pthread_mutex_lock(&s->mutex);
        values[0] = s->frame_id; values[1] = s->bitmap_copies; values[2] = s->dirty_pixels;
        values[3] = s->paint_copy_us; values[4] = s->bitmap_copy_us;
        values[5] = s->rtt_ms; values[6] = s->bandwidth_kbps;
        values[7] = s->network_sample_ms > 0 ? monotonic_us() / 1000 - s->network_sample_ms : -1;
        values[8] = s->fb_width; values[9] = s->fb_height; values[10] = s->input_drops; values[11] = s->generation;
        pthread_mutex_unlock(&s->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    jlongArray result = (*e)->NewLongArray(e, 12);
    if (result) (*e)->SetLongArrayRegion(e, result, 0, 12, values);
    return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendPointerEvent(JNIEnv* e,jobject o,jint x,jint y,jint button) {
    (void)e; (void)o;
    if (button < 0 || button > 3) return -3;
    return enqueue_input((InputCommand){button ? INPUT_CLICK : INPUT_MOVE, x, y, button, 0});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendMouseButton(JNIEnv* e,jobject o,jint x,jint y,jint button,jboolean down) {
    (void)e; (void)o;
    if (button < 1 || button > 3) return -3;
    return enqueue_input((InputCommand){INPUT_BUTTON, x, y, button, down == JNI_TRUE});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendWheelEvent(JNIEnv* e,jobject o,jint x,jint y,jint delta) {
    (void)e; (void)o;
    if (delta < -32767 || delta > 32767) return -3;
    return enqueue_input((InputCommand){INPUT_WHEEL, x, y, delta, 0});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendHWheelEvent(JNIEnv* e,jobject o,jint x,jint y,jint delta) {
    (void)e; (void)o;
    if (delta < -32767 || delta > 32767) return -3;
    return enqueue_input((InputCommand){INPUT_HWHEEL, x, y, delta, 0});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendTouchEvent(JNIEnv* e,jobject o,jint pointer,jint event,jint x,jint y) {
    (void)e; (void)o;
    if (pointer < 0 || pointer > 255 || event < 0 || event > 3) return -3;
    return enqueue_input((InputCommand){INPUT_TOUCH, x, y, pointer + 1, event});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendKeyEvent(JNIEnv* e,jobject o,jint code,jint down) {
    (void)e; (void)o;
    return enqueue_input((InputCommand){INPUT_KEY, 0, 0, code, down != 0});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendUnicodeChar(JNIEnv* e,jobject o,jint code) {
    (void)e; (void)o;
    if (code < 0 || code > 0xffff) return -3;
    return enqueue_input((InputCommand){INPUT_UNICODE, 0, 0, code, 0});
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendClipboardText(JNIEnv* e,jobject o,jstring text) {
    (void)e; (void)o; (void)text;
    return -2; /* Clipboard channel is not built; do not claim a successful paste. */
}
