/* Actual production JNI runtime bootstrap, using disposable owned directories.
 * This tests environment setup, NOT a mocked FreeRDP context allocation. The
 * Android integration fixture separately proves allocation/connect on device. */
#define _GNU_SOURCE
#include <assert.h>
#include "../../main/cpp/freerdp_bridge.c"

static const char* JNICALL test_utf_chars(JNIEnv* env, jstring text, jboolean* copy) {
    (void)env; (void)copy; return (const char*)text;
}
static void JNICALL test_release_utf(JNIEnv* env, jstring text, const char* chars) {
    (void)env; assert((const char*)text == chars);
}
static const struct JNINativeInterface_ runtime_jni = {
    .GetStringUTFChars = test_utf_chars, .ReleaseStringUTFChars = test_release_utf
};
static BOOL bool_options[8192];
static UINT32 uint_options[8192];
BOOL freerdp_settings_set_bool(rdpSettings* settings, FreeRDP_Settings_Keys_Bool key, BOOL value) {
    (void)settings; assert(key < 8192); bool_options[key] = value; return TRUE;
}
BOOL freerdp_settings_set_uint32(rdpSettings* settings, FreeRDP_Settings_Keys_UInt32 key, UINT32 value) {
    (void)settings; assert(key < 8192); uint_options[key] = value; return TRUE;
}
BOOL freerdp_settings_set_uint16(rdpSettings* settings, FreeRDP_Settings_Keys_UInt16 key, UINT16 value) {
    (void)settings; assert(key < 8192); uint_options[key] = value; return TRUE;
}
BOOL freerdp_settings_get_bool(const rdpSettings* settings, FreeRDP_Settings_Keys_Bool key) {
    (void)settings; assert(key < 8192); return bool_options[key];
}
UINT32 freerdp_settings_get_uint32(const rdpSettings* settings, FreeRDP_Settings_Keys_UInt32 key) {
    (void)settings; assert(key < 8192); return uint_options[key];
}
static int loader_mutation;
BOOL freerdp_client_load_channels(freerdp* instance) {
    (void)instance;
    if (loader_mutation == 1) bool_options[FreeRDP_AudioPlayback] = TRUE;
    if (loader_mutation == 2) bool_options[FreeRDP_AudioCapture] = TRUE;
    if (loader_mutation == 3) uint_options[FreeRDP_DeviceCount] = 1;
    return loader_mutation != 4;
}
int main(void) {
    assert(configure_session_profile(NULL, 1280, 720, TRUE, FALSE));
    assert(bool_options[FreeRDP_NetworkAutoDetect] && bool_options[FreeRDP_DeviceRedirection]);
    assert(bool_options[FreeRDP_SupportDisplayControl] && bool_options[FreeRDP_MultiTouchInput]);
    assert(bool_options[FreeRDP_SupportDynamicChannels]);
    assert(!bool_options[FreeRDP_AudioCapture] && !bool_options[FreeRDP_AudioPlayback]);
    assert(!bool_options[FreeRDP_RedirectDrives] && !bool_options[FreeRDP_RedirectHomeDrive]);
    assert(!bool_options[FreeRDP_RedirectClipboard] && !bool_options[FreeRDP_SupportGraphicsPipeline]);
    assert(uint_options[FreeRDP_DesktopWidth] == 1280 && uint_options[FreeRDP_DesktopHeight] == 720);
    assert(configure_session_profile(NULL, 1280, 720, TRUE, TRUE));
    assert(!bool_options[FreeRDP_NetworkAutoDetect] && !bool_options[FreeRDP_DeviceRedirection]);
    assert(!bool_options[FreeRDP_SupportDisplayControl] && !bool_options[FreeRDP_MultiTouchInput]);
    assert(!bool_options[FreeRDP_SupportDynamicChannels]);
    RdpSyncContext profile_context = {0}; freerdp profile_instance = {0};
    profile_instance.context = &profile_context.common.context;
    assert(load_session_channels(&profile_instance));
    for (loader_mutation = 1; loader_mutation <= 4; ++loader_mutation) {
        assert(configure_session_profile(NULL, 1280, 720, TRUE, FALSE));
        uint_options[FreeRDP_DeviceCount] = 0;
        assert(!load_session_channels(&profile_instance));
    }
    loader_mutation = 0;

    char root[] = "/tmp/rdpsync-runtime-test-XXXXXX";
    assert(mkdtemp(root));
    char home[PATH_MAX], cache[PATH_MAX], other[PATH_MAX], missing[PATH_MAX], file[PATH_MAX];
    snprintf(home, sizeof(home), "%s/private-home", root);
    snprintf(cache, sizeof(cache), "%s/private-cache", root);
    snprintf(other, sizeof(other), "%s/private-other", root);
    snprintf(missing, sizeof(missing), "%s/missing", root);
    snprintf(file, sizeof(file), "%s/not-a-directory", root);
    assert(mkdir(home, 0700) == 0 && mkdir(cache, 0700) == 0 && mkdir(other, 0700) == 0);
    FILE* regular = fopen(file, "w"); assert(regular); fclose(regular);
    JNIEnv env = &runtime_jni;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeInitialize(&env, NULL, NULL, (jstring)cache) == JNI_FALSE);
    assert(!initialize_runtime("relative", cache));
    assert(!initialize_runtime(missing, cache));
    assert(!initialize_runtime(file, cache));
    assert(chmod(home, 0777) == 0);
    assert(!initialize_runtime(home, cache));
    assert(chmod(home, 0700) == 0);
    assert(!g_runtime_home[0]);
    RdpSession session = {0};
    g_session = &session;
    assert(!initialize_runtime(home, cache)); /* Never initialize under a session. */
    g_session = NULL;
    assert(unsetenv("HOME") == 0 && getenv("HOME") == NULL);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeInitialize(&env, NULL, (jstring)home, (jstring)cache) == JNI_TRUE);
    assert(!strcmp(getenv("HOME"), home));
    assert(!strcmp(getenv("TMPDIR"), cache));
    assert(!strcmp(getenv("XDG_CONFIG_HOME"), home));
    assert(!strcmp(getenv("XDG_DATA_HOME"), home));
    assert(!strcmp(getenv("XDG_CACHE_HOME"), cache));
    assert(!strcmp(getenv("XDG_RUNTIME_DIR"), cache));
    g_session = &session;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeInitialize(&env, NULL, (jstring)home, (jstring)cache) == JNI_TRUE);
    assert(!initialize_runtime(other, cache)); /* Immutable after the first setup. */
    assert(!strcmp(getenv("HOME"), home));
    g_session = NULL;
    assert(unlink(file) == 0 && rmdir(home) == 0 && rmdir(cache) == 0 && rmdir(other) == 0 && rmdir(root) == 0);
    puts("native runtime: passed actual JNI HOME/private XDG bootstrap, shared profiles, post-load privacy guards and active-session protection");
}
