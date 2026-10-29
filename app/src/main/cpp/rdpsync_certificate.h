#ifndef RDPSYNC_CERTIFICATE_H
#define RDPSYNC_CERTIFICATE_H
#include <limits.h>
#include <stddef.h>
#include <string.h>
#include <openssl/bio.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>

typedef struct {
    char fingerprint[65]; /* SHA-256 of the leaf certificate DER, lowercase hex. */
    char subject[512];
    char issuer[512];
} RdpCertificateInfo;

/* 0=empty (not trusted), 1=canonical valid pin, -1=malformed. Never guess formats. */
static inline int rdpsync_normalize_pin(const char* input, char output[65]) {
    output[0] = 0;
    if (!input || !*input) return 0;
    if (strlen(input) != 64) return -1;
    for (size_t i = 0; i < 64; ++i) {
        unsigned char c = (unsigned char)input[i];
        if (c >= 'A' && c <= 'F') c = (unsigned char)(c - 'A' + 'a');
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) { output[0] = 0; return -1; }
        output[i] = (char)c;
    }
    output[64] = 0;
    return 1;
}
static inline void rdpsync_certificate_name(X509_NAME* name, char output[512]) {
    output[0] = 0;
    if (!name) return;
    BIO* bio = BIO_new(BIO_s_mem());
    if (!bio) return;
    if (X509_NAME_print_ex(bio, name, 0, XN_FLAG_RFC2253) >= 0) {
        char* bytes = NULL;
        const long length = BIO_get_mem_data(bio, &bytes);
        size_t count = length > 0 ? (size_t)length : 0;
        if (count > 511) count = 511;
        /* Untrusted certificate text is display-only; keep bounded ASCII for JNI
         * Modified UTF-8 and prevent controls/newlines from spoofing UI fields. */
        for (size_t i = 0; i < count; ++i)
            output[i] = (bytes[i] >= 32 && bytes[i] <= 126) ? bytes[i] : '?';
        output[count] = 0;
    }
    BIO_free(bio);
}
static inline int rdpsync_certificate_info(const unsigned char* pem, size_t length, RdpCertificateInfo* info) {
    if (!info) return 0;
    memset(info, 0, sizeof(*info));
    if (!pem || !length || length > 1024U * 1024U || length > INT_MAX) return 0;
    BIO* bio = BIO_new_mem_buf(pem, (int)length);
    if (!bio) return 0;
    /* FreeRDP passes the leaf first, followed by the optional PEM chain. The
     * identity is the parsed leaf DER, never a hash of arbitrary text/PEM bytes. */
    X509* certificate = PEM_read_bio_X509(bio, NULL, NULL, NULL);
    BIO_free(bio);
    if (!certificate) return 0;
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int digest_length = 0;
    int ok = X509_digest(certificate, EVP_sha256(), digest, &digest_length) == 1 && digest_length == 32;
    if (ok) {
        static const char hex[] = "0123456789abcdef";
        for (size_t i = 0; i < 32; ++i) {
            info->fingerprint[i * 2] = hex[digest[i] >> 4];
            info->fingerprint[i * 2 + 1] = hex[digest[i] & 15];
        }
        info->fingerprint[64] = 0;
        rdpsync_certificate_name(X509_get_subject_name(certificate), info->subject);
        rdpsync_certificate_name(X509_get_issuer_name(certificate), info->issuer);
    }
    X509_free(certificate);
    return ok;
}
#endif
