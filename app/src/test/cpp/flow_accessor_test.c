/* The test script appends the EXACT production accessor suffix. Minimal
 * FreeRDP object shapes isolate its fail-closed guards, with real OpenSSL BIO
 * identity/control operations. This is NOT the real-socket drain proof. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <openssl/ssl.h>
#define FREERDP_API
#define TRANSPORT_LAYER_CLOSED 0
#define TRANSPORT_LAYER_TLS 1
#define BIO_TYPE_BUFFERED 67
typedef int64_t INT64;
static void transport_default_write(void) {}
static void transport_default_connect_tls(void) {}
static void transport_default_connect_layer(void) {}
static void transport_default_attach_layer(void) {}
static void custom_callback(void) {}
typedef struct { SSL* ssl; BIO* bio; BIO* underlying; } ProbeTls;
typedef struct {
    BIO* frontBio; int layer, GatewayEnabled, WriteLock;
    struct { void (*WritePdu)(void); void (*TLSConnect)(void); void (*ConnectLayer)(void); void (*AttachLayer)(void); } io;
    ProbeTls* tls;
} rdpTransport;
typedef struct { rdpTransport* transport; } ProbeRdp;
typedef struct { ProbeRdp* rdp; } rdpContext;
static int held, locks;
static void EnterCriticalSection(int* lock) { assert(!*lock && !held); *lock=1;held=1;locks++; }
static void LeaveCriticalSection(int* lock) { assert(*lock && held); *lock=0;held=0; }
static long queued;
static int bio_create(BIO* bio) { BIO_set_init(bio,1);return 1; }
static int bio_destroy(BIO* bio) { (void)bio;return 1; }
static long bio_ctrl(BIO* bio,int command,long argument,void* pointer) {(void)bio;(void)argument;(void)pointer;if(command==BIO_CTRL_WPENDING){assert(held);return queued;}return 0;}
INT64 rdpsync_freerdp_pending_bytes(rdpContext*);
int main(void) {
    assert(rdpsync_freerdp_pending_bytes(NULL)==-1 && !locks);
    rdpContext context={0}; assert(rdpsync_freerdp_pending_bytes(&context)==-1);
    rdpTransport transport={0};ProbeRdp rdp={.transport=&transport};context.rdp=&rdp;
    assert(rdpsync_freerdp_pending_bytes(&context)==-1 && locks==1 && !held);
    BIO_METHOD* method=BIO_meth_new(BIO_TYPE_BUFFERED,"BufferedSocket");assert(method);
    assert(BIO_meth_set_create(method,bio_create)&&BIO_meth_set_destroy(method,bio_destroy)&&BIO_meth_set_ctrl(method,bio_ctrl));
    BIO* buffered=BIO_new(method);assert(buffered);
    SSL_CTX* ssl_context=SSL_CTX_new(TLS_client_method());assert(ssl_context);
    SSL* ssl=SSL_new(ssl_context);assert(ssl);SSL_set_bio(ssl,buffered,buffered);
    ProbeTls tls={.ssl=ssl,.bio=buffered,.underlying=buffered};transport.frontBio=buffered;transport.layer=TRANSPORT_LAYER_TLS;transport.tls=&tls;
    transport.io.WritePdu=transport_default_write;transport.io.TLSConnect=transport_default_connect_tls;
    transport.io.ConnectLayer=transport_default_connect_layer;transport.io.AttachLayer=transport_default_attach_layer;
    queued=0;assert(rdpsync_freerdp_pending_bytes(&context)==0 && !held);
    queued=123456;assert(rdpsync_freerdp_pending_bytes(&context)==123456 && !held);
    queued=-1;assert(rdpsync_freerdp_pending_bytes(&context)==-2 && !held);queued=0;
    transport.GatewayEnabled=1;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.GatewayEnabled=0;
    transport.io.WritePdu=custom_callback;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.io.WritePdu=transport_default_write;
    transport.io.TLSConnect=custom_callback;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.io.TLSConnect=transport_default_connect_tls;
    transport.io.ConnectLayer=custom_callback;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.io.ConnectLayer=transport_default_connect_layer;
    transport.io.AttachLayer=custom_callback;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.io.AttachLayer=transport_default_attach_layer;
    BIO* other=BIO_new(BIO_s_mem());assert(other);
    tls.underlying=other;assert(rdpsync_freerdp_pending_bytes(&context)==-2);tls.underlying=buffered;
    transport.frontBio=other;assert(rdpsync_freerdp_pending_bytes(&context)==-2);transport.frontBio=buffered;
    transport.layer=TRANSPORT_LAYER_CLOSED;assert(rdpsync_freerdp_pending_bytes(&context)==-1);assert(!held);
    BIO_free(other);SSL_free(ssl);SSL_CTX_free(ssl_context);BIO_meth_free(method);
    puts("exact pending accessor guards: real OpenSSL BIO identity/pending, closed/gateway/custom-stack negatives and lock release PASS (isolated object shapes)");
}
