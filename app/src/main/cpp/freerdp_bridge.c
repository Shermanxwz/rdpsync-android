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
#include <freerdp/gdi/gfx.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/codecs.h>
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
#include "rdpsync_context.h"
#include "rdpsync_gfx_policy.h"
#include "rdpsync_transfer.h"
#include "rdpsync_ubuntu_ime.h"
static atomic_int g_next_text_bridge = -1;
static atomic_int g_next_hw_h264 = 0;
static pthread_mutex_t g_ubuntu_text_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_input_sync_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_input_sync_cond;
static pthread_once_t g_input_sync_once = PTHREAD_ONCE_INIT;
static uint64_t g_input_next_seq = 0;
static uint64_t g_input_done_seq = 0;
static uint64_t g_input_failed_seq = 0;
static int64_t g_input_aborted_gen = 0;

static void init_input_sync_once(void) {
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
#ifdef CLOCK_MONOTONIC
    (void)pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
#endif
    pthread_cond_init(&g_input_sync_cond, &attr);
    pthread_condattr_destroy(&attr);
}
static void notify_input_sync_done(uint64_t seq, BOOL ok) {
    if (!seq) return;
    pthread_once(&g_input_sync_once, init_input_sync_once);
    pthread_mutex_lock(&g_input_sync_lock);
    if (seq > g_input_done_seq) g_input_done_seq = seq;
    if (!ok) g_input_failed_seq = seq;
    pthread_cond_broadcast(&g_input_sync_cond);
    pthread_mutex_unlock(&g_input_sync_lock);
}
static void notify_input_sync_abort(int64_t gen) {
    if (gen <= 0) return;
    pthread_once(&g_input_sync_once, init_input_sync_once);
    pthread_mutex_lock(&g_input_sync_lock);
    if (gen > g_input_aborted_gen) g_input_aborted_gen = gen;
    pthread_cond_broadcast(&g_input_sync_cond);
    pthread_mutex_unlock(&g_input_sync_lock);
}
#include "rdpsync_drive.h"

#define TAG "FreeRDP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define INPUT_CAPACITY 4096

typedef enum { INPUT_MOVE, INPUT_CLICK, INPUT_BUTTON, INPUT_WHEEL, INPUT_HWHEEL,
               INPUT_TOUCH, INPUT_KEY, INPUT_UNICODE, INPUT_UBUNTU_HEX } InputKind;
typedef struct { InputKind kind; int x, y, code, action; } InputCommand;

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
    uint64_t command_sync_seq[INPUT_CAPACITY];
    size_t command_head, command_count;
    DispClientContext* disp;
    int disp_state; /* -1 disabled, 0 unnegotiated, 1 waiting caps, 2 ready, -2 failed */
    uint64_t max_monitor_area;
    int resize_pending, resize_width, resize_height, resize_scale;
    int touch_available;
    RdpgfxClientContext* gfx;
    RdpTransfer* transfer;
    RdpDrive* drive;
    int file_transfer_enabled, text_bridge_enabled, peer_os_major, hw_h264_active, hw_h264_failed;
    RdpTransferIngress transfer_ingress;
    pReceiveChannelData receive_channel_data;
    int gfx_ready, gfx_last_codec;
    int64_t gfx_commands;
    RdpGfxBudget gfx_budget;
    RdpGfxCacheBudget gfx_cache_budget;
    pcRdpgfxResetGraphics gfx_reset;
    pcRdpgfxCreateSurface gfx_create;
    pcRdpgfxDeleteSurface gfx_delete;
    pcRdpgfxMapSurfaceToOutput gfx_map;
    pcRdpgfxMapSurfaceToScaledOutput gfx_map_scaled;
    pcRdpgfxSurfaceCommand gfx_command;
    pcRdpgfxSurfaceToCache gfx_cache;
    pcRdpgfxEvictCacheEntry gfx_evict;
    pcRdpgfxOnOpen gfx_open;
    char expected_fingerprint[65], endpoint_host[256], endpoint_name[256];
    int endpoint_port, expected_pin_state, certificate_verified, certificate_challenge, certificate_superseded;
    int same_endpoint_redirect_allowed, redirect_count;
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

#include "rdpsync_sharedspace_jni.inc"

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
            rdpsync_copy_opaque_rgba_fast(dst, src, (size_t)changed.width);
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

/* RDPGFX uses upstream software decoding and optional MediaCodec H.264/AVC444
 * with automatic software fallback when a device decoder rejects a frame. */
static RdpSession* gfx_session(RdpgfxClientContext* gfx) {
    rdpGdi* gdi = gfx ? gfx->custom : NULL;
    RdpSession* s = gdi ? s_from_ctx(gdi->context) : NULL;
    return s && s->gfx == gfx ? s : NULL;
}
static UINT gfx_reject(RdpSession* s, const char* phase) {
    if (s) {
        s_error(s, "图形管线请求无效或超出安全内存限制");
        char message[96]; snprintf(message, sizeof(message), "gfx=rejected-%s", phase);
        s_diag(s, message);
    }
    return ERROR_INVALID_DATA;
}
static UINT gfx_reset(RdpgfxClientContext* gfx, const RDPGFX_RESET_GRAPHICS_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !pdu ||
        !rdpsync_valid_frame_size((int)pdu->width, (int)pdu->height) || pdu->monitorCount > 1)
        return gfx_reject(s, "reset");
    if (pdu->monitorCount) {
        const MONITOR_DEF* m = pdu->monitorDefArray;
        if (!m || m->left != 0 || m->top != 0 || m->right != (INT32)pdu->width - 1 ||
            m->bottom != (INT32)pdu->height - 1) return gfx_reject(s, "monitor-layout");
    }
    return s->gfx_reset(gfx, pdu);
}
static UINT gfx_create(RdpgfxClientContext* gfx, const RDPGFX_CREATE_SURFACE_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !pdu ||
        (pdu->pixelFormat != GFX_PIXEL_FORMAT_XRGB_8888 && pdu->pixelFormat != GFX_PIXEL_FORMAT_ARGB_8888) ||
        rdpsync_gfx_reserve(&s->gfx_budget, pdu->surfaceId, pdu->width, pdu->height) < 0)
        return gfx_reject(s, "surface-budget");
    UINT result = s->gfx_create(gfx, pdu);
    if (result != CHANNEL_RC_OK) rdpsync_gfx_release(&s->gfx_budget, pdu->surfaceId);
    return result;
}
static UINT gfx_delete(RdpgfxClientContext* gfx, const RDPGFX_DELETE_SURFACE_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !pdu) return ERROR_INVALID_DATA;
    UINT result = s->gfx_delete(gfx, pdu);
    if (result == CHANNEL_RC_OK) rdpsync_gfx_release(&s->gfx_budget, pdu->surfaceId);
    return result;
}
static UINT gfx_map(RdpgfxClientContext* gfx, const RDPGFX_MAP_SURFACE_TO_OUTPUT_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    rdpGdi* gdi = gfx ? gfx->custom : NULL;
    const int slot = s && pdu ? rdpsync_gfx_slot(&s->gfx_budget, pdu->surfaceId) : -1;
    if (!s || !s->gfx_ready || !gdi || slot < 0 ||
        !rdpsync_gfx_output_fits(gdi->width, gdi->height, pdu->outputOriginX, pdu->outputOriginY,
            s->gfx_budget.slots[slot].width, s->gfx_budget.slots[slot].height))
        return gfx_reject(s, "output-map");
    return s->gfx_map(gfx, pdu);
}
static UINT gfx_map_scaled(RdpgfxClientContext* gfx, const RDPGFX_MAP_SURFACE_TO_SCALED_OUTPUT_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    rdpGdi* gdi = gfx ? gfx->custom : NULL;
    if (!s || !s->gfx_ready || !gdi || !pdu || rdpsync_gfx_slot(&s->gfx_budget, pdu->surfaceId) < 0 ||
        !rdpsync_gfx_output_fits(gdi->width, gdi->height, pdu->outputOriginX, pdu->outputOriginY,
            pdu->targetWidth, pdu->targetHeight)) return gfx_reject(s, "scaled-output-map");
    return s->gfx_map_scaled(gfx, pdu);
}
static UINT gfx_command(RdpgfxClientContext* gfx, const RDPGFX_SURFACE_COMMAND* cmd) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !cmd) return gfx_reject(s, "codec");
    const int is_avc = (cmd->codecId == RDPGFX_CODECID_AVC420 ||
        cmd->codecId == RDPGFX_CODECID_AVC444 || cmd->codecId == RDPGFX_CODECID_AVC444v2);
    /* GDI's unknown/ProgressiveV2 default can otherwise return success without
     * painting. Fail explicitly instead of silently displaying stale frames. */
    switch (cmd->codecId) {
        case RDPGFX_CODECID_UNCOMPRESSED: case RDPGFX_CODECID_CAVIDEO:
        case RDPGFX_CODECID_CLEARCODEC: case RDPGFX_CODECID_PLANAR:
        case RDPGFX_CODECID_ALPHA: case RDPGFX_CODECID_CAPROGRESSIVE: break;
        case RDPGFX_CODECID_AVC420: case RDPGFX_CODECID_AVC444:
        case RDPGFX_CODECID_AVC444v2:
            if (!s->hw_h264_active) return gfx_reject(s, "unsupported-codec");
            break;
        default: return gfx_reject(s, "unsupported-codec");
    }
    UINT result = s->gfx_command(gfx, cmd);
    if (result == CHANNEL_RC_OK) {
        pthread_mutex_lock(&s->mutex);
        s->gfx_last_codec = (int)cmd->codecId; ++s->gfx_commands;
        pthread_mutex_unlock(&s->mutex);
    } else if (is_avc && s->hw_h264_active) {
        pthread_mutex_lock(&s->mutex);
        s->hw_h264_failed = 1;
        pthread_mutex_unlock(&s->mutex);
        s_error(s, "H.264 硬件解码不兼容，正在自动切换软件渲染");
        s_diag(s, "gfx=hw-h264-decode-failed; fallback-software");
    }
    return result;
}
static UINT gfx_evict(RdpgfxClientContext* gfx, const RDPGFX_EVICT_CACHE_ENTRY_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !pdu || pdu->cacheSlot == 0 || pdu->cacheSlot > RDPSYNC_GFX_CACHE_SLOTS)
        return gfx_reject(s, "cache-slot");
    UINT result = s->gfx_evict(gfx, pdu);
    if (result == CHANNEL_RC_OK) rdpsync_gfx_cache_release(&s->gfx_cache_budget, pdu->cacheSlot);
    return result;
}
static UINT gfx_cache(RdpgfxClientContext* gfx, const RDPGFX_SURFACE_TO_CACHE_PDU* pdu) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready || !pdu) return gfx_reject(s, "cache");
    int slot = rdpsync_gfx_slot(&s->gfx_budget, pdu->surfaceId);
    const RECTANGLE_16* r = &pdu->rectSrc;
    if (slot < 0 || r->right <= r->left || r->bottom <= r->top ||
        r->right > s->gfx_budget.slots[slot].width || r->bottom > s->gfx_budget.slots[slot].height)
        return gfx_reject(s, "cache-rectangle");
    uint64_t cost = rdpsync_gfx_cache_cost(&s->gfx_cache_budget, pdu->cacheSlot,
        r->right - r->left, r->bottom - r->top);
    if (!cost) return gfx_reject(s, "cache-budget");
    UINT result = s->gfx_cache(gfx, pdu);
    if (result == CHANNEL_RC_OK) {
        rdpsync_gfx_cache_release(&s->gfx_cache_budget, pdu->cacheSlot);
        s->gfx_cache_budget.slots[pdu->cacheSlot - 1] = cost;
        s->gfx_cache_budget.bytes += cost;
    }
    return result;
}
static UINT gfx_open(RdpgfxClientContext* gfx, BOOL* advertise, BOOL* acknowledge) {
    RdpSession* s = gfx_session(gfx);
    if (!s || !s->gfx_ready) {
        /* rdpgfx_on_open would overwrite an error if it still sent caps. */
        if (advertise) *advertise = FALSE;
        if (acknowledge) *acknowledge = FALSE;
        return CHANNEL_RC_INITIALIZATION_ERROR;
    }
    return s->gfx_open ? s->gfx_open(gfx, advertise, acknowledge) : CHANNEL_RC_OK;
}
static BOOL gfx_attach(RdpSession* s, RdpgfxClientContext* gfx) {
    rdpGdi* gdi = s && s->instance && s->instance->context ? s->instance->context->gdi : NULL;
    if (!s || !gfx || s->gfx) return FALSE;
    s->gfx = gfx;
    s->gfx_open = gfx->OnOpen;
    gfx->OnOpen = gfx_open;
    if (!gdi) return FALSE;
    if (!gdi_graphics_pipeline_init(gdi, gfx)) {
        /* Upstream allocates codecs BEFORE initializing gfx->mux. The ordinary
         * uninit would delete an uninitialized mutex on this partial path. */
        freerdp_client_codecs_free(gfx->codecs); gfx->codecs = NULL;
        gdi->gfx = NULL;
        return FALSE;
    }
    s->gfx_reset = gfx->ResetGraphics; gfx->ResetGraphics = gfx_reset;
    s->gfx_create = gfx->CreateSurface; gfx->CreateSurface = gfx_create;
    s->gfx_delete = gfx->DeleteSurface; gfx->DeleteSurface = gfx_delete;
    s->gfx_map = gfx->MapSurfaceToOutput; gfx->MapSurfaceToOutput = gfx_map;
    s->gfx_map_scaled = gfx->MapSurfaceToScaledOutput; gfx->MapSurfaceToScaledOutput = gfx_map_scaled;
    s->gfx_command = gfx->SurfaceCommand; gfx->SurfaceCommand = gfx_command;
    s->gfx_cache = gfx->SurfaceToCache; gfx->SurfaceToCache = gfx_cache;
    s->gfx_evict = gfx->EvictCacheEntry; gfx->EvictCacheEntry = gfx_evict;
    pthread_mutex_lock(&s->mutex); s->gfx_ready = 1; pthread_mutex_unlock(&s->mutex);
    return TRUE;
}
static void gfx_detach(RdpSession* s, RdpgfxClientContext* gfx) {
    if (!s || !gfx || s->gfx != gfx) return;
    /* DVC OnClose already deletes surfaces before raising ChannelDisconnected.
     * Keep GDI alive until PostFinalDisconnect so custom/context remain valid. */
    if (s->gfx_ready) gdi_graphics_pipeline_uninit(s->instance->context->gdi, gfx);
    gfx->custom = NULL;
    gfx->OnOpen = s->gfx_open;
    s->gfx = NULL;
    memset(&s->gfx_budget, 0, sizeof(s->gfx_budget));
    memset(&s->gfx_cache_budget, 0, sizeof(s->gfx_cache_budget));
    pthread_mutex_lock(&s->mutex); s->gfx_ready = 0; pthread_mutex_unlock(&s->mutex);
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
    if (strcmp(event->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
        if (!gfx_attach(s, event->pInterface)) {
            s_error(s, "无法初始化远程图形管线，已阻止不完整会话");
            s_diag(s, "gfx=initialization-failed");
            atomic_store(&s->terminating, 1);
            freerdp_abort_connect_context(s->instance->context);
        }
    } else if (strcmp(event->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        rdpsync_transfer_attach(s->transfer, event->pInterface);
    } else freerdp_client_OnChannelConnectedEventHandler(context, event);
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
    if (strcmp(event->name, RDPGFX_DVC_CHANNEL_NAME) == 0) gfx_detach(s, event->pInterface);
    else if (strcmp(event->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) rdpsync_transfer_detach(s->transfer, event->pInterface);
    else freerdp_client_OnChannelDisconnectedEventHandler(context, event);
    pthread_mutex_unlock(&s->channel_mutex);
}
/* Bound the opted-in clipboard message BEFORE upstream fragment reassembly.
 * This is independent of file-parser checks, and leaves every other channel's
 * existing receive delegate untouched. No arbitrary clipboard text is fetched. */
static BOOL receive_channel_data(freerdp* instance, UINT16 id, const BYTE* data,
    size_t size, UINT32 flags, size_t total) {
    RdpSession* s = s_from_ctx(instance->context);
    const char* name = freerdp_channels_get_name_by_id(instance, id);
    if (name && strcmp(name, "rdpdr") == 0 && s &&
        !rdpsync_drive_ingress(s->drive, data, size, flags, total)) {
        s_error(s, "共享空间通道请求超出安全限制");
        s_diag(s, "shared-space=ingress-rejected");
        return FALSE;
    }
    if (name && strcmp(name, CLIPRDR_SVC_CHANNEL_NAME) == 0 && s &&
        s->text_bridge_enabled && !s->file_transfer_enabled) {
        if (!s->transfer_ingress.active && s->transfer_ingress.messages >= 4096U) {
            s->transfer_ingress.messages = 0;
        }
        if (!rdpsync_transfer_ingress(&s->transfer_ingress, data, size, flags, total)) {
            s->transfer_ingress = (RdpTransferIngress){0};
            s_diag(s, "text-bridge=ignored-nonstandard-cliprdr-pdu");
            return TRUE;
        }
        if ((flags & CHANNEL_FLAG_FIRST) && !(flags & (CHANNEL_FLAG_SUSPEND | CHANNEL_FLAG_RESUME)) &&
            !rdpsync_transfer_admit_incoming(s->transfer, data, size, total)) {
            s->transfer_ingress = (RdpTransferIngress){0};
            s_diag(s, "text-bridge=ignored-oversized-cliprdr-pdu");
            return TRUE;
        }
        return s->receive_channel_data && s->receive_channel_data(instance, id, data, size, flags, total);
    }
    if (name && strcmp(name, CLIPRDR_SVC_CHANNEL_NAME) == 0 &&
        (!s || !rdpsync_transfer_ingress(&s->transfer_ingress, data, size, flags, total))) {
        s_error(s, "文件传输通道消息超出安全限制");
        s_diag(s, "file-transfer=ingress-framing-rejected");
        return FALSE;
    }
    if (name && strcmp(name, CLIPRDR_SVC_CHANNEL_NAME) == 0 &&
        (flags & CHANNEL_FLAG_FIRST) && !(flags & (CHANNEL_FLAG_SUSPEND | CHANNEL_FLAG_RESUME)) &&
        !rdpsync_transfer_admit_incoming(s->transfer, data, size, total)) {
        s_error(s, "文件传输通道收到未请求或超额的数据");
        s_diag(s, "file-transfer=unsolicited-or-budget-rejected");
        return FALSE;
    }
    return s && s->receive_channel_data && s->receive_channel_data(instance, id, data, size, flags, total);
}
static BOOL pre_connect(freerdp* instance) {
    RdpSession* s = s_from_ctx(instance->context);
    if (s) {
        s_diag(s, "connection=pre-connect");
        if (instance->ReceiveChannelData != receive_channel_data) {
            s->receive_channel_data = instance->ReceiveChannelData;
            instance->ReceiveChannelData = receive_channel_data;
        }
    }
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
    s_diag(session, "connection=post-connect");
#ifdef __ANDROID__
    {
        extern UINT32 rdpsync_freerdp_peer_os_major(const rdpSettings* settings);
        session->peer_os_major = (int)rdpsync_freerdp_peer_os_major(instance->context->settings);
    }
#else
    session->peer_os_major = (int)freerdp_settings_get_uint32(
        instance->context->settings, FreeRDP_OsMajorType);
#endif
    {
        char os_line[64];
        snprintf(os_line, sizeof(os_line), "peer-os-major=%d", session->peer_os_major);
        s_diag(session, os_line);
    }
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
static void post_final_disconnect(freerdp* instance) {
    /* Core invokes this only AFTER closing dynamic channels, whose GFX
     * surface/codec cleanup still references the primary GDI context. */
    RdpSession* session = s_from_ctx(instance->context);
    if (session) rdpsync_transfer_detach(session->transfer, NULL);
    gdi_free(instance);
    PubSub_UnsubscribeChannelConnected(instance->context->pubSub, channel_connected);
    PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, channel_disconnected);
}

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
    if (!s) { LOGE("Certificate callback rejected: missing session"); return 0; }
    s_diag(s, "certificate=callback-entered");
    if (atomic_load(&s->terminating)) {
        s_diag(s, "certificate=rejected-canceled"); return 0;
    }
    pthread_mutex_lock(&s->mutex);
    if (s->certificate_superseded) {
        pthread_mutex_unlock(&s->mutex);
        s_diag(s, "certificate=rejected-superseded"); return 0;
    }
    const int allow_same_endpoint_redirect = s->same_endpoint_redirect_allowed;
    s->certificate_verified = 0; s->certificate_challenge = 0;
    pthread_mutex_unlock(&s->mutex);
    /* Pins never authorize a gateway, legacy, or cross-endpoint redirect.
     * Same-endpoint GNOME Remote Login handover is allowed only after verified
     * handover authorization and still requires an exact SHA-256 pin match. */
    if (!hostname || strcmp(hostname, s->endpoint_name) != 0 || port != s->endpoint_port ||
        (flags & (VERIFY_CERT_FLAG_GATEWAY | VERIFY_CERT_FLAG_LEGACY)) ||
        ((flags & VERIFY_CERT_FLAG_REDIRECT) && !allow_same_endpoint_redirect)) {
        s_error(s, "证书验证失败：连接目标已改变");
        s_diag(s, "certificate=rejected-endpoint");
        return 0;
    }
    RdpCertificateInfo info;
    if (!rdpsync_certificate_info(pem, length, &info)) {
        s_error(s, "证书验证失败：无法读取服务器证书");
        s_diag(s, "certificate=rejected-pem");
        return 0;
    }
    pthread_mutex_lock(&s->mutex);
    if (s->certificate_superseded || atomic_load(&s->terminating)) {
        pthread_mutex_unlock(&s->mutex);
        s_diag(s, "certificate=rejected-stale-result"); return 0;
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
    s_diag(s, trusted ? "certificate=pin-matched" : "certificate=challenge-published");
    /* Do not block a native thread waiting for UI. Reject now; explicit approval
     * may start a NEW connection with a new immutable expected-pin snapshot. */
    return trusted ? 2 : 0;
}
static BOOL reject_redirect(freerdp* instance) {
    RdpSession* s = instance && instance->context ? s_from_ctx(instance->context) : NULL;
    rdpSettings* settings = instance && instance->context ? instance->context->settings : NULL;
    if (s && settings) {
        pthread_mutex_lock(&s->mutex);
        const int verified = s->certificate_verified && !s->certificate_superseded &&
            !atomic_load(&s->terminating) && s->redirect_count < 2;
        pthread_mutex_unlock(&s->mutex);
        if (verified) {
            const char* target_host = freerdp_settings_get_string(settings, FreeRDP_ServerHostname);
            const UINT32 target_port = freerdp_settings_get_uint32(settings, FreeRDP_ServerPort);
            const int host_matches = target_host &&
                (strcmp(target_host, s->endpoint_host) == 0 || strcmp(target_host, s->endpoint_name) == 0);
            const int port_matches = (target_port == 0 || (int)target_port == s->endpoint_port);
            if (host_matches && port_matches) {
                pthread_mutex_lock(&s->mutex);
                s->same_endpoint_redirect_allowed = 1;
                ++s->redirect_count;
                pthread_mutex_unlock(&s->mutex);
                s_diag(s, "redirect=same-endpoint-handover-allowed");
                return TRUE;
            }
        }
    }
    if (s) {
        s_error(s, "已阻止服务器重定向；请为新目标创建独立连接并验证证书");
        s_diag(s, "redirect=blocked; cross-endpoint or unverified handover denied");
    }
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
    PROFILE_SETTING(uint16, FreeRDP_DesktopOrientation, height > width ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE);
    PROFILE_SETTING(uint32, FreeRDP_DesktopPhysicalWidth, width * 254 / 960);
    PROFILE_SETTING(uint32, FreeRDP_DesktopPhysicalHeight, height * 254 / 960);
    /* Single-monitor client: do NOT require the server's initial Monitor Layout
     * PDU. FreeRDP waits for it before activation if this is TRUE, and servers
     * such as xrdp omit it. DISP dynamic resize below is a separate DVC feature.
     * Upstream X11 likewise enables the initial PDU only for >1 monitor. */
    PROFILE_SETTING(bool, FreeRDP_SupportMonitorLayoutPdu, FALSE);
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
    /* GNOME Remote Desktop requires real RDPGFX even when AVC is disabled.
     * Upstream GDI handles RemoteFX progressive/planar/uncompressed decoding. */
    PROFILE_SETTING(bool, FreeRDP_SupportGraphicsPipeline, !compatibility);
    PROFILE_SETTING(bool, FreeRDP_GfxSmallCache, TRUE);
    PROFILE_SETTING(bool, FreeRDP_BitmapCachePersistEnabled, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxH264, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxAVC444, FALSE);
    PROFILE_SETTING(bool, FreeRDP_GfxAVC444v2, FALSE);
    PROFILE_SETTING(bool, FreeRDP_SupportDynamicChannels, !compatibility);
#undef PROFILE_SETTING
    return TRUE;
}
static void configure_performance_profile(RdpSession* session, rdpSettings* settings, BOOL compatibility) {
    if (!settings) return;
    /* Keep TCP alive across aggressive IPv4 home/CGNAT firewalls & mobile carriers. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_TcpKeepAlive, TRUE);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay, 15);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, 5);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, 3);
    const int want_hw = atomic_exchange(&g_next_hw_h264, 0);
    if (compatibility) {
        if (session) session->hw_h264_active = 0;
        return;
    }
    (void)freerdp_settings_set_bool(settings, FreeRDP_GfxSmallCache, TRUE);
    if (want_hw && session) {
        const BOOL h264_ok = freerdp_settings_set_bool(settings, FreeRDP_GfxH264, TRUE) &&
            freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, TRUE) &&
            freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, TRUE);
        session->hw_h264_active = h264_ok ? 1 : 0;
    } else {
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxH264, FALSE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, FALSE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, FALSE);
        if (session) session->hw_h264_active = 0;
    }
}
static UINT32 expected_clipboard_feature_mask(BOOL file_enabled, BOOL text_bridge_enabled) {
    UINT32 mask = 0;
    if (file_enabled) {
        mask |= CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES | CLIPRDR_FLAG_REMOTE_TO_LOCAL_FILES;
    }
    if (text_bridge_enabled) {
        /* Strictly outbound local-to-remote CF_UNICODETEXT only; remote text is never pulled. */
        mask |= CLIPRDR_FLAG_LOCAL_TO_REMOTE;
    }
    return mask;
}
static BOOL configure_clipboard_profile(rdpSettings* settings, BOOL file_enabled, BOOL text_bridge_enabled) {
    const BOOL redirect = (file_enabled || text_bridge_enabled) ? TRUE : FALSE;
    return freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, redirect) &&
        freerdp_settings_set_uint32(settings, FreeRDP_ClipboardFeatureMask,
            expected_clipboard_feature_mask(file_enabled, text_bridge_enabled));
}
static BOOL configure_transfer_profile(rdpSettings* settings, BOOL enabled) {
    return configure_clipboard_profile(settings, enabled, FALSE);
}
static BOOL load_session_channels(freerdp* instance) {
    RdpSession* drive_owner = s_from_ctx(instance->context);
    if (drive_owner && drive_owner->drive) rdpsync_drive_install_provider();
    if (!freerdp_client_load_channels(instance)) return FALSE;
    const rdpSettings* settings = instance->context->settings;
    RdpSession* owner = s_from_ctx(instance->context);
    const BOOL file_enabled = owner && owner->file_transfer_enabled;
    const BOOL text_enabled = owner && owner->text_bridge_enabled;
    const BOOL transfer_enabled = (file_enabled || text_enabled) ? TRUE : FALSE;
    if (freerdp_settings_get_bool(settings, FreeRDP_RedirectClipboard) != transfer_enabled ||
        freerdp_settings_get_uint32(settings, FreeRDP_ClipboardFeatureMask) !=
            expected_clipboard_feature_mask(file_enabled, text_enabled)) {
        LOGE("Channel loading unexpectedly changed clipboard policy");
        return FALSE;
    }
    /* Check AFTER upstream dependency expansion, not only our initial options.
     * A duplicate load can turn fake rdpsnd into AudioPlayback=TRUE. */
    if (freerdp_settings_get_bool(settings, FreeRDP_AudioPlayback) ||
        freerdp_settings_get_bool(settings, FreeRDP_AudioCapture) ||
        !rdpsync_drive_settings_valid(owner ? owner->drive : NULL, settings)) {
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
    instance->PostFinalDisconnect = post_final_disconnect;
    instance->AuthenticateEx = authenticate_ex;
    instance->VerifyX509Certificate = verify_x509_certificate;
    instance->Redirect = reject_redirect;
    return TRUE;
}
static const char* certificate_context_failure(freerdp* instance, RdpSession* expected, int check_external) {
    if (!instance || !instance->context || s_from_ctx(instance->context) != expected)
        return "证书会话绑定";
    if (instance->VerifyX509Certificate != verify_x509_certificate)
        return "证书回调绑定";
    if (check_external && !freerdp_settings_get_bool(instance->context->settings, FreeRDP_ExternalCertificateManagement))
        return "外部证书管理设置";
    return NULL;
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
        case INPUT_UBUNTU_HEX: {
            const uint32_t cp = (uint32_t)command.code;
            if (cp == 0 || cp > 0x10FFFFU) return FALSE;
            char hex[8];
            int len = snprintf(hex, sizeof(hex), "%x", cp);
            if (len <= 0 || len >= (int)sizeof(hex)) return FALSE;
            BOOL ok = input->KeyboardEvent(input, 0, 0x1D) &&
                      input->KeyboardEvent(input, 0, 0x2A) &&
                      input->KeyboardEvent(input, 0, 0x16) &&
                      input->KeyboardEvent(input, KBD_FLAGS_RELEASE, 0x16) &&
                      input->KeyboardEvent(input, KBD_FLAGS_RELEASE, 0x2A) &&
                      input->KeyboardEvent(input, KBD_FLAGS_RELEASE, 0x1D);
            for (int i = 0; ok && i < len; ++i) {
                unsigned nib = (hex[i] >= '0' && hex[i] <= '9')
                    ? (unsigned)(hex[i] - '0') : (unsigned)(hex[i] - 'a' + 10);
                UINT8 sc = rdpsync_hex_nibble_scancode(nib);
                ok = input->KeyboardEvent(input, 0, sc) &&
                     input->KeyboardEvent(input, KBD_FLAGS_RELEASE, sc);
            }
            return ok && input->KeyboardEvent(input, 0, 0x39) &&
                   input->KeyboardEvent(input, KBD_FLAGS_RELEASE, 0x39);
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
        const size_t slot = s->command_head;
        InputCommand command = s->commands[slot];
        const uint64_t sync_seq = s->command_sync_seq[slot];
        s->command_sync_seq[slot] = 0;
        s->command_head = (slot + 1) % INPUT_CAPACITY;
        --s->command_count;
        pthread_mutex_unlock(&s->mutex);
        const BOOL ok = execute_input(s, command);
        if (sync_seq != 0) notify_input_sync_done(sync_seq, ok);
        if (!ok) return FALSE;
    }
    pthread_mutex_lock(&s->mutex);
    const int pending = s->resize_pending;
    const int width = s->resize_width, height = s->resize_height, scale = s->resize_scale;
    s->resize_pending = 0;
    if (s->command_count && s->wake_event) SetEvent(s->wake_event);
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
            layout.Orientation = (height > width) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
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
static void drive_wake_owner(void* context) {
    RdpSession* session = context;
    if (session->wake_event) SetEvent(session->wake_event);
}
#include "rdpsync_bridge_jni.inc"
