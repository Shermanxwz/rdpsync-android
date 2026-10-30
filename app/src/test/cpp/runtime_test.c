/* Actual production JNI runtime bootstrap, using disposable owned directories.
 * This tests environment setup, NOT a mocked FreeRDP context allocation. The
 * Android integration fixture separately proves allocation/connect on device. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include "../../main/cpp/freerdp_bridge.c"

static const char* JNICALL test_utf_chars(JNIEnv* env, jstring text, jboolean* copy) {
    (void)env; (void)copy; return (const char*)text;
}
static void JNICALL test_release_utf(JNIEnv* env, jstring text, const char* chars) {
    (void)env; assert((const char*)text == chars);
}
static jsize JNICALL test_string_length(JNIEnv* env,jstring text){(void)env;return (jsize)strlen((const char*)text);}
static const struct JNINativeInterface_ runtime_jni = {
    .GetStringUTFChars = test_utf_chars, .ReleaseStringUTFChars = test_release_utf,
    .GetStringLength = test_string_length
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
static void check_staged_open(JNIEnv* env,const char* root,const char* cache,const char* outside) {
    const char* directory_id="01234567-89ab-cdef-0123-456789abcdef";
    const char* file_id="fedcba98-7654-3210-fedc-ba9876543210";
    char base[PATH_MAX],directory[PATH_MAX],path[PATH_MAX],alias[PATH_MAX],aliased_cache[PATH_MAX];
    snprintf(base,sizeof(base),"%s/rdpsync-transfer",root);snprintf(directory,sizeof(directory),"%s/%s",base,directory_id);
    snprintf(path,sizeof(path),"%s/%s.bin",directory,file_id);
    assert(!mkdir(base,0700)&&!mkdir(directory,0700));
    int file=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);assert(file>=0&&write(file,"ABCD",4)==4);close(file);
    int fd=Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOpenStaged(env,NULL,(jstring)directory_id,(jstring)file_id,4);
    assert(fd>=0&&(fcntl(fd,F_GETFD)&FD_CLOEXEC)&&(fcntl(fd,F_GETFL)&O_ACCMODE)==O_RDONLY&&!(fcntl(fd,F_GETFL)&O_NONBLOCK));
    char bytes[4];assert(read(fd,bytes,4)==4&&!memcmp(bytes,"ABCD",4));close(fd);
    /* Android ancestors such as /data grant search but not listing to apps.
     * Non-root CI also proves a readonly-directory walk would fail here. */
    assert(!chmod(root,0111));
    if(geteuid()!=0){int denied=open(root,O_RDONLY|O_DIRECTORY);assert(denied<0&&errno==EACCES);}
    fd=rdpsync_transfer_open_staged(cache,directory_id,file_id,4);assert(fd>=0);close(fd);assert(!chmod(root,0700));
    assert(!chmod(base,0777));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,4)==TF_IO);assert(!chmod(base,0700));
    assert(!chmod(directory,0777));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,4)==TF_IO);assert(!chmod(directory,0700));
    assert(Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOpenStaged(env,NULL,(jstring)directory_id,(jstring)file_id,3)==TF_IO);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOpenStaged(env,NULL,(jstring)directory_id,(jstring)file_id,-1)==TF_INVALID);
    assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,RDPSYNC_TRANSFER_FILE_BYTES+1)==TF_INVALID);
    assert(rdpsync_transfer_open_staged(cache,"../private-home",file_id,4)==TF_INVALID);
    assert(rdpsync_transfer_open_staged(cache,directory_id,"FEDCBA98-7654-3210-fedc-ba9876543210",4)==TF_INVALID);
    assert(rdpsync_transfer_open_staged(cache,directory_id,"fedcba98-7654-3210-fedc-ba9876543210.part",4)==TF_INVALID);
    /* Never follow final files, hard links, session/base symlinks or a changed
     * initialized path ancestor; never block on a nonregular FIFO replacement. */
    assert(!unlink(path)&&!symlink(outside,path));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);
    assert(!unlink(path)&&!link(outside,path));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);
    assert(!unlink(path)&&!mkfifo(path,0600));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);
    assert(!unlink(path)&&!mkdir(path,0700));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);
    assert(!rmdir(path)&&!rmdir(directory)&&!symlink(root,directory));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);
    assert(!unlink(directory)&&!rmdir(base)&&!symlink(root,base));assert(rdpsync_transfer_open_staged(cache,directory_id,file_id,0)==TF_IO);assert(!unlink(base));
    assert(!mkdir(base,0700)&&!mkdir(directory,0700));file=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);assert(file>=0);close(file);
    fd=rdpsync_transfer_open_staged(cache,directory_id,file_id,0);assert(fd>=0);close(fd);
    snprintf(alias,sizeof(alias),"%s/alias",root);assert(!symlink(root,alias));snprintf(aliased_cache,sizeof(aliased_cache),"%s/private-cache",alias);
    assert(rdpsync_transfer_open_staged(aliased_cache,directory_id,file_id,0)==TF_IO);assert(!unlink(alias));
    assert(!unlink(path)&&!rmdir(directory)&&!rmdir(base));
}
int main(void) {
    assert(configure_session_profile(NULL, 1280, 720, TRUE, FALSE));
    assert(bool_options[FreeRDP_NetworkAutoDetect] && bool_options[FreeRDP_DeviceRedirection]);
    assert(bool_options[FreeRDP_SupportDisplayControl] && bool_options[FreeRDP_MultiTouchInput]);
    /* Initial core monitor-layout PDU is not required for the single monitor;
     * genuine DISP dynamic resize remains enabled independently. */
    assert(!bool_options[FreeRDP_SupportMonitorLayoutPdu]);
    assert(bool_options[FreeRDP_DynamicResolutionUpdate]);
    assert(bool_options[FreeRDP_SupportDynamicChannels]);
    assert(!bool_options[FreeRDP_AudioCapture] && !bool_options[FreeRDP_AudioPlayback]);
    assert(!bool_options[FreeRDP_RedirectDrives] && !bool_options[FreeRDP_RedirectHomeDrive]);
    assert(!bool_options[FreeRDP_RedirectClipboard] && bool_options[FreeRDP_SupportGraphicsPipeline]);
    assert(bool_options[FreeRDP_GfxSmallCache] && !bool_options[FreeRDP_BitmapCachePersistEnabled]);
    assert(!bool_options[FreeRDP_GfxH264] && !bool_options[FreeRDP_GfxAVC444] && !bool_options[FreeRDP_GfxAVC444v2]);
    assert(uint_options[FreeRDP_DesktopWidth] == 1280 && uint_options[FreeRDP_DesktopHeight] == 720);
    assert(configure_session_profile(NULL, 1280, 720, TRUE, TRUE));
    assert(!bool_options[FreeRDP_NetworkAutoDetect] && !bool_options[FreeRDP_DeviceRedirection]);
    assert(!bool_options[FreeRDP_SupportDisplayControl] && !bool_options[FreeRDP_MultiTouchInput]);
    assert(!bool_options[FreeRDP_SupportDynamicChannels]);
    assert(!bool_options[FreeRDP_SupportGraphicsPipeline]);
    RdpSyncContext profile_context = {0}; freerdp profile_instance = {0};
    profile_instance.context = &profile_context.common.context;
    assert(load_session_channels(&profile_instance));
    for (loader_mutation = 1; loader_mutation <= 4; ++loader_mutation) {
        assert(configure_session_profile(NULL, 1280, 720, TRUE, FALSE));
        uint_options[FreeRDP_DeviceCount] = 0;
        assert(!load_session_channels(&profile_instance));
    }
    loader_mutation = 0;
    assert(configure_transfer_profile(NULL, TRUE));
    assert(bool_options[FreeRDP_RedirectClipboard]);
    assert(uint_options[FreeRDP_ClipboardFeatureMask] == (CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES | CLIPRDR_FLAG_REMOTE_TO_LOCAL_FILES));
    assert(!load_session_channels(&profile_instance)); /* no explicit owner consent */
    RdpSession transfer_owner = {0}; transfer_owner.file_transfer_enabled = 1; profile_context.session = &transfer_owner;
    assert(load_session_channels(&profile_instance));
    uint_options[FreeRDP_ClipboardFeatureMask] |= CLIPRDR_FLAG_LOCAL_TO_REMOTE;
    assert(!load_session_channels(&profile_instance)); /* text never enabled */
    assert(configure_transfer_profile(NULL, FALSE));
    transfer_owner.file_transfer_enabled = 0;
    assert(load_session_channels(&profile_instance));

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
    assert(Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOpenStaged(&env,NULL,(jstring)"01234567-89ab-cdef-0123-456789abcdef",(jstring)"fedcba98-7654-3210-fedc-ba9876543210",0)==TF_UNAVAILABLE);
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
    check_staged_open(&env,root,cache,file);
    g_session = &session;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeInitialize(&env, NULL, (jstring)home, (jstring)cache) == JNI_TRUE);
    assert(!initialize_runtime(other, cache)); /* Immutable after the first setup. */
    assert(!strcmp(getenv("HOME"), home));
    g_session = NULL;
    assert(unlink(file) == 0 && rmdir(home) == 0 && rmdir(cache) == 0 && rmdir(other) == 0 && rmdir(root) == 0);
    puts("native runtime: passed actual JNI HOME/private XDG bootstrap, shared profiles, staging confinement/CLOEXEC, post-load privacy guards and active-session protection");
}
