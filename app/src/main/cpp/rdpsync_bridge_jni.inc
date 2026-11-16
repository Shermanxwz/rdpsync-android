static void* thread_fn(void* arg) {
    RdpSession* s = arg;
    freerdp* instance = s->instance;
    s_status(s, "正在连接...");
    const BOOL session_ready = s_from_ctx(instance->context) == s;
    const BOOL callback_ready = instance->VerifyX509Certificate == verify_x509_certificate;
    const BOOL external_ready = freerdp_settings_get_bool(instance->context->settings, FreeRDP_ExternalCertificateManagement);
    s_diag(s, session_ready ? "certificate=session-bound" : "certificate=session-mismatch");
    s_diag(s, callback_ready ? "certificate=callback-bound" : "certificate=callback-mismatch");
    s_diag(s, external_ready ? "certificate=external-management" : "certificate=external-disabled");
    if (!session_ready || !callback_ready || !external_ready) {
        s_error(s, "证书验证上下文未就绪，已阻止连接");
        s_diag(s, "certificate=context-invariant-failed");
        goto done;
    }
    s_diag(s, "certificate=context-ready");
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
        if (s->wake_event) ResetEvent(s->wake_event);
        int transfer_sent = 0;
        if (rdpsync_transfer_pump_ex(s->transfer, &transfer_sent) != CHANNEL_RC_OK) { s_error(s, "文件传输通道发送失败"); break; }
        if (transfer_sent) (void)freerdp_channels_check_fds(instance->context->channels, instance);
        if (!process_commands(s)) { s_error(s, "输入发送失败，连接已停止"); break; }
        if (!rdpsync_drive_pump(s->drive, instance)) { s_error(s, "共享空间通道发送失败"); break; }
        HANDLE handles[MAXIMUM_WAIT_OBJECTS] = {0};
        DWORD count = freerdp_get_event_handles(instance->context, handles, MAXIMUM_WAIT_OBJECTS - 1);
        if (!count) { s_error(s, "连接中断"); break; }
        handles[count++] = s->wake_event;
        const DWORD status = WaitForMultipleObjects(count, handles, FALSE, 100);
        if (atomic_load(&s->terminating)) break;
        if (status == WAIT_FAILED) { s_error(s, "连接事件等待失败"); break; }
        if (status != WAIT_TIMEOUT && !freerdp_check_event_handles(instance->context)) {
            if (!freerdp_shall_disconnect_context(instance->context)) {
                const UINT32 err = freerdp_get_last_error(instance->context);
                char diag_line[96];
                snprintf(diag_line, sizeof(diag_line), "disconnect-error=0x%08x", (unsigned)err);
                s_diag(s, diag_line);
                pthread_mutex_lock(&s->mutex);
                const int has_err = s->last_error[0] != 0;
                pthread_mutex_unlock(&s->mutex);
                if (!has_err) {
                    if (err) s_error(s, "连接中断 [0x%08x]: %s", (unsigned)err, freerdp_get_last_error_string(err));
                    else s_error(s, "连接中断");
                }
            }
            break;
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
    memset(s->command_sync_seq, 0, sizeof(s->command_sync_seq));
    if (!s->last_error[0]) snprintf(s->status, sizeof(s->status), "已断开");
    pthread_mutex_unlock(&s->mutex);
    notify_input_sync_abort(s->generation);
    return NULL;
}
static void session_destroy(RdpSession* s) {
    if (!s) return;
    atomic_store(&s->terminating, 1);
    notify_input_sync_abort(s->generation);
    /* Abort a connecting socket, but let an established transport send releases. */
    if (s->instance && !atomic_load(&s->connected)) freerdp_abort_connect_context(s->instance->context);
    if (s->wake_event) SetEvent(s->wake_event);
    if (s->thread_started) pthread_join(s->thread, NULL);
    if (s->instance) freerdp_client_context_free(s->instance->context);
    if (s->wake_event) CloseHandle(s->wake_event);
    pthread_mutex_destroy(&s->channel_mutex);
    pthread_mutex_destroy(&s->mutex);
    rdpsync_transfer_free(s->transfer);
    rdpsync_drive_free(s->drive);
    free(s->fb_pixels); free(s);
}
static int enqueue_input_seq(InputCommand command, uint64_t sync_seq, int64_t* out_gen) {
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    int result = 0;
    if (!s || !atomic_load(&s->connected) || atomic_load(&s->terminating)) {
        pthread_mutex_unlock(&g_lock); return -1;
    }
    pthread_mutex_lock(&s->mutex);
    if (out_gen) *out_gen = s->generation;
    if (command.kind == INPUT_TOUCH && !s->touch_available) { result = -2; goto unlock; }
    /* Only consecutive unsynchronized moves for the same contact may be merged. */
    if (!sync_seq && s->command_count &&
        (command.kind == INPUT_MOVE || (command.kind == INPUT_TOUCH && command.action == 1))) {
        const size_t prev_slot = (s->command_head + s->command_count - 1) % INPUT_CAPACITY;
        InputCommand* last = &s->commands[prev_slot];
        if (!s->command_sync_seq[prev_slot] && last->kind == command.kind &&
            last->code == command.code && last->action == command.action) {
            *last = command; goto unlock;
        }
    }
    if (s->command_count == INPUT_CAPACITY) {
        ++s->input_drops;
        result = -5;
        /* Nonblocking backpressure: Kotlin / sync callers retry on IO without g_lock.
         * Unretried fire-and-forget edges fail closed so a remote key cannot stay held. */
        if (!sync_seq && command.kind != INPUT_UNICODE && command.kind != INPUT_UBUNTU_HEX) {
            atomic_store(&s->terminating, 1);
            snprintf(s->last_error, sizeof(s->last_error), "输入队列过载，会话已停止");
            SetEvent(s->wake_event);
            notify_input_sync_abort(s->generation);
        }
        goto unlock;
    }
    const size_t tail = (s->command_head + s->command_count) % INPUT_CAPACITY;
    s->commands[tail] = command;
    s->command_sync_seq[tail] = sync_seq;
    ++s->command_count;
    SetEvent(s->wake_event);
unlock:
    pthread_mutex_unlock(&s->mutex);
    pthread_mutex_unlock(&g_lock);
    return result;
}
static int enqueue_input(InputCommand command) {
    return enqueue_input_seq(command, 0, NULL);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect6(JNIEnv*,jobject,jstring,jint,jstring,jstring,jstring,jstring,jint,jint,jboolean,jboolean,jstring,jboolean);
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
        for (int profile = 0; profile < 5 && result; ++profile) {
            const int compatibility = (profile == 1 || profile == 3);
            const BOOL transfer_enabled = (profile >= 2);
            const BOOL text_enabled = !compatibility && (profile != 4);
            failed = "诊断会话内存";
            RdpSession* probe = calloc(1, sizeof(RdpSession));
            if (!probe) { result = 0; break; }
            probe->file_transfer_enabled = transfer_enabled;
            probe->text_bridge_enabled = text_enabled;
            pthread_mutex_init(&probe->mutex, NULL);
            pthread_mutex_init(&probe->channel_mutex, NULL);
            atomic_init(&probe->connected, 0); atomic_init(&probe->terminating, 0);
            failed = "FreeRDP 上下文";
            freerdp* instance = rdpsync_create_instance(probe, client_ctx_new);
            probe->instance = instance;
            result = instance != NULL;
            const char* invariant = instance ? certificate_context_failure(instance, probe, 0) : NULL;
            if (invariant) { failed = invariant; result = 0; }
            if (result) {
                failed = "安全设置";
                result = configure_security(instance->context->settings);
                invariant = certificate_context_failure(instance, probe, 1);
                if (invariant) { failed = invariant; result = 0; }
            }
            if (result) {
                failed = "通道设置";
                result = configure_session_profile(instance->context->settings, 1280, 720, TRUE, compatibility) &&
                    configure_clipboard_profile(instance->context->settings, transfer_enabled, text_enabled);
                invariant = certificate_context_failure(instance, probe, 1);
                if (invariant) { failed = invariant; result = 0; }
            }
            if (result) {
                failed = compatibility ? "兼容模式通道加载" : "完整模式通道加载";
                result = instance->LoadChannels && instance->LoadChannels(instance);
                invariant = certificate_context_failure(instance, probe, 1);
                if (invariant) { failed = invariant; result = 0; }
            }
            if (instance) {
                /* Terminate loaded addins; no socket or authentication exists. */
                (void)freerdp_disconnect(instance);
                freerdp_client_context_free(instance->context);
            }
            pthread_mutex_destroy(&probe->channel_mutex);
            pthread_mutex_destroy(&probe->mutex);
            free(probe->fb_pixels);
            free(probe);
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
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect7(
    JNIEnv* env, jobject obj, jstring jhost, jint port, jstring juser, jstring jpassword,
    jstring jdomain, jstring jserver_name, jint width, jint height, jboolean touch, jboolean compatibility,
    jstring jexpected_fingerprint, jboolean transfer_enabled, jint shared_space_mode) {
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
    if (shared_space_mode < 0 || shared_space_mode > 2) return 0;
    uint32_t workspace_status = 0;
    RdpWorkspace* workspace = shared_space_mode ? active_drive_workspace_require(env, &workspace_status) : NULL;
    if (shared_space_mode && !workspace) {
        pthread_mutex_lock(&g_lock);
        snprintf(g_startup_error, sizeof(g_startup_error), "共享空间初始化失败 [0x%08x]", workspace_status);
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
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
    if (shared_space_mode) {
        failure_stage = "共享空间会话";
        session->drive = rdpsync_drive_new(workspace, session->generation, shared_space_mode, workspace_pattern_key, NULL);
        if (!session->drive) { session_destroy(session); goto failure; }
    }
    failure_stage = "文件传输会话";
    session->file_transfer_enabled = transfer_enabled;
    const int tb_override = atomic_exchange(&g_next_text_bridge, -1);
    session->text_bridge_enabled = !compatibility && (tb_override != 0);
    const BOOL cliprdr_active = session->file_transfer_enabled || session->text_bridge_enabled;
    session->transfer = rdpsync_transfer_new(session->generation, cliprdr_active);
    if (!session->transfer) { session_destroy(session); goto failure; }
    rdpsync_transfer_set_text_only(session->transfer, !session->file_transfer_enabled && session->text_bridge_enabled);
    failure_stage = "唤醒事件";
    session->wake_event = CreateEvent(NULL, TRUE, FALSE, NULL);
    if (!session->wake_event) { session_destroy(session); goto failure; }
    rdpsync_drive_set_wakeup(session->drive, drive_wake_owner, session);
    failure_stage = "FreeRDP 上下文";
    session->instance = rdpsync_create_instance(session, client_ctx_new);
    if (!session->instance) { session_destroy(session); goto failure; }
    const char* invariant = certificate_context_failure(session->instance, session, 0);
    if (invariant) { failure_stage = invariant; session_destroy(session); goto failure; }
    s_diag(session, "certificate=allocation-binding-ready");
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
    invariant = certificate_context_failure(session->instance, session, 1);
    if (invariant) { failure_stage = invariant; session_destroy(session); goto failure; }
    s_diag(session, "certificate=security-settings-ready");
    failure_stage = "显示与通道设置";
    if (!configure_session_profile(settings, width, height, touch, compatibility) ||
        !configure_clipboard_profile(settings, session->file_transfer_enabled, session->text_bridge_enabled) ||
        !rdpsync_drive_configure(session->drive, session->instance->context)) {
        session_destroy(session); goto failure;
    }
    configure_performance_profile(session, settings, compatibility);
    invariant = certificate_context_failure(session->instance, session, 1);
    if (invariant) { failure_stage = invariant; session_destroy(session); goto failure; }
    /* Preserve earlier initialization milestones instead of overwriting them. */
    s_diag(session, "engine=FreeRDP " FREERDP_VERSION_FULL);
    s_diag(session, session->hw_h264_active ? "renderer=MediaCodec H.264 + GDI" : "renderer=RemoteFX Progressive + GDI");
    s_diag(session, "certificate=profile-settings-ready");
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
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect6(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jstring n,jint w,jint hh,jboolean t,jboolean compat,jstring pin,jboolean transfer) {
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect7(e,o,h,p,u,pw,d,n,w,hh,t,compat,pin,transfer,0);
}
JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeConnect5(JNIEnv* e,jobject o,jstring h,jint p,jstring u,jstring pw,jstring d,jstring n,jint w,jint hh,jboolean t,jboolean compat,jstring pin) {
    return Java_com_rdp_sync_network_RdpConnector_nativeConnect6(e,o,h,p,u,pw,d,n,w,hh,t,compat,pin,JNI_FALSE);
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
                 "rtt-ms=%lld\nbandwidth-kbps=%lld\ninput-queue-drops=%lld\n"
                 "gfx=%s\ngfx-last-codec=%d\ngfx-decoded-commands=%lld\n",
                 s->diag, s->touch_available ? "connected" : "not-negotiated", s->disp_state,
                 s->fb_width, s->fb_height, (long long)s->rtt_ms, (long long)s->bandwidth_kbps,
                 (long long)s->input_drops, s->gfx_ready ? "software-ready" : "not-negotiated",
                 s->gfx_last_codec, (long long)s->gfx_commands);
        pthread_mutex_unlock(&s->mutex);
        const size_t used = strlen(text);
        rdpsync_drive_diagnostics(s->drive, text + used, sizeof(text) - used);
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
JNIEXPORT void JNICALL Java_com_rdp_sync_network_RdpConnector_nativeEnableNextTextBridge(JNIEnv* e, jobject o, jboolean enabled) {
    (void)e; (void)o;
    atomic_store(&g_next_text_bridge, enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeGetPeerOsMajor(JNIEnv* e, jobject o) {
    (void)e; (void)o;
    int os_major = 0;
    RdpTransfer* transfer = NULL;
    pthread_mutex_lock(&g_lock);
    if (g_session) {
        pthread_mutex_lock(&g_session->mutex);
        os_major = g_session->peer_os_major;
        transfer = g_session->transfer;
        pthread_mutex_unlock(&g_session->mutex);
        if (os_major != 0x0004 && rdpsync_transfer_is_unix_peer(transfer)) {
            os_major = 0x0004;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return (jint)os_major;
}

static int is_session_generation_alive(int64_t gen) {
    if (gen <= 0) return 0;
    pthread_mutex_lock(&g_lock);
    const RdpSession* s = g_session;
    const int alive = s && s->generation == gen &&
        atomic_load(&s->connected) && !atomic_load(&s->terminating);
    pthread_mutex_unlock(&g_lock);
    return alive;
}

static int sleep_session_alive_ms(int64_t gen, int total_ms) {
    int remaining = total_ms;
    while (remaining > 0) {
        if (!is_session_generation_alive(gen)) return -1;
        const int step = remaining > 20 ? 20 : remaining;
        struct timespec ts = {0, (long)step * 1000000L};
        nanosleep(&ts, NULL);
        remaining -= step;
    }
    return is_session_generation_alive(gen) ? 0 : -1;
}

static int current_ubuntu_post_commit_settle_ms(int64_t* out_gen) {
    int settle_ms = 90;
    int64_t gen = 0;
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (s && atomic_load(&s->connected) && !atomic_load(&s->terminating)) {
        pthread_mutex_lock(&s->mutex);
        gen = s->generation;
        if (s->rtt_ms > 0) {
            int extra = (int)(s->rtt_ms / 3);
            if (extra > 50) extra = 50;
            settle_ms += extra;
        }
        pthread_mutex_unlock(&s->mutex);
    }
    pthread_mutex_unlock(&g_lock);
    if (out_gen) *out_gen = gen;
    return settle_ms;
}

static int enqueue_input_with_retry(InputCommand cmd) {
    const int64_t deadline = monotonic_us() + 2000000LL;
    int rc;
    do {
        rc = enqueue_input(cmd);
        if (rc == -5) {
            struct timespec ts = {0, 4000000L};
            nanosleep(&ts, NULL);
        }
    } while (rc == -5 && monotonic_us() < deadline);
    return rc;
}

/* Enqueues a single InputCommand and waits WITHOUT holding g_lock until the protocol
 * owner thread pops and transmits it on the wire via execute_input(). Prevents multi-char
 * ISO-14755 commands from coalescing into a 0ms FastPath burst inside s->commands[]. */
static int enqueue_input_sync(InputCommand cmd, int timeout_ms) {
    pthread_once(&g_input_sync_once, init_input_sync_once);
    pthread_mutex_lock(&g_input_sync_lock);
    const uint64_t seq = ++g_input_next_seq;
    pthread_mutex_unlock(&g_input_sync_lock);

    const int64_t deadline_us = monotonic_us() + (int64_t)timeout_ms * 1000LL;
    int64_t gen = 0;
    int rc;
    do {
        rc = enqueue_input_seq(cmd, seq, &gen);
        if (rc == -5) {
            struct timespec ts = {0, 4000000L};
            nanosleep(&ts, NULL);
        }
    } while (rc == -5 && monotonic_us() < deadline_us);
    if (rc != 0) return rc;

    pthread_mutex_lock(&g_input_sync_lock);
    while (g_input_done_seq < seq && g_input_aborted_gen < gen) {
        if (monotonic_us() >= deadline_us) {
            pthread_mutex_unlock(&g_input_sync_lock);
            return -1;
        }
        struct timespec wait_ts;
#ifdef CLOCK_MONOTONIC
        clock_gettime(CLOCK_MONOTONIC, &wait_ts);
#else
        clock_gettime(CLOCK_REALTIME, &wait_ts);
#endif
        wait_ts.tv_nsec += 25000000L; /* 25ms liveness slice */
        if (wait_ts.tv_nsec >= 1000000000L) {
            wait_ts.tv_sec += 1;
            wait_ts.tv_nsec -= 1000000000L;
        }
        (void)pthread_cond_timedwait(&g_input_sync_cond, &g_input_sync_lock, &wait_ts);
        if (g_input_done_seq < seq && !is_session_generation_alive(gen)) {
            pthread_mutex_unlock(&g_input_sync_lock);
            return -1;
        }
    }
    const int ok = (g_input_done_seq >= seq && g_input_failed_seq != seq) ? 0 : -1;
    pthread_mutex_unlock(&g_input_sync_lock);
    return ok;
}

/* Wire-synchronized 3-phase ISO-14755 state machine for Ubuntu GNOME Wayland + IBus + Firefox:
 *   Phase 1: LCtrl↓, LShift↓ -> U↓, U↑ -> LShift↑, LCtrl↑ + 14ms async D-Bus hex-mode latch
 *            (guarantees in_hex_sequence=TRUE, modifiers_dropped=TRUE while n_compose==0,
 *             and drops Ctrl before hex digits so 'c'/'d' can never trigger Ctrl+C/Ctrl+D).
 *   Phase 2: Unmodified hex digits 0-9/a-f paced individually after wire send (3ms down, 4ms up).
 *   Phase 3: Space↓/Space↑ commit + RTT-adaptive 90..140ms post-commit wire-idle settle so
 *            Mutter's CLUTTER_PRIORITY_EVENTS+1 done_idle_cb flushes zwp_text_input_v3.done
 *            and Firefox e10s OnSelectionChange -> ResetIME() completes before the next char. */
static int send_ubuntu_hex_codepoint(uint32_t cp) {
    if (cp == 0 || cp > 0x10FFFFU || (cp >= 0xD800U && cp <= 0xDFFFU)) return -3;
    char hex[8];
    const int len = snprintf(hex, sizeof(hex), "%x", cp);
    if (len <= 0 || len >= (int)sizeof(hex)) return -3;

    int64_t gen = 0;
    const int post_settle_ms = current_ubuntu_post_commit_settle_ms(&gen);
    if (gen <= 0) return -1;

    int ctrl_held = 0, shift_held = 0;
    int rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1D, 1}, 2000); /* LCtrl down */
    if (!rc) { ctrl_held = 1; rc = sleep_session_alive_ms(gen, 2); }
    if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2A, 1}, 2000); /* LShift down */
    if (!rc) { shift_held = 1; rc = sleep_session_alive_ms(gen, 3); }
    if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x16, 1}, 2000); /* U down */
    if (!rc) rc = sleep_session_alive_ms(gen, 4);
    if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x16, 0}, 2000); /* U up */
    if (!rc) rc = sleep_session_alive_ms(gen, 3);
    if (!rc) {
        rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2A, 0}, 2000); /* LShift up */
        if (!rc) shift_held = 0;
    }
    if (!rc) rc = sleep_session_alive_ms(gen, 2);
    if (!rc) {
        rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1D, 0}, 2000); /* LCtrl up */
        if (!rc) ctrl_held = 0;
    }
    if (rc != 0) {
        if (shift_held) (void)enqueue_input((InputCommand){INPUT_KEY, 0, 0, 0x2A, 0});
        if (ctrl_held) (void)enqueue_input((InputCommand){INPUT_KEY, 0, 0, 0x1D, 0});
        return rc;
    }
    /* Wait 14ms for gnome-shell -> ibus-daemon async D-Bus to latch modifiers_dropped=TRUE */
    rc = sleep_session_alive_ms(gen, 14);
    if (rc != 0) return rc;

    for (int i = 0; i < len; ++i) {
        const unsigned nib = (hex[i] >= '0' && hex[i] <= '9')
            ? (unsigned)(hex[i] - '0') : (unsigned)(hex[i] - 'a' + 10);
        const UINT8 sc = rdpsync_hex_nibble_scancode(nib);
        rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, sc, 1}, 2000);
        if (!rc) rc = sleep_session_alive_ms(gen, 3);
        if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, sc, 0}, 2000);
        if (!rc) rc = sleep_session_alive_ms(gen, 4);
        if (rc != 0) return rc;
    }

    rc = sleep_session_alive_ms(gen, 4);
    if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x39, 1}, 2000); /* Space down */
    if (!rc) rc = sleep_session_alive_ms(gen, 4);
    if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x39, 0}, 2000); /* Space up */
    if (!rc) rc = sleep_session_alive_ms(gen, post_settle_ms);
    return rc;
}

static int send_ubuntu_hex_stream(const uint8_t* utf8, size_t len, int all_printable_hex) {
    size_t idx = 0;
    uint32_t prev = 0;
    while (idx < len) {
        uint32_t cp = 0;
        if (rdpsync_utf8_next(utf8, len, &idx, &cp) <= 0) return -3;
        if (cp == '\n' && prev == '\r') { prev = cp; continue; }
        prev = cp;
        int64_t gen = 0;
        (void)current_ubuntu_post_commit_settle_ms(&gen);
        if (gen <= 0) return -1;
        int rc = 0;
        if (cp == '\n' || cp == '\r') {
            rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1C, 1}, 2000);
            if (!rc) rc = sleep_session_alive_ms(gen, 3);
            if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1C, 0}, 2000);
            if (!rc) rc = sleep_session_alive_ms(gen, 45);
        } else if (cp == '\t') {
            rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x0F, 1}, 2000);
            if (!rc) rc = sleep_session_alive_ms(gen, 3);
            if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x0F, 0}, 2000);
            if (!rc) rc = sleep_session_alive_ms(gen, 35);
        } else if (cp >= 0x20U && (all_printable_hex || cp >= 0x80U)) {
            rc = send_ubuntu_hex_codepoint(cp);
        } else if (cp >= 0x20U) {
            rc = enqueue_input_sync((InputCommand){INPUT_UNICODE, 0, 0, (int)cp, 0}, 2000);
            if (!rc) rc = sleep_session_alive_ms(gen, 12);
        }
        if (rc != 0) return rc;
    }
    return 0;
}

static int send_ubuntu_hex_fallback(const uint8_t* utf8, size_t len) {
    return send_ubuntu_hex_stream(utf8, len, 1);
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendUbuntuText(
    JNIEnv* e, jobject o, jstring text, jint mode
) {
    (void)o;
    if (!text) return -3;
    const char* raw = (*e)->GetStringUTFChars(e, text, NULL);
    if (!raw) return -3;
    const size_t len = strlen(raw);
    if (len == 0) {
        (*e)->ReleaseStringUTFChars(e, text, raw);
        return 0;
    }

    /* Serialize ALL outbound Ubuntu/CLIPRDR/ISO-14755 text commits end-to-end so rapid
     * consecutive Chinese IME commits never interleave keystrokes or overwrite UTF-16LE bytes. */
    pthread_mutex_lock(&g_ubuntu_text_lock);

    if (mode == RDPSYNC_LINUX_TEXT_IBUS_HEX) {
        const int rc = send_ubuntu_hex_fallback((const uint8_t*)raw, len);
        pthread_mutex_unlock(&g_ubuntu_text_lock);
        (*e)->ReleaseStringUTFChars(e, text, raw);
        return rc;
    }

    RdpTransfer* transfer = NULL;
    HANDLE wake = NULL;
    int is_unix = 0;
    pthread_mutex_lock(&g_lock);
    RdpSession* s = g_session;
    if (s && atomic_load(&s->connected) && !atomic_load(&s->terminating)) {
        transfer = s->transfer;
        wake = s->wake_event;
        pthread_mutex_lock(&s->mutex);
        is_unix = (s->peer_os_major == 0x0004);
        pthread_mutex_unlock(&s->mutex);
        if (!is_unix && rdpsync_transfer_is_unix_peer(transfer)) is_unix = 1;
    }
    pthread_mutex_unlock(&g_lock);

    /* Wait briefly if CLIPRDR MonitorReady is still completing right after logon. */
    for (int wait_ready = 0; transfer && !rdpsync_transfer_is_ready(transfer) && wait_ready < 20; ++wait_ready) {
        struct timespec rts = {0, 15000000L}; /* 15ms */
        nanosleep(&rts, NULL);
    }
    if (!is_unix && rdpsync_transfer_is_unix_peer(transfer)) is_unix = 1;
    const int is_xrdp = rdpsync_transfer_is_xrdp_peer(transfer);

    /* Ubuntu 24.04 GNOME Remote Desktop 46.x + WinPR 3.32.2 escapes CF_UNICODETEXT via
     * winpr_utf8ToUtfEscapedString ("text/plain" -> "\u767e\u5ea6"). On non-xrdp Unix
     * peers (GNOME Remote Desktop) in SMART_AUTO / Send-Text modes, deliver directly via
     * wire-synchronized 3-phase ISO-14755 (Ctrl+Shift+U <hex> Space) so multi-character
     * Chinese ("百度", sentences, mixed English/punctuation) arrives 100% in order without
     * Wayland done_idle_cb / Firefox e10s ResetIME() races and without touching clipboard. */
    const int prefer_gnome_iso14755 = is_unix && !is_xrdp &&
        (mode == RDPSYNC_LINUX_TEXT_GUI_PASTE || mode == RDPSYNC_LINUX_TEXT_UNIVERSAL_PASTE);

    uint64_t seq = (!prefer_gnome_iso14755 && transfer)
        ? rdpsync_transfer_offer_text(transfer, (const uint8_t*)raw, len) : 0;
    if (seq > 0 && wake) {
        SetEvent(wake);
        /* CRITICAL: Only inject Ctrl+V / Ctrl+Shift+V AFTER the remote server confirms
         * CB_FORMAT_LIST_RESPONSE(CB_RESPONSE_OK) for this exact text offer sequence.
         * Never fire blind Ctrl+V if CLIPRDR rejected or timed out, preventing stale
         * remote clipboard contents from ever being pasted! */
        if (rdpsync_transfer_wait_text_ack(transfer, seq, 1500) == 0) {
            struct timespec settle = {0, 28000000L}; /* 28ms X11/Wayland selection ownership settle */
            nanosleep(&settle, NULL);
            struct timespec key_gap = {0, 6000000L}; /* 6ms */
            int rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1D, 1}, 2000); /* LCtrl down */
            if (!rc && mode == RDPSYNC_LINUX_TEXT_TERM_PASTE) {
                rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2A, 1}, 2000); /* LShift down */
            }
            nanosleep(&key_gap, NULL);
            if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2F, 1}, 2000); /* V down */
            nanosleep(&key_gap, NULL);
            if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2F, 0}, 2000); /* V up */
            nanosleep(&key_gap, NULL);
            if (!rc && mode == RDPSYNC_LINUX_TEXT_TERM_PASTE) {
                rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x2A, 0}, 2000); /* LShift up */
            }
            if (!rc) rc = enqueue_input_sync((InputCommand){INPUT_KEY, 0, 0, 0x1D, 0}, 2000); /* LCtrl up */
            if (!rc) {
                (void)rdpsync_transfer_wait_text_served(transfer, seq, 1000);
                struct timespec post_paste = {0, 12000000L}; /* 12ms widget commit settle */
                nanosleep(&post_paste, NULL);
            }
            pthread_mutex_unlock(&g_ubuntu_text_lock);
            (*e)->ReleaseStringUTFChars(e, text, raw);
            return rc;
        }
    }

    if (!is_unix && mode == RDPSYNC_LINUX_TEXT_UNIVERSAL_PASTE) {
        /* Windows fallback when CLIPRDR is disabled on the remote host */
        size_t idx = 0;
        int rc = 0;
        while (idx < len && !rc) {
            uint32_t cp = 0;
            if (rdpsync_utf8_next((const uint8_t*)raw, len, &idx, &cp) <= 0) { rc = -3; break; }
            if (cp == '\n' || cp == '\r') {
                rc = enqueue_input_with_retry((InputCommand){INPUT_KEY, 0, 0, 0x1C, 1});
                if (!rc) rc = enqueue_input_with_retry((InputCommand){INPUT_KEY, 0, 0, 0x1C, 0});
            } else if (cp <= 0xFFFFU) {
                rc = enqueue_input_with_retry((InputCommand){INPUT_UNICODE, 0, 0, (int)cp, 0});
            } else {
                const uint32_t v = cp - 0x10000U;
                rc = enqueue_input_with_retry((InputCommand){INPUT_UNICODE, 0, 0, (int)(0xD800U + (v >> 10)), 0});
                if (!rc) rc = enqueue_input_with_retry((InputCommand){INPUT_UNICODE, 0, 0, (int)(0xDC00U + (v & 0x3FFU)), 0});
            }
        }
        pthread_mutex_unlock(&g_ubuntu_text_lock);
        (*e)->ReleaseStringUTFChars(e, text, raw);
        return rc;
    }

    /* Automatic zero-channel ISO-14755 Ctrl+Shift+U fallback on Ubuntu when CLIPRDR is disabled */
    const int fallback_rc = send_ubuntu_hex_fallback((const uint8_t*)raw, len);
    pthread_mutex_unlock(&g_ubuntu_text_lock);
    (*e)->ReleaseStringUTFChars(e, text, raw);
    return fallback_rc;
}

JNIEXPORT jint JNICALL Java_com_rdp_sync_network_RdpConnector_nativeSendClipboardText(JNIEnv* e, jobject o, jstring text) {
    return Java_com_rdp_sync_network_RdpConnector_nativeSendUbuntuText(e, o, text, RDPSYNC_LINUX_TEXT_GUI_PASTE);
}

#include "rdpsync_transfer_jni.inc"
