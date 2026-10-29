/* No private credentials leave this process: test-only key/certs are generated
 * in memory, and the production X509 trust callback is executed directly. */
#define _GNU_SOURCE
#include <assert.h>
#include <ctype.h>
#include "../../main/cpp/freerdp_bridge.c"

typedef struct { char* values[8]; } ChallengeArray;
static jclass JNICALL fake_find_class(JNIEnv* env, const char* name) { (void)env; assert(!strcmp(name, "java/lang/String")); return (jclass)(uintptr_t)1; }
static jobjectArray JNICALL fake_new_array(JNIEnv* env, jsize count, jclass klass, jobject initial) {
    (void)env; (void)klass; (void)initial; assert(count == 8); return (jobjectArray)calloc(1, sizeof(ChallengeArray));
}
static jstring JNICALL fake_new_string(JNIEnv* env, const char* text) { (void)env; return (jstring)strdup(text); }
static void JNICALL fake_set_element(JNIEnv* env, jobjectArray array, jsize index, jobject value) {
    (void)env; ((ChallengeArray*)array)->values[index] = strdup((char*)value);
}
static void JNICALL fake_delete_ref(JNIEnv* env, jobject value) { (void)env; if ((uintptr_t)value > 1) free(value); }
static jboolean JNICALL fake_exception(JNIEnv* env) { (void)env; return JNI_FALSE; }
static const struct JNINativeInterface_ challenge_jni = {
    .FindClass = fake_find_class, .NewObjectArray = fake_new_array, .NewStringUTF = fake_new_string,
    .SetObjectArrayElement = fake_set_element, .DeleteLocalRef = fake_delete_ref, .ExceptionCheck = fake_exception
};
static int boolean_settings[8192];
static UINT32 authentication_level;
BOOL freerdp_settings_get_bool(const rdpSettings* settings, FreeRDP_Settings_Keys_Bool key) {
    (void)settings; assert(key < 8192); return boolean_settings[key];
}
BOOL freerdp_settings_set_bool(rdpSettings* settings, FreeRDP_Settings_Keys_Bool key, BOOL value) {
    (void)settings; assert(key < 8192); boolean_settings[key] = value; return TRUE;
}
BOOL freerdp_settings_set_uint32(rdpSettings* settings, FreeRDP_Settings_Keys_UInt32 key, UINT32 value) {
    (void)settings;
    if (key == FreeRDP_AuthenticationLevel) authentication_level = value;
    else assert(value == 0); /* accepted certificate caches must start empty */
    return TRUE;
}
BOOL freerdp_settings_set_string(rdpSettings* settings, FreeRDP_Settings_Keys_String key, const char* value) {
    (void)settings; assert(key == FreeRDP_CertificateAcceptedFingerprints && value == NULL); return TRUE;
}
static char* make_certificate(EVP_PKEY* key, long serial, size_t* size) {
    X509* certificate = X509_new(); assert(certificate);
    assert(X509_set_version(certificate, 2));
    assert(ASN1_INTEGER_set(X509_get_serialNumber(certificate), serial));
    assert(X509_gmtime_adj(X509_getm_notBefore(certificate), -60));
    assert(X509_gmtime_adj(X509_getm_notAfter(certificate), 3600));
    assert(X509_set_pubkey(certificate, key));
    X509_NAME* subject = X509_get_subject_name(certificate);
    assert(X509_NAME_add_entry_by_txt(subject, "CN", MBSTRING_ASC,
        (const unsigned char*)"disposable.example", -1, -1, 0));
    assert(X509_set_issuer_name(certificate, subject));
    assert(X509_sign(certificate, key, EVP_sha256()));
    BIO* bio = BIO_new(BIO_s_mem()); assert(bio);
    assert(PEM_write_bio_X509(bio, certificate));
    char* buffer = NULL; long length = BIO_get_mem_data(bio, &buffer); assert(length > 0);
    char* pem = malloc((size_t)length + 1); assert(pem);
    memcpy(pem, buffer, (size_t)length); pem[length] = 0; *size = (size_t)length;
    BIO_free(bio); X509_free(certificate); return pem;
}
static void expect_pin(RdpSession* session, const char* pin) {
    session->expected_pin_state = rdpsync_normalize_pin(pin, session->expected_fingerprint);
}
int main(void) {
    char normalized[65];
    assert(rdpsync_normalize_pin(NULL, normalized) == 0);
    assert(rdpsync_normalize_pin("", normalized) == 0);
    assert(rdpsync_normalize_pin("aa:bb", normalized) == -1);
    assert(rdpsync_normalize_pin("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaag", normalized) == -1);
    assert(rdpsync_normalize_pin("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", normalized) == 1);
    assert(strcmp(normalized, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") == 0);
    RdpCertificateInfo info;
    assert(!rdpsync_certificate_info((const BYTE*)"not a certificate", 17, &info));
    assert(!rdpsync_certificate_info((const BYTE*)"short", 1024U * 1024U + 1, &info));
    EVP_PKEY* key = EVP_PKEY_Q_keygen(NULL, NULL, "EC", "prime256v1"); assert(key);
    size_t size1, size2; char* pem1 = make_certificate(key, 1, &size1); char* pem2 = make_certificate(key, 2, &size2);
    assert(rdpsync_certificate_info((const BYTE*)pem1, size1, &info));
    assert(strlen(info.fingerprint) == 64 && strstr(info.subject, "disposable.example"));
    char trusted_pin[65]; strcpy(trusted_pin, info.fingerprint);
    /* PEM whitespace is not identity: the parsed leaf DER remains the same. */
    char* leading_newline = malloc(size1 + 2); assert(leading_newline);
    leading_newline[0] = '\n'; memcpy(leading_newline + 1, pem1, size1 + 1);
    RdpCertificateInfo same; assert(rdpsync_certificate_info((const BYTE*)leading_newline, size1 + 1, &same));
    assert(strcmp(info.fingerprint, same.fingerprint) == 0);
    RdpSession session = {0}; pthread_mutex_init(&session.mutex, NULL); atomic_init(&session.terminating, 0);
    session.generation = 42; strcpy(session.endpoint_host, "127.0.0.1"); strcpy(session.endpoint_name, "disposable.example"); session.endpoint_port = 3389;
    RdpSyncContext context = {0}; freerdp instance = {0};
    context.session = &session; instance.context = &context.common.context;
    assert(!strcmp(certificate_context_failure(&instance, &session, 0), "证书回调绑定"));
    instance.VerifyX509Certificate = verify_x509_certificate;
    assert(certificate_context_failure(&instance, &session, 0) == NULL);
    assert(!strcmp(certificate_context_failure(&instance, &session, 1), "外部证书管理设置"));
    boolean_settings[FreeRDP_ExternalCertificateManagement] = TRUE;
    assert(certificate_context_failure(&instance, &session, 1) == NULL);
    assert(!strcmp(certificate_context_failure(&instance, NULL, 1), "证书会话绑定"));
    expect_pin(&session, "");
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, 0) == 0);
    assert(session.certificate_challenge && !session.certificate_verified && !strcmp(session.certificate_reason, "untrusted"));
    assert(!strcmp(session.certificate.fingerprint, trusted_pin) && session.generation == 42);
    JNIEnv env = &challenge_jni; g_session = &session;
    ChallengeArray* challenge = (ChallengeArray*)Java_com_rdp_sync_network_RdpConnector_nativeGetCertificateChallenge(&env, NULL);
    assert(challenge && !strcmp(challenge->values[0], "42") && !strcmp(challenge->values[1], trusted_pin));
    assert(!strcmp(challenge->values[4], "127.0.0.1") && !strcmp(challenge->values[5], "3389"));
    assert(!strcmp(challenge->values[6], "disposable.example") && !strcmp(challenge->values[7], "untrusted"));
    strcpy(session.certificate.subject, "mutated after snapshot"); session.certificate_challenge = 0;
    assert(strstr(challenge->values[2], "disposable.example")); /* detached, immutable JNI snapshot */
    assert(Java_com_rdp_sync_network_RdpConnector_nativeGetCertificateChallenge(&env, NULL) == NULL);
    g_session = NULL;
    assert(Java_com_rdp_sync_network_RdpConnector_nativeGetCertificateChallenge(&env, NULL) == NULL);
    for (int i = 0; i < 8; ++i) free(challenge->values[i]); free(challenge);
    expect_pin(&session, trusted_pin);
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, 0) == 2);
    assert(session.certificate_verified && !session.certificate_challenge);
    char uppercase_pin[65]; for (int i = 0; i < 65; ++i) uppercase_pin[i] = (char)toupper((unsigned char)trusted_pin[i]);
    expect_pin(&session, uppercase_pin);
    assert(verify_x509_certificate(&instance, (const BYTE*)leading_newline, size1 + 1, "disposable.example", 3389, 0) == 2);
    assert(verify_x509_certificate(&instance, (const BYTE*)pem2, size2, "disposable.example", 3389, 0) == 0);
    assert(session.certificate_challenge && !session.certificate_verified && !strcmp(session.certificate_reason, "changed"));
    assert(strcmp(session.certificate.fingerprint, trusted_pin));
    expect_pin(&session, "broken persisted pin");
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, 0) == 0);
    assert(!strcmp(session.certificate_reason, "invalid-pin"));
    expect_pin(&session, trusted_pin);
    const DWORD denied_flags[] = {VERIFY_CERT_FLAG_REDIRECT, VERIFY_CERT_FLAG_GATEWAY, VERIFY_CERT_FLAG_LEGACY};
    for (size_t i = 0; i < sizeof(denied_flags) / sizeof(denied_flags[0]); ++i) {
        assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, denied_flags[i]) == 0);
        assert(!session.certificate_verified && !session.certificate_challenge);
    }
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "another.example", 3389, 0) == 0);
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3390, 0) == 0);
    assert(verify_x509_certificate(&instance, (const BYTE*)"bogus", 5, "disposable.example", 3389, 0) == 0);
    assert(!session.certificate_challenge && !session.certificate_verified);
    assert(reject_redirect(&instance) == FALSE);
    assert(authenticate_ex(&instance, NULL, NULL, NULL, AUTH_RDP) == FALSE);
    assert(configure_security(NULL));
    assert(boolean_settings[FreeRDP_ExternalCertificateManagement]);
    assert(!boolean_settings[FreeRDP_IgnoreCertificate] && !boolean_settings[FreeRDP_AutoAcceptCertificate]);
    assert(!boolean_settings[FreeRDP_RdpSecurity] && !boolean_settings[FreeRDP_UseRdpSecurityLayer]);
    assert(boolean_settings[FreeRDP_TlsSecurity] && boolean_settings[FreeRDP_NlaSecurity] && boolean_settings[FreeRDP_NegotiateSecurityLayer]);
    assert(authentication_level == 2);
    assert(!boolean_settings[FreeRDP_MstscCookieMode] && !boolean_settings[FreeRDP_SendPreconnectionPdu]);
    session.certificate_superseded = 1;
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, 0) == 0);
    assert(!session.certificate_challenge);
    session.certificate_superseded = 0;
    atomic_store(&session.terminating, 1);
    assert(verify_x509_certificate(&instance, (const BYTE*)pem1, size1, "disposable.example", 3389, 0) == 0);
    pthread_mutex_destroy(&session.mutex);
    free(leading_newline); free(pem1); free(pem2); EVP_PKEY_free(key);
    puts("native certificate: passed parsed DER SHA256, missing/matched/changed/malformed pins, endpoint/redirect rejection, TLS-only policy");
}
