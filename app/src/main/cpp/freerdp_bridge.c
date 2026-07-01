/**
 * RdpSync FreeRDP Bridge - proper FreeRDP lifecycle
 * Based on official android_freerdp.c pattern:
 *   client_ctx_new -> sets PreConnect/PostConnect/PostDisconnect/AuthenticateEx
 *   PreConnect -> subscribe events
 *   freerdp_connect() -> internally calls PreConnect, then PostConnect
 *   PostConnect -> gdi_init, register pointer, set BeginPaint/EndPaint/DesktopResize
 *   Thread -> freerdp_get_event_handles / freerdp_check_event_handles loop
 *   PostDisconnect -> gdi_free
 */
#define _GNU_SOURCE
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <time.h>
#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/constants.h>
#include <freerdp/input.h>
#include <freerdp/update.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/version.h>
#include <freerdp/graphics.h>
#include <freerdp/client.h>
#include <freerdp/addin.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/channels/rdpei.h>
#include <freerdp/channels/channels.h>
#include <winpr/synch.h>
#include <winpr/wlog.h>
#include <openssl/evp.h>

#define TAG "FreeRDP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ====== Frame buffer state ======
typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    volatile int connected;
    volatile int terminating;
    volatile int thread_done;
    char status[1024];
    char last_error[4096];
    char diag[4096];
    int fb_width, fb_height, fb_size;
    int32_t* fb_pixels;
    int dirty_x, dirty_y, dirty_w, dirty_h;
    volatile int64_t frame_id;
    freerdp* instance;
    pthread_t thread;
} RdpSession;

static RdpSession* g_session = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_startup_error[1024] = "";

enum {
    TOUCH_EVENT_DOWN = 0,
    TOUCH_EVENT_MOVE = 1,
    TOUCH_EVENT_UP = 2,
    TOUCH_EVENT_CANCEL = 3
};

static void s_status(RdpSession* s, const char* fmt, ...) {
    char b[1024]; va_list ap; va_start(ap,fmt); vsnprintf(b,sizeof(b),fmt,ap); va_end(ap);
    pthread_mutex_lock(&s->mutex);
    strncpy(s->status,b,sizeof(s->status)-1); s->status[sizeof(s->status)-1]=0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->mutex);
}
static void s_error(RdpSession* s, const char* fmt, ...) {
    char b[4096]; va_list ap; va_start(ap,fmt); vsnprintf(b,sizeof(b),fmt,ap); va_end(ap);
    pthread_mutex_lock(&s->mutex);
    s->connected=0; strncpy(s->last_error,b,sizeof(s->last_error)-1);
    s->last_error[sizeof(s->last_error)-1]=0;
    strncpy(s->status,"连接失败",sizeof(s->status)-1); s->status[sizeof(s->status)-1]=0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->mutex);
}
static void s_diag(RdpSession* s, const char* line) {
    pthread_mutex_lock(&s->mutex);
    size_t cur=strlen(s->diag), need=strlen(line)+2;
    if (cur+need<sizeof(s->diag)) { memcpy(s->diag+cur,line,need-1); s->diag[cur+need-2]='\n'; s->diag[cur+need-1]=0; }
    pthread_mutex_unlock(&s->mutex);
}

static void sleep_for_frame_pacing(void) {
    const struct timespec ts = {0, 1000000}; // 1ms
    nanosleep(&ts, NULL);
}

static void normalize_dirty_rect(int w, int h, int* dx, int* dy, int* dw, int* dh) {
    if (*dx < 0) { *dw += *dx; *dx = 0; }
    if (*dy < 0) { *dh += *dy; *dy = 0; }
    if (*dx + *dw > w) *dw = w - *dx;
    if (*dy + *dh > h) *dh = h - *dy;
    if (*dw <= 0 || *dh <= 0) {
        *dx = 0;
        *dy = 0;
        *dw = w;
        *dh = h;
    }
}

// ====== Frame buffer management ======
static RdpSession* s_from_ctx(rdpContext* ctx) {
    if(!ctx||!ctx->instance) return NULL;
    RdpSession* s; pthread_mutex_lock(&g_lock); s=g_session; pthread_mutex_unlock(&g_lock);
    if(!s||s->instance!=ctx->instance) return NULL;
    return s;
}

static BOOL begin_paint(rdpContext* ctx) {
    (void)ctx; return TRUE;
}

static BOOL end_paint(rdpContext* ctx) {
    // Copy the FreeRDP GDI dirty bounds to the session frame buffer.
    RdpSession* s = s_from_ctx(ctx);
    if(!s) return TRUE;
    rdpGdi* gdi = ctx->gdi;
    if(!gdi||!gdi->primary_buffer) return TRUE;
    int w=(int)freerdp_settings_get_uint32(ctx->settings,FreeRDP_DesktopWidth);
    int h=(int)freerdp_settings_get_uint32(ctx->settings,FreeRDP_DesktopHeight);
    int sz=w*h; if(sz<=0) return TRUE;
    int dx = 0, dy = 0, dw = w, dh = h;
    if (gdi->hdc && gdi->hdc->hwnd && gdi->hdc->hwnd->invalid && !gdi->hdc->hwnd->invalid->null) {
        HGDI_RGN invalid = gdi->hdc->hwnd->invalid;
        dx = invalid->x;
        dy = invalid->y;
        dw = invalid->w;
        dh = invalid->h;
    }
    normalize_dirty_rect(w, h, &dx, &dy, &dw, &dh);
    if (dw <= 0 || dh <= 0) return TRUE;

    pthread_mutex_lock(&s->mutex);
    if(sz!=s->fb_size){
        free(s->fb_pixels);
        s->fb_pixels=(int32_t*)calloc((size_t)sz,sizeof(int32_t));
        s->fb_size=sz;
        dx=0;dy=0;dw=w;dh=h;
    }
    if(s->fb_pixels&&gdi->primary_buffer){
        s->fb_width=w;s->fb_height=h;
        /* Convert RGBX32 (R,G,B,X per byte) to ARGB int (A,R,G,B per byte). */
        for (int yy = 0; yy < dh; yy++) {
            const uint8_t* __restrict src8 = (const uint8_t*)gdi->primary_buffer + ((dy + yy) * w + dx) * 4;
            int32_t* __restrict dst32 = s->fb_pixels + ((dy + yy) * w + dx);
            int n = dw;
#pragma clang loop vectorize(enable) interleave(enable)
            while (n >= 4) {
                uint32_t p0 = *(const uint32_t*)(src8);      /* R0,G0,B0,X0 */
                uint32_t p1 = *(const uint32_t*)(src8 + 4);  /* R1,G1,B1,X1 */
                uint32_t p2 = *(const uint32_t*)(src8 + 8);
                uint32_t p3 = *(const uint32_t*)(src8 + 12);
                dst32[0] = (int32_t)(0xFF000000U | ((p0 & 0x00FF0000U) >> 16) | (p0 & 0x0000FF00U) | ((p0 & 0x000000FFU) << 16));
                dst32[1] = (int32_t)(0xFF000000U | ((p1 & 0x00FF0000U) >> 16) | (p1 & 0x0000FF00U) | ((p1 & 0x000000FFU) << 16));
                dst32[2] = (int32_t)(0xFF000000U | ((p2 & 0x00FF0000U) >> 16) | (p2 & 0x0000FF00U) | ((p2 & 0x000000FFU) << 16));
                dst32[3] = (int32_t)(0xFF000000U | ((p3 & 0x00FF0000U) >> 16) | (p3 & 0x0000FF00U) | ((p3 & 0x000000FFU) << 16));
                src8 += 16; dst32 += 4; n -= 4;
            }
            while (n > 0) {
                uint32_t p = *(const uint32_t*)src8;
                *dst32++ = (int32_t)(0xFF000000U | ((p & 0x00FF0000U) >> 16) | (p & 0x0000FF00U) | ((p & 0x000000FFU) << 16));
                src8 += 4; n--;
            }
        }
        s->dirty_x=dx;s->dirty_y=dy;s->dirty_w=dw;s->dirty_h=dh;
        s->frame_id++;
        if (s->frame_id <= 5) {
            LOGI("frame copied id=%lld size=%dx%d dirty=%d,%d %dx%d", (long long)s->frame_id, w, h, dx, dy, dw, dh);
        }
    }
    pthread_mutex_unlock(&s->mutex);
    if(!s->connected){pthread_mutex_lock(&s->mutex);s->connected=1;strncpy(s->status,"已连接",sizeof(s->status)-1);pthread_cond_broadcast(&s->cond);pthread_mutex_unlock(&s->mutex);/* LOGI removed for perf */}
    return TRUE;
}

static BOOL desktop_resize(rdpContext* ctx) {
    LOGI("desktop_resize");
    if(ctx->gdi) { 
        UINT32 w=freerdp_settings_get_uint32(ctx->settings,FreeRDP_DesktopWidth);
        UINT32 h=freerdp_settings_get_uint32(ctx->settings,FreeRDP_DesktopHeight);
        return gdi_resize(ctx->gdi,w,h);
    }
    return TRUE;
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

// ====== PreConnect callback ======
static void rdpsync_OnChannelConnectedEventHandler(void* context, const ChannelConnectedEventArgs* e) {
    if (!context || !e) return;
    if (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) == 0 || strcmp(e->name, RDPEI_CHANNEL_NAME) == 0) {
        LOGI("RDPEI channel connected: %s", e->name);
        RdpSession* s = s_from_ctx((rdpContext*)context);
        if (s) s_diag(s, "rdpei=connected");
    }
    freerdp_client_OnChannelConnectedEventHandler(context, e);
}

static void rdpsync_OnChannelDisconnectedEventHandler(void* context, const ChannelDisconnectedEventArgs* e) {
    if (!context || !e) return;
    if (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) == 0 || strcmp(e->name, RDPEI_CHANNEL_NAME) == 0) {
        LOGI("RDPEI channel disconnected: %s", e->name);
        RdpSession* s = s_from_ctx((rdpContext*)context);
        if (s) s_diag(s, "rdpei=disconnected");
    }
    freerdp_client_OnChannelDisconnectedEventHandler(context, e);
}

static BOOL pre_connect(freerdp* instance) {
    LOGI("pre_connect");
    RdpSession* session = s_from_ctx(instance->context);
    int rc = PubSub_SubscribeChannelConnected(instance->context->pubSub, rdpsync_OnChannelConnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("subscribe channel connected failed: 0x%08x", rc);
        if (session) s_diag(session, "rdpei=subscribe-connected-failed");
        return FALSE;
    }
    rc = PubSub_SubscribeChannelDisconnected(instance->context->pubSub, rdpsync_OnChannelDisconnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("subscribe channel disconnected failed: 0x%08x", rc);
        if (session) s_diag(session, "rdpei=subscribe-disconnected-failed");
        return FALSE;
    }
    if (!freerdp_client_load_channels(instance)) {
        DWORD err = GetLastError();
        LOGE("freerdp_client_load_channels failed: 0x%08lx", (unsigned long)err);
        if (session) s_diag(session, "rdpei=load-channels-failed-fallback");
        freerdp_settings_set_bool(instance->context->settings, FreeRDP_MultiTouchInput, FALSE);
        return TRUE;
    }
    if (session) s_diag(session, "rdpei=channels-loaded");
    LOGI("client channels loaded");
    return TRUE;
}

// ====== PostConnect callback (gdi_init happens HERE) ======
static BOOL post_connect(freerdp* instance) {
    LOGI("post_connect");
    rdpUpdate* update = instance->context->update;
    rdpSettings* settings = instance->context->settings;
    
    // Initialize GDI - this must be done inside PostConnect
    if(!gdi_init(instance, PIXEL_FORMAT_RGBX32)) {
        LOGE("gdi_init failed");
        return FALSE;
    }
    LOGI("gdi_init OK");
    
    // Register pointer handlers
    if(!register_pointer(instance->context->graphics)) {
        LOGE("register_pointer failed");
        return FALSE;
    }
    LOGI("pointer registered");
    
    // Set update callbacks
    update->BeginPaint = begin_paint;
    update->EndPaint = end_paint;
    update->DesktopResize = desktop_resize;
    
    LOGI("post_connect done");
    return TRUE;
}

// ====== PostDisconnect callback ======
static void post_disconnect(freerdp* instance) {
    LOGI("post_disconnect");
    gdi_free(instance);
}

// ====== Authentication callbacks ======
static BOOL authenticate_ex(freerdp* instance, char** username, char** password, char** domain, rdp_auth_reason reason) {
    LOGI("authenticate_ex: reason=%d", reason);
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

static DWORD verify_certificate_ex(freerdp* instance, const char* host, UINT16 port,
    const char* common_name, const char* subject, const char* issuer,
    const char* fingerprint, DWORD flags) {
    LOGI("verify_certificate_ex: ignoring (cert:ignore mode)");
    return 1; // Accept certificate
}

static DWORD verify_changed_certificate_ex(freerdp* instance, const char* host, UINT16 port,
    const char* common_name, const char* subject, const char* issuer,
    const char* new_fingerprint, const char* old_subject, const char* old_issuer,
    const char* old_fingerprint, DWORD flags) {
    LOGI("verify_changed_certificate_ex: accepting changed cert");
    return 1;
}

// ====== client_ctx_new / client_ctx_free ======
static BOOL client_ctx_new(freerdp* instance, rdpContext* context) {
    LOGI("client_ctx_new called");
    // Set ALL lifecycle callbacks (matching official bridge pattern)
    instance->PreConnect = pre_connect;
    instance->PostConnect = post_connect;
    instance->PostDisconnect = post_disconnect;
    instance->AuthenticateEx = authenticate_ex;
    instance->VerifyCertificateEx = verify_certificate_ex;
    instance->VerifyChangedCertificateEx = verify_changed_certificate_ex;
    return TRUE;
}

static void client_ctx_free(freerdp* instance, rdpContext* context) {
    LOGI("client_ctx_free called");
}

// ====== Instance creation ======
static freerdp* create_instance(void) {
    setenv("HOME", "/data/data/com.rdp.sync/files", 1);
    RDP_CLIENT_ENTRY_POINTS entry = {0};
    entry.Version = RDP_CLIENT_INTERFACE_VERSION;
    entry.Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
    entry.ContextSize = sizeof(rdpClientContext);
    entry.ClientNew = client_ctx_new;
    entry.ClientFree = client_ctx_free;

    rdpContext* ctx = freerdp_client_context_new(&entry);
    if (!ctx || !ctx->instance) {
        LOGE("freerdp_client_context_new failed");
        return NULL;
    }
    freerdp* inst = ctx->instance;
    LOGI("Instance created OK");
    return inst;
}

// ====== Connection thread (event loop) ======
static void* thread_fn(void* arg) {
    RdpSession* s=(RdpSession*)arg; freerdp* inst=s->instance;
    LOGI("Thread started");
    
    s_status(s,"正在连接..."); s_diag(s,"stage=connecting");
    
    // freerdp_connect internally calls PreConnect -> NEGO -> TLS -> NLA -> PostConnect
    if(!freerdp_connect(inst)){
        UINT32 e=freerdp_get_last_error(inst->context);
        const char* es=freerdp_get_last_error_string(e);
        LOGE("freerdp_connect failed: 0x%08x %s",e,es?es:"?");
        s_error(s,"FreeRDP 连接失败 [0x%08x]: %s",e,es?es:"?"); s_diag(s,"connect=FAILED");
        goto cleanup;
    }
    
    LOGI("freerdp_connect OK");
    s_status(s,"已连接"); s_diag(s,"connected=OK");
    
    // Event loop (same shape as FreeRDP Android's android_freerdp_run).
    while(!s->terminating){
        HANDLE handles[MAXIMUM_WAIT_OBJECTS] = {0};
        DWORD count = freerdp_get_event_handles(inst->context, handles, MAXIMUM_WAIT_OBJECTS);
        if(count == 0){
            LOGE("freerdp_get_event_handles failed");
            s_error(s,"连接中断"); break;
        }
        DWORD status = WaitForMultipleObjects(count, handles, FALSE, 100);
        if(status == WAIT_TIMEOUT) continue;
        if(status == WAIT_FAILED){
            LOGE("WaitForMultipleObjects failed: 0x%08lx", (unsigned long)GetLastError());
            s_error(s,"连接中断"); break;
        }
        if(!freerdp_check_event_handles(inst->context)){
            UINT32 e=freerdp_get_last_error(inst->context);
            const char* es=freerdp_get_last_error_string(e);
            LOGE("freerdp_check_event_handles failed: 0x%08x %s",e,es?es:"?");
            s_error(s,"连接中断"); break;
        }
        if(freerdp_shall_disconnect_context(inst->context)) break;
    }
    
    // Disconnect. Skip if aborting (session_destroy already called freerdp_abort_connect_context,
    // which tears down the connection internally). Doing a second disconnect on a freed context
    // would crash.
    if (!s->terminating) freerdp_disconnect(inst);
    
cleanup:
    s->terminating=0;
    pthread_mutex_lock(&s->mutex);s->thread_done=1;pthread_cond_broadcast(&s->cond);pthread_mutex_unlock(&s->mutex);
    LOGI("Thread exited"); return NULL;
}

static void session_destroy(RdpSession* s) {
    if(!s) return;
    s->terminating=1;
    if(s->instance && s->instance->context) freerdp_abort_connect_context(s->instance->context);
    pthread_cond_broadcast(&s->cond);
    if(s->thread && !s->thread_done){
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 2;
        pthread_mutex_lock(&s->mutex);
        while(!s->thread_done){
            if(pthread_cond_timedwait(&s->cond, &s->mutex, &ts) != 0) break;
        }
        pthread_mutex_unlock(&s->mutex);
        if(!s->thread_done){
            // Thread is truly stuck. Detach it and leak the session resources
            // to avoid a use-after-free crash. The thread will clean up its own
            // stack when it finally exits (inst is still owned by the thread).
            LOGI("thread still busy after 2s, detaching (session leaked to avoid crash)");
            pthread_detach(s->thread);
            return;  // DO NOT free s, fb_pixels, or context — thread may still use them
        }
    }
    // Thread completed normally, safe to free everything
    if(s->instance){
        freerdp_client_context_free(s->instance->context);
        s->instance = NULL;
    }
    pthread_mutex_destroy(&s->mutex);
    pthread_cond_destroy(&s->cond);
    free(s->fb_pixels);
    free(s);
}

// ====== JNI ======
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect3(JNIEnv*,jobject,jstring,jint,jstring,jstring,jstring,jstring,jint,jint,jboolean);
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect2(JNIEnv*,jobject,jstring,jint,jstring,jstring,jstring,jstring,jint,jint);

JNIEXPORT void JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSetLibDir(JNIEnv* e, jobject t, jstring jpath) {
    const char* path = (*e)->GetStringUTFChars(e,jpath,NULL);
    LOGI("nativeSetLibDir: %s", path);
    
    // Register MD4 with OpenSSL EVP via dlsym (OpenSSL 3.x legacy provider unavailable on Android)
    void* fr = dlopen("libfreerdp3.so", RTLD_NOW | RTLD_GLOBAL);
    if(fr) {
        typedef int (*m4i_t)(void*); typedef int (*m4u_t)(void*,const void*,size_t); typedef int (*m4f_t)(unsigned char*,void*);
        m4i_t m4i = (m4i_t)dlsym(fr, "MD4_Init"); m4u_t m4u = (m4u_t)dlsym(fr, "MD4_Update"); m4f_t m4f = (m4f_t)dlsym(fr, "MD4_Final");
        if(m4i && m4u && m4f) {
            EVP_MD* m = EVP_MD_meth_new(3, NID_md4);
            if(m) {
                EVP_MD_meth_set_init(m, (int(*)(EVP_MD_CTX*))m4i);
                EVP_MD_meth_set_update(m, (int(*)(EVP_MD_CTX*,const void*,size_t))m4u);
                EVP_MD_meth_set_final(m, (int(*)(EVP_MD_CTX*,unsigned char*))m4f);
                EVP_MD_meth_set_input_blocksize(m, 64);
                EVP_MD_meth_set_result_size(m, 16);
                if(EVP_add_digest(m)) LOGI("MD4 registered via EVP_add_digest");
                else LOGE("EVP_add_digest failed");
            }
        } else LOGE("dlsym MD4 functions failed");
    }
    (*e)->ReleaseStringUTFChars(e,jpath,path);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect(
    JNIEnv* e,jobject t,jstring h,jint p,jstring u,jstring pw,jstring d,jint w,jint hh){
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect2(e,t,h,p,u,pw,d,NULL,w,hh);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect2(
    JNIEnv* e,jobject t,jstring jh,jint jp,jstring ju,jstring jpw,jstring jd,jstring jrn,jint jw,jint jh2){
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect3(e,t,jh,jp,ju,jpw,jd,jrn,jw,jh2,JNI_TRUE);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect3(
    JNIEnv* e,jobject t,jstring jh,jint jp,jstring ju,jstring jpw,jstring jd,jstring jrn,jint jw,jint jh2,jboolean enableTouchInput){
    const char* host=(*e)->GetStringUTFChars(e,jh,NULL);int port=(int)jp;
    const char* user=(*e)->GetStringUTFChars(e,ju,NULL);const char* pass=(*e)->GetStringUTFChars(e,jpw,NULL);
    const char* domain=(*e)->GetStringUTFChars(e,jd,NULL);
    LOGI("connect host=%s port=%d user=%s rdpei=%s",host,port,user,enableTouchInput?"on":"off");
    
    freerdp* inst = create_instance();
    if(!inst){snprintf(g_startup_error,sizeof(g_startup_error),"create_instance failed");goto fail;}
    g_startup_error[0]=0;
    
    rdpSettings* s=inst->context->settings;
    freerdp_settings_set_string(s,FreeRDP_ServerHostname,host);
    freerdp_settings_set_uint32(s,FreeRDP_ServerPort,(UINT32)port);
    freerdp_settings_set_string(s,FreeRDP_Username,user);
    freerdp_settings_set_string(s,FreeRDP_Password,pass);
    if(domain&&strlen(domain)>0)freerdp_settings_set_string(s,FreeRDP_Domain,domain);
    freerdp_settings_set_bool(s,FreeRDP_AutoLogonEnabled,TRUE);
    freerdp_settings_set_uint32(s,FreeRDP_DesktopWidth,(UINT32)(jw>0?jw:1280));
    freerdp_settings_set_uint32(s,FreeRDP_DesktopHeight,(UINT32)(jh2>0?jh2:720));
    freerdp_settings_set_uint16(s,FreeRDP_DesktopOrientation,(jw>jh2)?ORIENTATION_LANDSCAPE:ORIENTATION_PORTRAIT);
    freerdp_settings_set_uint32(s,FreeRDP_DesktopPhysicalWidth,(UINT32)(jw>0?jw:1280));
    freerdp_settings_set_uint32(s,FreeRDP_DesktopPhysicalHeight,(UINT32)(jh2>0?jh2:720));
    freerdp_settings_set_bool(s,FreeRDP_SupportMonitorLayoutPdu,FALSE);
    freerdp_settings_set_uint32(s,FreeRDP_DesktopScaleFactor,100);
    freerdp_settings_set_uint32(s,FreeRDP_DeviceScaleFactor,100);
    freerdp_settings_set_bool(s,FreeRDP_SupportDisplayControl,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_DynamicResolutionUpdate,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_MultiTouchInput,enableTouchInput?TRUE:FALSE);
    freerdp_settings_set_bool(s,FreeRDP_NetworkAutoDetect,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_SupportHeartbeatPdu,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_SupportMultitransport,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_DeviceRedirection,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_RedirectDrives,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_RedirectSmartCards,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_RedirectPrinters,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_RedirectSerialPorts,FALSE);
    freerdp_settings_set_bool(s,FreeRDP_RedirectParallelPorts,FALSE);
    freerdp_settings_set_uint32(s,FreeRDP_ColorDepth,32);
    freerdp_settings_set_uint32(s,FreeRDP_RequestedProtocols,0x00000001|0x00000002);
    freerdp_settings_set_bool(s,FreeRDP_IgnoreCertificate,TRUE);
    freerdp_settings_set_bool(s,FreeRDP_AudioPlayback,FALSE);
    
    RdpSession* session=(RdpSession*)calloc(1,sizeof(RdpSession));
    if(!session){snprintf(g_startup_error,sizeof(g_startup_error),"内存不足");freerdp_context_free(inst);freerdp_free(inst);goto fail;}
    pthread_mutex_init(&session->mutex,NULL);pthread_cond_init(&session->cond,NULL);
    session->instance=inst;
    snprintf(session->status,sizeof(session->status),"初始化");
    snprintf(session->diag,sizeof(session->diag),"engine=FreeRDP %s\nhost=%s\nport=%d\nuser=%s\n",FREERDP_VERSION_FULL,host,port,user);
    s_diag(session,enableTouchInput?"rdpei=requested":"rdpei=disabled");
    pthread_mutex_lock(&g_lock);if(g_session){session_destroy(g_session);g_session=NULL;}g_session=session;pthread_mutex_unlock(&g_lock);
    
    if(pthread_create(&session->thread,NULL,thread_fn,session)!=0){
        snprintf(g_startup_error,sizeof(g_startup_error),"线程创建失败");
        pthread_mutex_lock(&g_lock);g_session=NULL;pthread_mutex_unlock(&g_lock);
        free(session->fb_pixels);pthread_mutex_destroy(&session->mutex);pthread_cond_destroy(&session->cond);free(session);
        freerdp_context_free(inst);freerdp_free(inst);goto fail;
    }
    g_startup_error[0]=0;
    (*e)->ReleaseStringUTFChars(e,jh,host);(*e)->ReleaseStringUTFChars(e,ju,user);(*e)->ReleaseStringUTFChars(e,jpw,pass);(*e)->ReleaseStringUTFChars(e,jd,domain);
    LOGI("nativeConnect2 returning 1");return 1;
fail:
    (*e)->ReleaseStringUTFChars(e,jh,host);(*e)->ReleaseStringUTFChars(e,ju,user);(*e)->ReleaseStringUTFChars(e,jpw,pass);(*e)->ReleaseStringUTFChars(e,jd,domain);
    LOGI("nativeConnect2 returning 0: %s",g_startup_error);return 0;
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeDisconnect(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);if(g_session){session_destroy(g_session);g_session=NULL;}pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jboolean JNICALL Java_com_rdp_sync_network_RdpConnector_nativeIsConnected(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);int c=g_session?g_session->connected:0;pthread_mutex_unlock(&g_lock);return c?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jstring JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetStatus(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);const char* text="未连接";
    if(strlen(g_startup_error)>0)text=g_startup_error;
    else if(g_session){pthread_mutex_lock(&g_session->mutex);text=strlen(g_session->last_error)>0?g_session->last_error:g_session->status;pthread_mutex_unlock(&g_session->mutex);}
    jstring ret=(*e)->NewStringUTF(e,text);pthread_mutex_unlock(&g_lock);return ret;}
JNIEXPORT jstring JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetDiag(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);const char* text="";if(g_session){pthread_mutex_lock(&g_session->mutex);text=g_session->diag;pthread_mutex_unlock(&g_session->mutex);}
    jstring ret=(*e)->NewStringUTF(e,text);pthread_mutex_unlock(&g_lock);return ret;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetWidth(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);int w=g_session?g_session->fb_width:1280;pthread_mutex_unlock(&g_lock);return w;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetHeight(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);int h=g_session?g_session->fb_height:720;pthread_mutex_unlock(&g_lock);return h;}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetFrameId(JNIEnv* e,jobject t){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;int64_t id=0;
    if(s){pthread_mutex_lock(&s->mutex);id=s->frame_id;pthread_mutex_unlock(&s->mutex);}
    pthread_mutex_unlock(&g_lock);return (jlong)id;}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameArgb(JNIEnv* e,jobject t,jintArray buffer){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->fb_pixels||s->fb_size==0||!buffer){pthread_mutex_unlock(&g_lock);return 0;}
    jsize len=(*e)->GetArrayLength(e,buffer);
    if(len<s->fb_size){pthread_mutex_unlock(&g_lock);return 0;}
    pthread_mutex_lock(&s->mutex);
    int size=s->fb_size;int64_t id=s->frame_id;
    (*e)->SetIntArrayRegion(e,buffer,0,size,s->fb_pixels);
    pthread_mutex_unlock(&s->mutex);
    pthread_mutex_unlock(&g_lock);return (jlong)id;}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameDirtyArgb(JNIEnv* e,jobject t,jintArray buffer,jintArray dirty){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->fb_pixels||s->fb_size==0||!buffer||!dirty){pthread_mutex_unlock(&g_lock);return 0;}
    jsize len=(*e)->GetArrayLength(e,buffer);
    jsize dirtyLen=(*e)->GetArrayLength(e,dirty);
    if(len<s->fb_size||dirtyLen<4){pthread_mutex_unlock(&g_lock);return 0;}
    pthread_mutex_lock(&s->mutex);
    int w=s->fb_width,h=s->fb_height;
    int dx=s->dirty_x,dy=s->dirty_y,dw=s->dirty_w,dh=s->dirty_h;
    int64_t id=s->frame_id;
    normalize_dirty_rect(w,h,&dx,&dy,&dw,&dh);
    for(int yy=0;yy<dh;yy++){
        int offset=(dy+yy)*w+dx;
        (*e)->SetIntArrayRegion(e,buffer,offset,dw,s->fb_pixels+offset);
    }
    jint rect[4]={(jint)dx,(jint)dy,(jint)dw,(jint)dh};
    (*e)->SetIntArrayRegion(e,dirty,0,4,rect);
    pthread_mutex_unlock(&s->mutex);
    pthread_mutex_unlock(&g_lock);return (jlong)id;}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(JNIEnv* e,jobject t,jobject bitmap,jintArray dirty,jboolean forceFull){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->fb_pixels||s->fb_size==0||!bitmap||!dirty){pthread_mutex_unlock(&g_lock);return 0;}
    jsize dirtyLen=(*e)->GetArrayLength(e,dirty);
    if(dirtyLen<4){pthread_mutex_unlock(&g_lock);return 0;}
    AndroidBitmapInfo info;
    if(AndroidBitmap_getInfo(e,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS){
        pthread_mutex_unlock(&g_lock);return 0;
    }
    pthread_mutex_lock(&s->mutex);
    int w=s->fb_width,h=s->fb_height;
    if((int)info.width!=w||(int)info.height!=h||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888){
        pthread_mutex_unlock(&s->mutex);pthread_mutex_unlock(&g_lock);return 0;
    }
    int dx=forceFull?0:s->dirty_x;
    int dy=forceFull?0:s->dirty_y;
    int dw=forceFull?w:s->dirty_w;
    int dh=forceFull?h:s->dirty_h;
    int64_t id=s->frame_id;
    normalize_dirty_rect(w,h,&dx,&dy,&dw,&dh);
    void* pixels=NULL;
    if(AndroidBitmap_lockPixels(e,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS){
        pthread_mutex_unlock(&s->mutex);pthread_mutex_unlock(&g_lock);return 0;
    }
    for(int yy=0;yy<dh;yy++){
        int offset=(dy+yy)*w+dx;
        uint32_t* dst=(uint32_t*)((uint8_t*)pixels+(size_t)(dy+yy)*info.stride+(size_t)dx*4U);
        const uint32_t* src=(const uint32_t*)(s->fb_pixels+offset);
        for(int xx=0;xx<dw;xx++){
            uint32_t argb=src[xx];
            dst[xx]=(argb&0xFF00FF00U)|((argb&0x00FF0000U)>>16)|((argb&0x000000FFU)<<16);
        }
    }
    AndroidBitmap_unlockPixels(e,bitmap);
    jint rect[4]={(jint)dx,(jint)dy,(jint)dw,(jint)dh};
    (*e)->SetIntArrayRegion(e,dirty,0,4,rect);
    pthread_mutex_unlock(&s->mutex);
    pthread_mutex_unlock(&g_lock);return (jlong)id;}
JNIEXPORT jintArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetFrameArgb(JNIEnv* e,jobject t,jint rw,jint rh){
    pthread_mutex_lock(&g_lock);if(!g_session||!g_session->fb_pixels||g_session->fb_size==0){pthread_mutex_unlock(&g_lock);return NULL;}
    jintArray ret=(*e)->NewIntArray(e,g_session->fb_size);
    if(ret){pthread_mutex_lock(&g_session->mutex);(*e)->SetIntArrayRegion(e,ret,0,g_session->fb_size,g_session->fb_pixels);pthread_mutex_unlock(&g_session->mutex);}
    pthread_mutex_unlock(&g_lock);return ret;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendPointerEvent(JNIEnv* e,jobject t,jint x,jint y,jint btn){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context){pthread_mutex_unlock(&g_lock);return -1;}
    rdpInput* in=s->instance->context->input;UINT16 f=PTR_FLAGS_MOVE;
    if(btn==1)f=PTR_FLAGS_BUTTON1|PTR_FLAGS_DOWN;else if(btn==2)f=PTR_FLAGS_BUTTON2|PTR_FLAGS_DOWN;else if(btn==3)f=PTR_FLAGS_BUTTON3|PTR_FLAGS_DOWN;
    in->MouseEvent(in,f,(UINT16)x,(UINT16)y);
    if(btn!=0){UINT16 r=(btn==1)?PTR_FLAGS_BUTTON1:(btn==2)?PTR_FLAGS_BUTTON2:PTR_FLAGS_BUTTON3;in->MouseEvent(in,r,(UINT16)x,(UINT16)y);}
    pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendWheelEvent(JNIEnv* e,jobject t,jint x,jint y,jint delta){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context){pthread_mutex_unlock(&g_lock);return -1;}
    if(delta==0){pthread_mutex_unlock(&g_lock);return 0;}
    UINT16 amount=(UINT16)(abs(delta)>0xFF?0xFF:abs(delta));
    UINT16 flags=(delta<0)
        ? (PTR_FLAGS_WHEEL|PTR_FLAGS_WHEEL_NEGATIVE|((0x100-amount)&0xFF))
        : (PTR_FLAGS_WHEEL|amount);
    s->instance->context->input->MouseEvent(s->instance->context->input,flags,(UINT16)x,(UINT16)y);
    pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendHWheelEvent(JNIEnv* e,jobject t,jint x,jint y,jint delta){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context){pthread_mutex_unlock(&g_lock);return -1;}
    if(delta==0){pthread_mutex_unlock(&g_lock);return 0;}
    UINT16 amount=(UINT16)(abs(delta)>0xFF?0xFF:abs(delta));
    UINT16 flags=(delta<0)
        ? (PTR_FLAGS_HWHEEL|PTR_FLAGS_WHEEL_NEGATIVE|((0x100-amount)&0xFF))
        : (PTR_FLAGS_HWHEEL|amount);
    s->instance->context->input->MouseEvent(s->instance->context->input,flags,(UINT16)x,(UINT16)y);
    pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendTouchEvent(JNIEnv* e,jobject t,jint pointerId,jint eventType,jint x,jint y){
    (void)e;(void)t;
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context){pthread_mutex_unlock(&g_lock);return -1;}
    rdpClientContext* cctx=(rdpClientContext*)s->instance->context;
    if(!cctx->rdpei){pthread_mutex_unlock(&g_lock);return -2;}
    UINT32 flags=0;
    switch(eventType){
        case TOUCH_EVENT_DOWN: flags=FREERDP_TOUCH_DOWN; break;
        case TOUCH_EVENT_MOVE: flags=FREERDP_TOUCH_MOTION; break;
        case TOUCH_EVENT_UP: flags=FREERDP_TOUCH_UP; break;
        case TOUCH_EVENT_CANCEL: flags=FREERDP_TOUCH_CANCEL; break;
        default: pthread_mutex_unlock(&g_lock);return -4;
    }
    BOOL ok=freerdp_client_handle_touch(cctx,flags,(INT32)pointerId,0,(INT32)x,(INT32)y);
    pthread_mutex_unlock(&g_lock);return ok?0:-3;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendKeyEvent(JNIEnv* e,jobject t,jint code,jint down){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context){pthread_mutex_unlock(&g_lock);return -1;}
    s->instance->context->input->KeyboardEvent(s->instance->context->input,(UINT16)(down?0:KBD_FLAGS_RELEASE),(UINT16)code);
    pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendUnicodeChar(JNIEnv* e,jobject t,jint code){
    pthread_mutex_lock(&g_lock);RdpSession* s=g_session;
    if(!s||!s->instance||!s->instance->context||!s->instance->context->input){pthread_mutex_unlock(&g_lock);return -1;}
    UINT16 c=(UINT16)(code&0xFFFF);
    rdpInput* in=s->instance->context->input;
    in->UnicodeKeyboardEvent(in,0,c);
    in->UnicodeKeyboardEvent(in,KBD_FLAGS_RELEASE,c);
    pthread_mutex_unlock(&g_lock);return 0;}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendClipboardText(JNIEnv* e,jobject t,jstring text){return 0;}
