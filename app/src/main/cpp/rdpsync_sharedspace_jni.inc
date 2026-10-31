/* One persistent broker is shared by remote IRPs and explicit local actions.
 * JNI never receives a SAF URI or a server-selected absolute filesystem path.
 * Name-key callbacks are pure CPU-only Java, with process-lifetime class refs.
 */
#include <fcntl.h>
#include <errno.h>
#include "rdpsync_utf.h"

static pthread_mutex_t g_workspace_init_lock = PTHREAD_MUTEX_INITIALIZER;
static RdpWorkspace* g_workspace;
static JavaVM* g_workspace_vm;
static jclass g_workspace_names, g_workspace_entry, g_workspace_listing, g_workspace_read;
static jmethodID g_workspace_key, g_workspace_pattern_key, g_workspace_entry_ctor, g_workspace_listing_ctor, g_workspace_read_ctor;
static pthread_key_t g_workspace_thread_key;
static int g_workspace_thread_key_status = EAGAIN;
static pthread_once_t g_workspace_thread_once = PTHREAD_ONCE_INIT;
static atomic_llong g_workspace_import_token, g_workspace_import_cancel;
static pthread_mutex_t g_workspace_import_lock = PTHREAD_MUTEX_INITIALIZER;
static long long g_workspace_import_last;
static bool g_workspace_import_copy;

static void workspace_thread_detach(void* ignored) {
    (void)ignored;
    if (g_workspace_vm) (*g_workspace_vm)->DetachCurrentThread(g_workspace_vm);
}
static void workspace_thread_key_init(void) { g_workspace_thread_key_status = pthread_key_create(&g_workspace_thread_key, workspace_thread_detach); }
static JNIEnv* workspace_env(void) {
    if (!g_workspace_vm) return NULL;
    JNIEnv* env = NULL;
    jint rc = (*g_workspace_vm)->GetEnv(g_workspace_vm, (void**)&env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) return NULL;
#ifdef __ANDROID__
    if ((*g_workspace_vm)->AttachCurrentThread(g_workspace_vm, &env, NULL) != JNI_OK) return NULL;
#else
    if ((*g_workspace_vm)->AttachCurrentThread(g_workspace_vm, (void**)&env, NULL) != JNI_OK) return NULL;
#endif
    if (pthread_once(&g_workspace_thread_once, workspace_thread_key_init) != 0 || g_workspace_thread_key_status != 0) {
        (*g_workspace_vm)->DetachCurrentThread(g_workspace_vm); return NULL;
    }
    if (pthread_setspecific(g_workspace_thread_key, env) != 0) {
        (*g_workspace_vm)->DetachCurrentThread(g_workspace_vm); return NULL;
    }
    return env;
}
static jstring workspace_java_string(JNIEnv* env, const char* utf8) {
    size_t length = 0;
    WCHAR* wide = rdpsync_utf8_to_utf16(utf8, strlen(utf8), &length);
    if (!wide || length > INT_MAX) { free(wide); return NULL; }
    jstring result = (*env)->NewString(env, (const jchar*)wide, (jsize)length);
    free(wide); return result;
}
static char* workspace_utf8(JNIEnv* env, jstring value, size_t maximum) {
    if (!value) return NULL;
    const jsize length = (*env)->GetStringLength(env, value);
    if (length < 0 || (size_t)length > maximum) return NULL;
    const jchar* chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return NULL;
    int valid = 1;
    for (jsize i = 0; i < length; ++i) {
        if (!chars[i]) { valid = 0; break; }
        if (chars[i] >= 0xd800 && chars[i] <= 0xdbff) {
            if (i + 1 >= length || chars[i + 1] < 0xdc00 || chars[i + 1] > 0xdfff) { valid = 0; break; }
            ++i;
        } else if (chars[i] >= 0xdc00 && chars[i] <= 0xdfff) { valid = 0; break; }
    }
    size_t bytes = 0;
    char* result = valid ? rdpsync_utf16_to_utf8((const WCHAR*)chars, (size_t)length, &bytes) : NULL;
    (*env)->ReleaseStringChars(env, value, chars);
    if (result && bytes > maximum) { free(result); result = NULL; }
    if (!result && valid && length == 0) result = strdup("");
    return result;
}
static bool workspace_policy_key(jmethodID method, const char* name, char* output, size_t capacity) {
    JNIEnv* env = workspace_env();
    if (!env || !capacity || (*env)->PushLocalFrame(env, 4) != JNI_OK) return false;
    bool okay = false;
    jstring value = workspace_java_string(env, name);
    jstring key = value ? (*env)->CallStaticObjectMethod(env, g_workspace_names, method, value) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto out; }
    char* utf8 = key ? workspace_utf8(env, key, capacity - 1) : NULL;
    if (utf8) { memcpy(output, utf8, strlen(utf8) + 1); free(utf8); okay = true; }
out:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL); return okay;
}
static bool workspace_name_key(void* unused, const char* name, char* output, size_t capacity) {
    (void)unused; return workspace_policy_key(g_workspace_key, name, output, capacity);
}
static bool workspace_pattern_key(void* unused, const char* name, char* output, size_t capacity) {
    (void)unused; return workspace_policy_key(g_workspace_pattern_key, name, output, capacity);
}
static int workspace_cache_class(JNIEnv* env, const char* name, jclass* target) {
    if (*target) return 1;
    jclass local = (*env)->FindClass(env, name);
    if (!local) return 0;
    *target = (*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local);
    return *target != NULL;
}
static RdpWorkspace* workspace_require(JNIEnv* env, uint32_t* status) {
    pthread_mutex_lock(&g_workspace_init_lock);
    *status = RDP_WS_OK;
    if (g_workspace) { pthread_mutex_unlock(&g_workspace_init_lock); return g_workspace; }
    if ((*env)->GetJavaVM(env, &g_workspace_vm) != JNI_OK ||
        !workspace_cache_class(env, "com/rdp/sync/sharedspace/SharedSpaceNames", &g_workspace_names) ||
        !workspace_cache_class(env, "com/rdp/sync/sharedspace/SharedSpaceEntry", &g_workspace_entry) ||
        !workspace_cache_class(env, "com/rdp/sync/sharedspace/SharedSpaceListingResult", &g_workspace_listing) ||
        !workspace_cache_class(env, "com/rdp/sync/sharedspace/SharedSpaceReadResult", &g_workspace_read)) goto invalid;
    g_workspace_key = (*env)->GetStaticMethodID(env, g_workspace_names, "nativeKey", "(Ljava/lang/String;)Ljava/lang/String;");
    g_workspace_pattern_key = (*env)->GetStaticMethodID(env, g_workspace_names, "nativePatternKey", "(Ljava/lang/String;)Ljava/lang/String;");
    g_workspace_entry_ctor = (*env)->GetMethodID(env, g_workspace_entry, "<init>", "(Ljava/lang/String;ZJJZ)V");
    g_workspace_listing_ctor = (*env)->GetMethodID(env, g_workspace_listing, "<init>", "(I[Lcom/rdp/sync/sharedspace/SharedSpaceEntry;)V");
    g_workspace_read_ctor = (*env)->GetMethodID(env, g_workspace_read, "<init>", "(I[B)V");
    if (!g_workspace_key || !g_workspace_pattern_key || !g_workspace_entry_ctor || !g_workspace_listing_ctor || !g_workspace_read_ctor) goto invalid;
    char home[PATH_MAX];
    pthread_mutex_lock(&g_lifecycle_lock); memcpy(home, g_runtime_home, sizeof(home)); pthread_mutex_unlock(&g_lifecycle_lock);
    int fd = home[0] ? open(home, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW) : -1;
    if (fd < 0) goto invalid;
    *status = rdp_ws_create(fd, workspace_name_key, NULL, &g_workspace); close(fd);
    pthread_mutex_unlock(&g_workspace_init_lock); return g_workspace;
invalid:
    *status = RDP_WS_UNSUCCESSFUL;
    pthread_mutex_unlock(&g_workspace_init_lock); return NULL;
}
static jlongArray workspace_longs(JNIEnv* env, const jlong* values, jsize count) {
    jlongArray result = (*env)->NewLongArray(env, count);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, count, values);
    return result;
}
JNIEXPORT jobject JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceList(JNIEnv* env, jobject obj, jstring jpath) {
    (void)obj;
    uint32_t status, handle = 0, information = 0;
    RdpWorkspace* ws = workspace_require(env, &status);
    if ((*env)->ExceptionCheck(env) || !g_workspace_listing) return NULL;
    char* path = workspace_utf8(env, jpath, RDP_WS_MAX_PATH);
    RdpWorkspaceEntry* entries = NULL; size_t count = 0;
    if (!path) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_open(ws, 0, path, FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, FILE_OPEN, FILE_DIRECTORY_FILE, false, 0, &handle, &information);
    if (!status) {
        entries = calloc(RDP_WS_MAX_ENTRIES, sizeof(*entries));
        if (!entries) status = RDP_WS_INSUFFICIENT_RESOURCES;
        else for (;;) {
            RdpWorkspaceEntry entry;
            status = rdp_ws_next(ws, 0, handle, count == 0, &entry);
            if (status == RDP_WS_NO_MORE_FILES) { status = RDP_WS_OK; break; }
            if (status) break;
            if (count == RDP_WS_MAX_ENTRIES) { status = RDP_WS_INSUFFICIENT_RESOURCES; break; }
            entries[count++] = entry;
        }
    }
    if (handle) (void)rdp_ws_close(ws, 0, handle);
    free(path);
    jobjectArray array = (*env)->NewObjectArray(env, status ? 0 : (jsize)count, g_workspace_entry, NULL);
    if (array && !status) for (size_t i = 0; i < count; ++i) {
        jstring name = workspace_java_string(env, entries[i].name);
        jobject entry = name ? (*env)->NewObject(env, g_workspace_entry, g_workspace_entry_ctor, name,
            (jboolean)entries[i].directory, (jlong)entries[i].size, (jlong)entries[i].modified_millis,
            (jboolean)entries[i].read_only) : NULL;
        if (entry) { (*env)->SetObjectArrayElement(env, array, (jsize)i, entry); (*env)->DeleteLocalRef(env, entry); }
        if (name) (*env)->DeleteLocalRef(env, name);
        if ((*env)->ExceptionCheck(env) || !entry) break;
    }
    free(entries);
    jobject result = array && !(*env)->ExceptionCheck(env) ? (*env)->NewObject(env, g_workspace_listing, g_workspace_listing_ctor, (jint)status, array) : NULL;
    if (array) (*env)->DeleteLocalRef(env, array);
    return result;
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceSpace(JNIEnv* env, jobject obj) {
    (void)obj; uint32_t status; RdpWorkspaceSpace space = {0}; RdpWorkspace* ws = workspace_require(env, &status);
    if (ws) status = rdp_ws_space(ws, &space);
    const jlong values[] = {status, space.used_bytes, space.available_bytes, space.entries};
    return workspace_longs(env, values, 4);
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceOpenExport(JNIEnv* env, jobject obj, jstring jpath) {
    (void)obj; uint32_t status, handle = 0, information = 0; RdpWorkspaceEntry entry = {0};
    RdpWorkspace* ws = workspace_require(env, &status); char* path = workspace_utf8(env, jpath, RDP_WS_MAX_PATH);
    if (!path) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_open(ws, 0, path, GENERIC_READ, FILE_SHARE_READ,
        FILE_OPEN, FILE_NON_DIRECTORY_FILE, false, 0, &handle, &information);
    if (!status) status = rdp_ws_stat(ws, 0, handle, &entry);
    if (status && handle) { (void)rdp_ws_close(ws, 0, handle); handle = 0; }
    free(path); const jlong values[] = {status, handle, entry.size}; return workspace_longs(env, values, 3);
}
JNIEXPORT jobject JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceReadExport(JNIEnv* env, jobject obj, jlong handle, jlong offset, jint length) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status); uint32_t actual = 0;
    if ((*env)->ExceptionCheck(env) || !g_workspace_read) return NULL;
    BYTE* buffer = NULL;
    if (handle <= 0 || handle > UINT32_MAX || offset < 0 || length < 0 || (uint32_t)length > RDP_WS_MAX_IO) status = RDP_WS_INVALID_PARAMETER;
    else if (ws) {
        buffer = malloc(length ? (size_t)length : 1);
        status = buffer ? rdp_ws_read(ws, 0, (uint32_t)handle, (uint64_t)offset, buffer, (uint32_t)length, &actual) : RDP_WS_INSUFFICIENT_RESOURCES;
    }
    jbyteArray data = (*env)->NewByteArray(env, status ? 0 : (jsize)actual);
    if (data && !status && actual) (*env)->SetByteArrayRegion(env, data, 0, (jsize)actual, (jbyte*)buffer);
    free(buffer);
    jobject result = data ? (*env)->NewObject(env, g_workspace_read, g_workspace_read_ctor, (jint)status, data) : NULL;
    if (data) (*env)->DeleteLocalRef(env, data); return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCloseExport(JNIEnv* env, jobject obj, jlong handle) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    return ws && handle > 0 && handle <= UINT32_MAX ? (jint)rdp_ws_close(ws, 0, (uint32_t)handle) : (jint)RDP_WS_INVALID_HANDLE;
}
/* One explicitly registered batch owns cancellation throughout provider staging
 * and all of its imports. UI cancel is atomic only. Before registration, the
 * Java cancellation flag is authoritative and MUST be checked immediately after
 * Begin; thus there is no check-to-native-entry gap and no cumulative foreign
 * token watermark cancelling unrelated work. */
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(JNIEnv* env, jobject obj, jlong token) {
    (void)env; (void)obj;
    if (token <= 0) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&g_workspace_import_lock);
    uint32_t status = RDP_WS_OK;
    if (atomic_load(&g_workspace_import_token) || g_workspace_import_copy) status = RDP_WS_SHARING_VIOLATION;
    else if (token <= g_workspace_import_last) status = RDP_WS_INVALID_PARAMETER;
    else { g_workspace_import_last = token; atomic_store(&g_workspace_import_token, token); }
    pthread_mutex_unlock(&g_workspace_import_lock); return status;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(JNIEnv* env, jobject obj, jlong token) {
    (void)env; (void)obj;
    pthread_mutex_lock(&g_workspace_import_lock);
    uint32_t status = RDP_WS_OK;
    if (token <= 0 || atomic_load(&g_workspace_import_token) != token) status = RDP_WS_INVALID_PARAMETER;
    else if (g_workspace_import_copy) status = RDP_WS_SHARING_VIOLATION;
    else atomic_store(&g_workspace_import_token, 0);
    pthread_mutex_unlock(&g_workspace_import_lock); return status;
}
static uint32_t workspace_import_enter(jlong token) {
    pthread_mutex_lock(&g_workspace_import_lock);
    uint32_t status = RDP_WS_OK;
    if (atomic_load(&g_workspace_import_token) != token || atomic_load(&g_workspace_import_cancel) == token) status = RDP_WS_CANCELLED;
    else if (g_workspace_import_copy) status = RDP_WS_SHARING_VIOLATION;
    else g_workspace_import_copy = true;
    pthread_mutex_unlock(&g_workspace_import_lock); return status;
}
static void workspace_import_leave(void) {
    pthread_mutex_lock(&g_workspace_import_lock); g_workspace_import_copy = false; pthread_mutex_unlock(&g_workspace_import_lock);
}
static bool workspace_import_cancelled(void* context) {
    const jlong token = *(const jlong*)context;
    return atomic_load(&g_workspace_import_token) != token || atomic_load(&g_workspace_import_cancel) == token;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceOpenStaged(JNIEnv* env, jobject obj, jstring directory, jstring file, jlong size) {
    (void)obj;
    if (size < 0 || (uint64_t)size > RDP_WS_MAX_FILE_BYTES) return TF_INVALID;
    char* directory_id = workspace_utf8(env, directory, 36);
    char* file_id = workspace_utf8(env, file, 36);
    char cache[PATH_MAX];
    pthread_mutex_lock(&g_lifecycle_lock); memcpy(cache, g_runtime_cache, sizeof(cache)); pthread_mutex_unlock(&g_lifecycle_lock);
    int result = directory_id && file_id ? rdpsync_sharedspace_open_staged(cache, directory_id, file_id, (uint64_t)size) : TF_INVALID;
    free(directory_id); free(file_id); return result;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImport(JNIEnv* env, jobject obj, jint fd, jlong size, jstring jparent, jstring jname, jlong token) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    if (!ws) return status;
    if (fd < 0 || size < 0 || (uint64_t)size > RDP_WS_MAX_FILE_BYTES || token <= 0) return RDP_WS_INVALID_PARAMETER;
    status = workspace_import_enter(token);
    if (status) return status;
    char* parent = workspace_utf8(env, jparent, RDP_WS_MAX_PATH);
    char* name = workspace_utf8(env, jname, RDP_WS_MAX_NAME);
    int owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (!parent || !name) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (owned < 0) status = RDP_WS_INVALID_HANDLE;
    else status = rdp_ws_import(ws, parent, name, owned, size, workspace_import_cancelled, &token);
    if (owned >= 0) close(owned); free(parent); free(name);
    workspace_import_leave(); return status;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImportToDirectory(JNIEnv* env, jobject obj, jint fd, jlong size, jlong parent_handle, jstring jname, jlong token) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    if (!ws) return status;
    if (fd < 0 || size < 0 || (uint64_t)size > RDP_WS_MAX_FILE_BYTES || token <= 0 ||
        parent_handle <= 0 || parent_handle > UINT32_MAX) return RDP_WS_INVALID_PARAMETER;
    status = workspace_import_enter(token);
    if (status) return status;
    char* name = workspace_utf8(env, jname, RDP_WS_MAX_NAME);
    int owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (!name) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (owned < 0) status = RDP_WS_INVALID_HANDLE;
    else status = rdp_ws_import_at(ws, 0, (uint32_t)parent_handle, name, owned, size, workspace_import_cancelled, &token);
    if (owned >= 0) close(owned); free(name);
    workspace_import_leave(); return status;
}
static jlongArray workspace_open_selection(JNIEnv* env, jstring jpath, bool directory) {
    uint32_t status, handle = 0, information = 0; RdpWorkspace* ws = workspace_require(env, &status);
    char* path = workspace_utf8(env, jpath, RDP_WS_MAX_PATH);
    if (!path) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_open(ws, 0, path,
        directory ? FILE_LIST_DIRECTORY | FILE_ADD_FILE | FILE_READ_ATTRIBUTES : DELETE | FILE_READ_ATTRIBUTES,
        directory ? FILE_SHARE_READ | FILE_SHARE_WRITE : FILE_SHARE_READ,
        FILE_OPEN, directory ? FILE_DIRECTORY_FILE : 0, false, 0, &handle, &information);
    free(path); const jlong values[] = {status, handle}; return workspace_longs(env, values, 2);
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceOpenImportDirectory(JNIEnv* env, jobject obj, jstring path) {
    (void)obj; return workspace_open_selection(env, path, true);
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceOpenMutation(JNIEnv* env, jobject obj, jstring path) {
    (void)obj; return workspace_open_selection(env, path, false);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceRenameSelection(JNIEnv* env, jobject obj, jlong handle, jstring next) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    if (handle <= 0 || handle > UINT32_MAX) return RDP_WS_INVALID_PARAMETER;
    char* path = workspace_utf8(env, next, RDP_WS_MAX_PATH);
    if (!path) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_rename(ws, 0, (uint32_t)handle, path, false);
    free(path); return status;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceDeleteSelection(JNIEnv* env, jobject obj, jlong handle) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    if (handle <= 0 || handle > UINT32_MAX) return RDP_WS_INVALID_PARAMETER;
    return ws ? rdp_ws_set_delete(ws, 0, (uint32_t)handle, true) : status;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceMkdirSelection(JNIEnv* env, jobject obj, jlong handle, jstring jname) {
    (void)obj; uint32_t status; RdpWorkspace* ws = workspace_require(env, &status);
    if (handle <= 0 || handle > UINT32_MAX) return RDP_WS_INVALID_PARAMETER;
    char* name = workspace_utf8(env, jname, RDP_WS_MAX_NAME);
    if (!name) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_mkdir_at(ws, 0, (uint32_t)handle, name);
    free(name); return status;
}
static void workspace_import_mark_cancelled(jlong token) {
    /* A delayed old signal may race End/new Begin. AtomicMax prevents that
     * delayed store from erasing cancellation of the newer registered token. */
    long long previous = atomic_load(&g_workspace_import_cancel);
    while (token > previous && !atomic_compare_exchange_weak(&g_workspace_import_cancel, &previous, (long long)token)) {}
}
JNIEXPORT void JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(JNIEnv* env, jobject obj, jlong token) {
    (void)env; (void)obj;
    if (token <= 0 || atomic_load(&g_workspace_import_token) != token) return;
    workspace_import_mark_cancelled(token);
}
static uint32_t workspace_local_action(JNIEnv* env, jstring jpath, jstring jnewpath, int action) {
    uint32_t status, handle = 0, information = 0; RdpWorkspace* ws = workspace_require(env, &status);
    char* path = workspace_utf8(env, jpath, RDP_WS_MAX_PATH);
    char* newpath = jnewpath ? workspace_utf8(env, jnewpath, RDP_WS_MAX_PATH) : NULL;
    if (!path || (action == 1 && !newpath)) status = RDP_WS_OBJECT_NAME_INVALID;
    else if (ws) status = rdp_ws_open(ws, 0, path,
        action == 0 ? FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES : DELETE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        action == 0 ? FILE_CREATE : FILE_OPEN, action == 0 ? FILE_DIRECTORY_FILE : 0, false, 0, &handle, &information);
    if (!status && action == 1) status = rdp_ws_rename(ws, 0, handle, newpath, false);
    if (!status && action == 2) status = rdp_ws_set_delete(ws, 0, handle, true);
    if (handle) { uint32_t close_status = rdp_ws_close(ws, 0, handle); if (!status) status = close_status; }
    free(path); free(newpath); return status;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceMkdir(JNIEnv* e, jobject o, jstring path) { (void)o; return workspace_local_action(e, path, NULL, 0); }
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceRename(JNIEnv* e, jobject o, jstring path, jstring next) { (void)o; return workspace_local_action(e, path, next, 1); }
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceDelete(JNIEnv* e, jobject o, jstring path) { (void)o; return workspace_local_action(e, path, NULL, 2); }
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedDriveState(JNIEnv* env, jobject obj) {
    (void)obj; int64_t state[13]; pthread_mutex_lock(&g_lifecycle_lock);
    pthread_mutex_lock(&g_lock); RdpSession* session = g_session; pthread_mutex_unlock(&g_lock);
    rdpsync_drive_state(session ? session->drive : NULL, state);
    if (session && !state[1]) state[1] = session->generation;
    pthread_mutex_unlock(&g_lifecycle_lock);
    return workspace_longs(env, (const jlong*)state, 13);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSharedDriveRevoke(JNIEnv* env, jobject obj, jlong generation) {
    (void)env; (void)obj; jint result = (jint)RDP_WS_INVALID_PARAMETER; pthread_mutex_lock(&g_lifecycle_lock);
    pthread_mutex_lock(&g_lock); RdpSession* session = g_session; pthread_mutex_unlock(&g_lock);
    if (session && session->drive && generation > 0 && session->generation == generation) {
        rdpsync_drive_revoke(session->drive); if (session->wake_event) SetEvent(session->wake_event); result = 0;
    }
    pthread_mutex_unlock(&g_lifecycle_lock); return result;
}
