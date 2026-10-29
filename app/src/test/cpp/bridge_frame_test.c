/* Executes the production bridge's frame, telemetry and input paths on host.
 * Android bitmap/JNI surfaces alone are mocks; all FreeRDP structures use the
 * pinned upstream headers. No network endpoints or credentials are involved. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include "../../main/cpp/freerdp_bridge.c"

typedef struct { AndroidBitmapInfo info; uint8_t* bytes; int fail_unlock; } TestBitmap;
typedef struct { jsize length; jint values[4]; } TestArray;
static jsize JNICALL test_length(JNIEnv* env, jarray array) { (void)env; return ((TestArray*)array)->length; }
static void JNICALL test_int_region(JNIEnv* env, jintArray array, jsize start, jsize len, const jint* values) {
    (void)env; assert(start + len <= 4); memcpy(((TestArray*)array)->values + start, values, (size_t)len * sizeof(jint));
}
static jboolean JNICALL test_exception(JNIEnv* env) { (void)env; return JNI_FALSE; }
static const struct JNINativeInterface_ jni_functions = {
    .GetArrayLength = test_length, .SetIntArrayRegion = test_int_region, .ExceptionCheck = test_exception
};
int AndroidBitmap_getInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info) { (void)env; *info = ((TestBitmap*)bitmap)->info; return 0; }
int AndroidBitmap_lockPixels(JNIEnv* env, jobject bitmap, void** bytes) { (void)env; *bytes = ((TestBitmap*)bitmap)->bytes; return 0; }
int AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap) { (void)env; return ((TestBitmap*)bitmap)->fail_unlock ? -1 : 0; }
BOOL SetEvent(HANDLE event) { (void)event; return TRUE; }
BOOL ResetEvent(HANDLE event) { (void)event; return TRUE; }
static size_t channel_flushes;
BOOL freerdp_channels_check_fds(rdpChannels* channels, freerdp* instance) {
    (void)channels; (void)instance; ++channel_flushes; return TRUE;
}
static UINT16 last_mouse_flags;
static size_t mouse_events;
static BOOL mouse_event(rdpInput* input, UINT16 flags, UINT16 x, UINT16 y) {
    (void)input; (void)x; (void)y; last_mouse_flags = flags; ++mouse_events; return TRUE;
}
static UINT32 last_touch_flags;
static size_t touch_events;
static UINT touch_raw_event(RdpeiClientContext* context, INT32 external_id, INT32 x, INT32 y,
                            INT32* contact_id, UINT32 flags, UINT32 fields, ...) {
    (void)context; (void)external_id; (void)x; (void)y; (void)contact_id; (void)fields;
    last_touch_flags = flags; ++touch_events; return CHANNEL_RC_OK;
}
static int test_width = 200, test_height = 300;
UINT32 freerdp_settings_get_uint32(const rdpSettings* settings, FreeRDP_Settings_Keys_UInt32 key) {
    (void)settings; return key == FreeRDP_DesktopWidth ? test_width : test_height;
}
static UINT16 seen_flags[8], seen_codes[8];
static size_t key_events;
static BOOL unicode_event(rdpInput* input, UINT16 flags, UINT16 code) {
    (void)input; seen_flags[key_events] = flags; seen_codes[key_events++] = code; return TRUE;
}
static BOOL keyboard_event(rdpInput* input, UINT16 flags, UINT8 code) { return unicode_event(input, flags, code); }
static void set_source_pixel(rdpGdi* gdi, int x, int y, uint32_t color) {
    memcpy(gdi->primary_buffer + (size_t)y * gdi->stride + (size_t)x * 4, &color, 4);
}
static uint32_t bitmap_pixel(TestBitmap* bitmap, int x, int y) {
    uint32_t pixel; memcpy(&pixel, bitmap->bytes + (size_t)y * bitmap->info.stride + (size_t)x * 4, 4); return pixel;
}
static void damage(GDI_RGN* invalid, int x, int y, int width, int height) {
    invalid->x = x; invalid->y = y; invalid->w = width; invalid->h = height; invalid->null = FALSE;
}
typedef struct { RdpSyncContext* context; GDI_RGN* invalid; atomic_int done; } Producer;
static void* produce_frames(void* arg) {
    Producer* producer = arg;
    rdpGdi* gdi = producer->context->common.context.gdi;
    for (int n = 0; n < 1000; ++n) {
        const int x = n % gdi->width, y = (n / gdi->width) % gdi->height;
        set_source_pixel(gdi, x, y, 0xff000000U | (uint32_t)n);
        damage(producer->invalid, x, y, 1, 1);
        assert(end_paint(&producer->context->common.context));
    }
    atomic_store(&producer->done, 1);
    return NULL;
}
int main(void) {
    RdpSession session = {0};
    pthread_mutex_init(&session.mutex, NULL); pthread_mutex_init(&session.channel_mutex, NULL);
    atomic_init(&session.connected, 1); atomic_init(&session.terminating, 0);
    RdpSyncContext context = {0}; freerdp instance = {0}; rdpInput input = {0};
    context.session = &session; context.common.context.instance = &instance;
    context.common.context.input = &input; instance.context = &context.common.context; session.instance = &instance;
    rdpGdi gdi = {0}; GDI_DC base_dc = {0}, primary_dc = {0}; gdiBitmap primary = {0};
    GDI_WND window = {0}; GDI_RGN invalid = {0};
    gdi.hdc = &base_dc; /* Deliberately NO hwnd here: only primary owns damage. */
    gdi.primary = &primary; primary.hdc = &primary_dc; primary_dc.hwnd = &window; window.invalid = &invalid;
    context.common.context.gdi = &gdi;
    gdi.width = test_width; gdi.height = test_height; gdi.stride = 4 * test_width + 64;
    gdi.primary_buffer = calloc((size_t)gdi.stride, 400);
    assert(gdi.primary_buffer);
    for (int y = 0; y < gdi.height; ++y) for (int x = 0; x < gdi.width; ++x) set_source_pixel(&gdi, x, y, 0x000000ff);
    set_source_pixel(&gdi, 0, 0, 0x00000000); /* RGB black, unused zero alpha */
    set_source_pixel(&gdi, 1, 0, 0x00332211); /* desktop fixture color #112233 */
    assert(end_paint(&context.common.context));
    assert(session.frame_id == 1 && session.dirty.width == test_width && session.dirty.height == test_height);
    TestBitmap bitmap = {{test_width, test_height, 4 * test_width + 32, ANDROID_BITMAP_FORMAT_RGBA_8888, 0}, NULL, 0};
    bitmap.bytes = calloc(bitmap.info.stride, 400); assert(bitmap.bytes);
    TestArray bounds = {4, {0}};
    JNIEnv env = &jni_functions; g_session = &session;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_TRUE) == 1);
    assert(bitmap_pixel(&bitmap, 17, 299) == 0xff0000ff); /* padded stride, forced opaque */
    assert(bitmap_pixel(&bitmap, 0, 0) == 0xff000000);
    assert(bitmap_pixel(&bitmap, 1, 0) == 0xff332211); /* no background contribution */
    assert(session.dirty.width == 0);
    damage(&invalid, 2, 3, 1, 1); set_source_pixel(&gdi, 2, 3, 0x0000ff00);
    assert(end_paint(&context.common.context)); assert(invalid.null);
    damage(&invalid, 190, 290, 1, 1); set_source_pixel(&gdi, 190, 290, 0x40ff0000);
    assert(end_paint(&context.common.context));
    assert(session.frame_id == 3);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE) == 3);
    assert(bounds.values[0] == 2 && bounds.values[1] == 3 && bounds.values[2] == 189 && bounds.values[3] == 288);
    assert(bitmap_pixel(&bitmap, 2, 3) == 0xff00ff00 && bitmap_pixel(&bitmap, 190, 290) == 0xffff0000);
    assert(end_paint(&context.common.context)); assert(session.frame_id == 3); /* no dirty = no copy */
    /* Same-area portrait -> landscape must invalidate ALL rows despite same allocation size. */
    gdi.width = 300; gdi.height = 200; gdi.stride = 4 * 300 + 64;
    for (int y = 0; y < gdi.height; ++y) for (int x = 0; x < gdi.width; ++x) set_source_pixel(&gdi, x, y, 0x00c0ffee);
    assert(end_paint(&context.common.context)); assert(session.frame_id == 4 && session.dirty.width == 300 && session.dirty.height == 200);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE) == 0);
    assert(session.dirty.width == 300); /* stale Java bitmap must not consume damage */
    bitmap.info.width = 300; bitmap.info.height = 200; bitmap.info.stride = 4 * 300 + 32;
    bitmap.fail_unlock = 1;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE) == 0);
    assert(session.dirty.width == 300);
    bitmap.fail_unlock = 0;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE) == 4);
    assert(bitmap_pixel(&bitmap, 299, 199) == 0xffc0ffee);
    /* Producer outruns presentation: retained damage remains coherent under contention. */
    Producer producer = {&context, &invalid, ATOMIC_VAR_INIT(0)};
    pthread_t producer_thread;
    assert(pthread_create(&producer_thread, NULL, produce_frames, &producer) == 0);
    do {
        Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE);
    } while (!atomic_load(&producer.done));
    pthread_join(producer_thread, NULL);
    Java_com_rdp_sync_network_RdpConnector_nativeCopyFrameToBitmap(&env, NULL, (jobject)&bitmap, (jintArray)&bounds, JNI_FALSE);
    assert(session.frame_id == 1004);
    for (int n = 0; n < 1000; ++n)
        assert(bitmap_pixel(&bitmap, n % gdi.width, (n / gdi.width) % gdi.height) == (0xff000000U | (uint32_t)n));
    /* Ordered input edges, coalesced moves, unsupported touch, extended keys. */
    assert(enqueue_input((InputCommand){INPUT_TOUCH, 1, 2, 1, 0}) == -2);
    session.touch_available = 1;
    assert(enqueue_input((InputCommand){INPUT_TOUCH, 1, 2, 1, 0}) == 0);
    assert(enqueue_input((InputCommand){INPUT_TOUCH, 3, 4, 1, 1}) == 0);
    assert(enqueue_input((InputCommand){INPUT_TOUCH, 5, 6, 1, 1}) == 0);
    assert(enqueue_input((InputCommand){INPUT_TOUCH, 5, 6, 1, 2}) == 0);
    assert(session.command_count == 3 && session.commands[1].x == 5 && session.commands[2].action == 2);
    input.KeyboardEvent = keyboard_event; input.UnicodeKeyboardEvent = unicode_event;
    assert(execute_input(&session, (InputCommand){INPUT_KEY, 0, 0, 0xe053, 1}));
    assert(execute_input(&session, (InputCommand){INPUT_KEY, 0, 0, 0xe053, 0}));
    assert(seen_flags[0] == KBD_FLAGS_EXTENDED && seen_flags[1] == (KBD_FLAGS_EXTENDED | KBD_FLAGS_RELEASE));
    assert(seen_codes[0] == 0x53 && seen_codes[1] == 0x53);
    assert(execute_input(&session, (InputCommand){INPUT_UNICODE, 0, 0, 'A', 0}));
    assert(key_events == 4 && seen_flags[3] == KBD_FLAGS_RELEASE);
    /* Owner-thread cleanup must release what reached the wire even if queued UP
     * packets are discarded by a rapid Close immediately after DOWN. */
    input.MouseEvent = mouse_event;
    RdpeiClientContext rdpei = {0}; rdpei.TouchRawEvent = touch_raw_event; context.common.rdpei = &rdpei;
    assert(execute_input(&session, (InputCommand){INPUT_KEY, 0, 0, 0xe01d, 1}));
    assert(execute_input(&session, (InputCommand){INPUT_BUTTON, 100, 50, 1, 1}));
    assert(execute_input(&session, (InputCommand){INPUT_TOUCH, 100, 50, 1, 0}));
    assert(session.held_keys[1][0x1d] && session.held_buttons[0] && session.held_touches[0]);
    release_remote_inputs(&session);
    assert(!session.held_keys[1][0x1d] && !session.held_buttons[0] && !session.held_touches[0]);
    assert(seen_flags[5] == (KBD_FLAGS_EXTENDED | KBD_FLAGS_RELEASE) && seen_codes[5] == 0x1d);
    assert(mouse_events == 2 && last_mouse_flags == PTR_FLAGS_BUTTON1);
    assert(touch_events == 2 && last_touch_flags == (RDPINPUT_CONTACT_FLAG_UP | RDPINPUT_CONTACT_FLAG_CANCELED));
    assert(channel_flushes == 1);
    rdpAutoDetect autodetect = {0}; autodetect.context = &context.common.context;
    rdpNetworkCharacteristicsResult network = {RDP_NETCHAR_RESULT_TYPE_BW_AVG_RTT, 0, 80, 800};
    assert(network_characteristics(&autodetect, RDP_TRANSPORT_TCP, 1, &network)); assert(session.bandwidth_kbps == 800);
    network.type = RDP_NETCHAR_RESULT_TYPE_BASE_RTT_AVG_RTT;
    assert(network_characteristics(&autodetect, RDP_TRANSPORT_TCP, 2, &network)); assert(session.bandwidth_kbps == -1);
    DispClientContext display = {0}; display.custom = &session;
    assert(display_caps(&display, UINT32_MAX, UINT32_MAX, UINT32_MAX) == 0);
    assert(session.max_monitor_area == RDPSYNC_MAX_PIXELS);
    session.command_count = INPUT_CAPACITY;
    const int64_t overflow_started = monotonic_us();
    assert(enqueue_input((InputCommand){INPUT_UNICODE, 0, 0, 'A', 0}) == -5);
    assert(!atomic_load(&session.terminating));
    assert(monotonic_us() - overflow_started < 50000); /* no250ms global-lock stall */
    assert(Java_com_rdp_sync_network_RdpConnector_nativeGetFrameId(NULL, NULL) == 1004);
    assert(enqueue_input((InputCommand){INPUT_KEY, 0, 0, 0x1c, 0}) == -5);
    assert(atomic_load(&session.terminating) && session.input_drops == 2);
    g_session = NULL;
    free(gdi.primary_buffer); free(session.fb_pixels); free(bitmap.bytes);
    pthread_mutex_destroy(&session.mutex); pthread_mutex_destroy(&session.channel_mutex);
    puts("native bridge: passed real dirty-frame copy/union/stride, resize race, input ordering, telemetry freshness");
}
