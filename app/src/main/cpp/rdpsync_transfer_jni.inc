/* JNI is called by the storage IO actor, not the UI/render callback. Lifecycle
 * ownership pins the session while global g_lock is released BEFORE any file
 * descriptor checks, waits, buffer copies or protocol-state mutex acquisition. */
static RdpSession* transfer_session_acquire(void) {
    pthread_mutex_lock(&g_lifecycle_lock);
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    pthread_mutex_unlock(&g_lock);
    return s;
}
static void transfer_session_release(RdpSession* s, int wake) {
    if (wake && s && s->wake_event && !atomic_load(&s->terminating)) SetEvent(s->wake_event);
    pthread_mutex_unlock(&g_lifecycle_lock);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOpenStaged(JNIEnv* e,jobject o,jstring directory,jstring file,jlong size) {
    (void)o;if(!directory||!file||size<0||(*e)->GetStringLength(e,directory)!=36||(*e)->GetStringLength(e,file)!=36)return TF_INVALID;
    const char* directory_id=(*e)->GetStringUTFChars(e,directory,NULL);if(!directory_id)return TF_INVALID;
    const char* file_id=(*e)->GetStringUTFChars(e,file,NULL);if(!file_id){(*e)->ReleaseStringUTFChars(e,directory,directory_id);return TF_INVALID;}
    char cache[PATH_MAX];pthread_mutex_lock(&g_lifecycle_lock);memcpy(cache,g_runtime_cache,sizeof(cache));pthread_mutex_unlock(&g_lifecycle_lock);
    int fd=rdpsync_transfer_open_staged(cache,directory_id,file_id,(uint64_t)size);
    (*e)->ReleaseStringUTFChars(e,file,file_id);(*e)->ReleaseStringUTFChars(e,directory,directory_id);return fd;
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferState(JNIEnv* e,jobject o) {
    (void)o;int64_t state[10];RdpSession* s=transfer_session_acquire();rdpsync_transfer_state(s?s->transfer:NULL,state);transfer_session_release(s,0);
    jlongArray a=(*e)->NewLongArray(e,10);if(a)(*e)->SetLongArrayRegion(e,a,0,10,(const jlong*)state);return a;
}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOffer(JNIEnv* e,jobject o,jlong gen,jobjectArray names,jlongArray sizes,jintArray fds) {
    (void)o;if(gen<=0||!names||!sizes||!fds)return TF_INVALID;
    jsize count=(*e)->GetArrayLength(e,names);
    if(count<1||count>RDPSYNC_TRANSFER_FILES||(*e)->GetArrayLength(e,sizes)!=count||(*e)->GetArrayLength(e,fds)!=count)return TF_INVALID;
    RdpTransferFile files[RDPSYNC_TRANSFER_FILES]={0};jlong lengths[RDPSYNC_TRANSFER_FILES];jint handles[RDPSYNC_TRANSFER_FILES];
    (*e)->GetLongArrayRegion(e,sizes,0,count,lengths);(*e)->GetIntArrayRegion(e,fds,0,count,handles);if((*e)->ExceptionCheck(e))return TF_INVALID;
    for(jsize i=0;i<count;i++) {
        jstring name=(*e)->GetObjectArrayElement(e,names,i);if(!name||lengths[i]<0){if(name)(*e)->DeleteLocalRef(e,name);return TF_INVALID;}
        jsize len=(*e)->GetStringLength(e,name);if(len<1||len>RDPSYNC_TRANSFER_NAME){(*e)->DeleteLocalRef(e,name);return TF_INVALID;}
        (*e)->GetStringRegion(e,name,0,len,(jchar*)files[i].name);(*e)->DeleteLocalRef(e,name);if((*e)->ExceptionCheck(e))return TF_INVALID;
        files[i].size=(uint64_t)lengths[i];
    }
    RdpSession* s=transfer_session_acquire();
    int64_t result=s&&atomic_load(&s->connected)&&!atomic_load(&s->terminating)?
        rdpsync_transfer_offer(s->transfer,(uint64_t)gen,files,(const int*)handles,(size_t)count):TF_DISCONNECTED;
    transfer_session_release(s,result>0);return result;
}
JNIEXPORT jboolean JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferOfferRetained(JNIEnv* e,jobject o,jlong token) {
    (void)e;(void)o;RdpSession* s=transfer_session_acquire();int yes=token>0&&rdpsync_transfer_retained(s?s->transfer:NULL,(uint64_t)token);transfer_session_release(s,0);return yes?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferCancel(JNIEnv* e,jobject o,jlong token) {
    (void)e;(void)o;RdpSession* s=transfer_session_acquire();int rc=token>0?rdpsync_transfer_cancel(s?s->transfer:NULL,(uint64_t)token):TF_INVALID;transfer_session_release(s,rc==0);return rc;
}
JNIEXPORT jlong JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferRequestList(JNIEnv* e,jobject o,jlong gen) {
    (void)e;(void)o;RdpSession* s=transfer_session_acquire();int64_t rc=gen>0&&s&&atomic_load(&s->connected)&&!atomic_load(&s->terminating)?rdpsync_transfer_request_list(s->transfer,(uint64_t)gen):TF_DISCONNECTED;transfer_session_release(s,rc>0);return rc;
}
JNIEXPORT jobjectArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferNames(JNIEnv* e,jobject o,jlong token) {
    (void)o;RdpTransferFile files[RDPSYNC_TRANSFER_FILES];size_t count=0;RdpSession* s=transfer_session_acquire();int rc=token>0?rdpsync_transfer_manifest(s?s->transfer:NULL,(uint64_t)token,files,&count):TF_INVALID;transfer_session_release(s,0);if(rc)return NULL;
    jclass cls=(*e)->FindClass(e,"java/lang/String");if(!cls)return NULL;jobjectArray names=(*e)->NewObjectArray(e,(jsize)count,cls,NULL);(*e)->DeleteLocalRef(e,cls);if(!names)return NULL;
    for(size_t i=0;i<count;i++){size_t n=0;while(files[i].name[n])++n;jstring name=(*e)->NewString(e,(const jchar*)files[i].name,(jsize)n);if(!name){(*e)->DeleteLocalRef(e,names);return NULL;}(*e)->SetObjectArrayElement(e,names,(jsize)i,name);(*e)->DeleteLocalRef(e,name);if((*e)->ExceptionCheck(e)){(*e)->DeleteLocalRef(e,names);return NULL;}}
    return names;
}
JNIEXPORT jlongArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferSizes(JNIEnv* e,jobject o,jlong token) {
    (void)o;RdpTransferFile files[RDPSYNC_TRANSFER_FILES];size_t count=0;RdpSession* s=transfer_session_acquire();int rc=token>0?rdpsync_transfer_manifest(s?s->transfer:NULL,(uint64_t)token,files,&count):TF_INVALID;transfer_session_release(s,0);if(rc)return NULL;
    jlong sizes[RDPSYNC_TRANSFER_FILES];for(size_t i=0;i<count;i++)sizes[i]=(jlong)files[i].size;jlongArray a=(*e)->NewLongArray(e,(jsize)count);if(a)(*e)->SetLongArrayRegion(e,a,0,(jsize)count,sizes);return a;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferRequestChunk(JNIEnv* e,jobject o,jlong token,jint index,jlong offset,jint length) {
    (void)e;(void)o;if(token<=0||index<0||offset<0||length<=0)return TF_INVALID;RdpSession* s=transfer_session_acquire();int rc=rdpsync_transfer_request_chunk(s?s->transfer:NULL,(uint64_t)token,(size_t)index,(uint64_t)offset,(uint32_t)length);transfer_session_release(s,rc==0);return rc;
}
JNIEXPORT jbyteArray JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferTakeChunk(JNIEnv* e,jobject o,jlong token) {
    (void)o;if(token<=0)return NULL;uint8_t* bytes=malloc(RDPSYNC_TRANSFER_CHUNK);if(!bytes)return NULL;RdpSession* s=transfer_session_acquire();int n=rdpsync_transfer_take_chunk(s?s->transfer:NULL,(uint64_t)token,bytes,RDPSYNC_TRANSFER_CHUNK);transfer_session_release(s,0);jbyteArray a=NULL;if(n>0){a=(*e)->NewByteArray(e,n);if(a)(*e)->SetByteArrayRegion(e,a,0,n,(const jbyte*)bytes);}free(bytes);return a;
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeFileTransferFinishReceive(JNIEnv* e,jobject o,jlong token) {
    (void)e;(void)o;RdpSession* s=transfer_session_acquire();int rc=token>0?rdpsync_transfer_finish(s?s->transfer:NULL,(uint64_t)token):TF_INVALID;transfer_session_release(s,rc==0);return rc;
}
