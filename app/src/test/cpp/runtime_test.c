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
int main(void) {
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
    puts("native runtime: passed actual JNI HOME/private XDG bootstrap, validation, idempotence and active-session protection");
}
