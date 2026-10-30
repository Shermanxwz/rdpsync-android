/* Actual JNI cancellation entrypoints: the UI can cancel in the tiny interval
 * after its final Kotlin check but BEFORE native registers the import token. */
#define _GNU_SOURCE
#include <assert.h>
#include <ctype.h>
#include <fcntl.h>
#define pthread_key_create test_key_create
#define pthread_setspecific test_set_specific
#include "../../main/cpp/freerdp_bridge.c"
#undef pthread_key_create
#undef pthread_setspecific
static int key_create_calls, specific_calls, attach_calls, detach_calls;
int test_key_create(pthread_key_t* key, void (*destructor)(void*)) { (void)key; (void)destructor; ++key_create_calls; return EAGAIN; }
int test_set_specific(pthread_key_t key, const void* value) { (void)key; (void)value; ++specific_calls; return 0; }
static JNIEnv* attached_env;
static jint JNICALL vm_get_env(JavaVM* vm, void** env, jint version) { (void)vm; (void)version; *env=NULL; return JNI_EDETACHED; }
static jint JNICALL vm_attach(JavaVM* vm, void** env, void* options) { (void)vm; (void)options; *env=attached_env; ++attach_calls; return JNI_OK; }
static jint JNICALL vm_detach(JavaVM* vm) { (void)vm; ++detach_calls; return JNI_OK; }
static const struct JNIInvokeInterface_ invoke_methods={.GetEnv=vm_get_env,.AttachCurrentThread=vm_attach,.DetachCurrentThread=vm_detach};
static jsize JNICALL string_length(JNIEnv* env,jstring string){(void)env;const uint16_t* p=(const uint16_t*)string;jsize n=0;while(p[n])n++;return n;}
static const jchar* JNICALL string_chars(JNIEnv* env,jstring string,jboolean* copy){(void)env;(void)copy;return (const jchar*)string;}
static void JNICALL release_chars(JNIEnv* env,jstring string,const jchar* chars){(void)env;assert((const jchar*)string==chars);}
static const struct JNINativeInterface_ methods={.GetStringLength=string_length,.GetStringChars=string_chars,.ReleaseStringChars=release_chars};
static bool key(void* context,const char* name,char* out,size_t capacity){(void)context;size_t n=strlen(name);if(n>=capacity)return false;for(size_t i=0;i<=n;i++)out[i]=(char)toupper((unsigned char)name[i]);return true;}
static pthread_mutex_t signal_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t signal_changed = PTHREAD_COND_INITIALIZER;
static bool old_checked, resume_old;
static void* delayed_old_signal(void* ignored) {
    (void)ignored;
    /* Stop between the exact-active-token guard and the actual production
     * atomic mark. End/new Begin can happen in this precise instruction gap. */
    assert(atomic_load(&g_workspace_import_token) == 45);
    pthread_mutex_lock(&signal_lock); old_checked = true; pthread_cond_broadcast(&signal_changed);
    while (!resume_old) pthread_cond_wait(&signal_changed, &signal_lock);
    pthread_mutex_unlock(&signal_lock);
    workspace_import_mark_cancelled(45);
    return NULL;
}
int main(void){
    JNIEnv env=&methods;
    char home[]="/tmp/rdpsync-jni-import-test-XXXXXX";assert(mkdtemp(home));
    int fd=open(home,O_RDONLY|O_DIRECTORY|O_CLOEXEC);assert(fd>=0);
    assert(!rdp_ws_create(fd,key,NULL,&g_workspace));
    uint32_t parent=0,information=0;assert(!rdp_ws_open(g_workspace,0,"",3,3,FILE_OPEN,FILE_DIRECTORY_FILE,false,0,&parent,&information));
    int source=openat(fd,"source",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);assert(source>=0&&write(source,"ABC",3)==3);close(source);
    source=openat(fd,"source",O_RDONLY|O_CLOEXEC);assert(source>=0);
    const uint16_t name[]={'s','a','f','e','.','b','i','n',0};
    /* Cancel before Begin is held by Java's AtomicBoolean. Its mandatory
     * post-Begin check aborts provider/native work and the finally ends batch.
     * The pure Kotlin registration fixture tests that recheck directly. */
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,41);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,41));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,41));
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImportToDirectory(&env,NULL,source,3,parent,(jstring)name,41)==(jint)RDP_WS_CANCELLED);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,42));
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,43)==(jint)RDP_WS_SHARING_VIOLATION);
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,99); /* unrelated unregistered newer route */
    jlong active=42;assert(!workspace_import_cancelled(&active));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImportToDirectory(&env,NULL,source,3,parent,(jstring)name,42));
    assert(atomic_load(&g_workspace_import_token)==42&&!g_workspace_import_copy);
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,42);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImportToDirectory(&env,NULL,source,3,parent,(jstring)name,42)==(jint)RDP_WS_CANCELLED); /* second file in same batch */
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,41)==(jint)RDP_WS_INVALID_PARAMETER);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,42));
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,42)==(jint)RDP_WS_INVALID_PARAMETER);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,43));
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,42);
    active=43;assert(!workspace_import_cancelled(&active));
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,43);
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,42);
    assert(workspace_import_cancelled(&active)&&atomic_load(&g_workspace_import_cancel)==43);
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceImportToDirectory(&env,NULL,source,3,parent,(jstring)name,43)==(jint)RDP_WS_CANCELLED);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,43));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,44));
    assert(!workspace_import_enter(44));
    assert(Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,44)==(jint)RDP_WS_SHARING_VIOLATION);
    workspace_import_leave();assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,44));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,45));
    pthread_t delayed;assert(!pthread_create(&delayed,NULL,delayed_old_signal,NULL));
    pthread_mutex_lock(&signal_lock);while(!old_checked)pthread_cond_wait(&signal_changed,&signal_lock);pthread_mutex_unlock(&signal_lock);
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,45));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceBeginImport(&env,NULL,46));
    Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceCancelImport(&env,NULL,46);
    pthread_mutex_lock(&signal_lock);resume_old=true;pthread_cond_broadcast(&signal_changed);pthread_mutex_unlock(&signal_lock);
    assert(!pthread_join(delayed,NULL));active=46;
    assert(atomic_load(&g_workspace_import_cancel)==46&&workspace_import_cancelled(&active));
    assert(!Java_com_rdp_sync_network_RdpConnector_nativeSharedSpaceEndImport(&env,NULL,46));
    uint32_t child=0;assert(!rdp_ws_open(g_workspace,0,"safe.bin",1,1,FILE_OPEN,FILE_NON_DIRECTORY_FILE,false,0,&child,&information));
    char bytes[3];uint32_t actual;assert(!rdp_ws_read(g_workspace,0,child,0,bytes,3,&actual)&&actual==3&&!memcmp(bytes,"ABC",3));
    assert(!rdp_ws_close(g_workspace,0,child));assert(!rdp_ws_close(g_workspace,0,parent));
    /* Fixed Shared Space staging ceiling must not widen the old CLIPRDR
     * opener. Sparse temporary source exercises limits without disk waste. */
    assert(!mkdirat(fd,"rdpsync-transfer",0700));
    const char* directory="01234567-89ab-cdef-0123-456789abcdef";
    const char* file="fedcba98-7654-3210-fedc-ba9876543210";
    char staged[256],cache[256];snprintf(cache,sizeof(cache),"%s/freerdp",home);
    snprintf(staged,sizeof(staged),"%s/rdpsync-transfer/%s",home,directory);assert(!mkdir(staged,0700));
    strcat(staged,"/fedcba98-7654-3210-fedc-ba9876543210.bin");
    int large=open(staged,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);assert(large>=0);
    const uint64_t big=RDPSYNC_TRANSFER_FILE_BYTES+1;assert(!ftruncate(large,(off_t)big));close(large);
    assert(rdpsync_transfer_open_staged(cache,directory,file,big)==TF_INVALID);
    large=rdpsync_sharedspace_open_staged(cache,directory,file,big);assert(large>=0);
    assert((fcntl(large,F_GETFD)&FD_CLOEXEC)&&((fcntl(large,F_GETFL)&O_ACCMODE)==O_RDONLY));close(large);
    assert(rdpsync_sharedspace_open_staged(cache,directory,file,RDP_WS_MAX_FILE_BYTES+1)==TF_INVALID);
    assert(rdpsync_sharedspace_open_staged(cache,directory,"../escape",big)==TF_INVALID);
    close(source);close(fd);rdp_ws_destroy(g_workspace);g_workspace=NULL;
    /* A failed TLS-key allocation must never use the zero/unrelated key.
     * The newly attached worker is detached and its storage callback fails. */
    JavaVM vm=&invoke_methods;g_workspace_vm=&vm;attached_env=&env;
    assert(workspace_env()==NULL);
    assert(key_create_calls==1&&specific_calls==0&&attach_calls==1&&detach_calls==1);
    assert(workspace_env()==NULL); /* once remembers failure; still detach safely */
    assert(key_create_calls==1&&specific_calls==0&&attach_calls==2&&detach_calls==2);
    g_workspace_vm=NULL;
    char command[256];snprintf(command,sizeof(command),"rm -rf -- '%s'",home);assert(!system(command));
    puts("production shared-space JNI: exact registered batch, stale/unrelated cancel isolation, finish/new batch, fixed staging limits, pinned parent and failed JNI TLS-key attach cleanup PASS");
}
