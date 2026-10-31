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
#define BIO_TYPE_SIMPLE 66
typedef int64_t INT64;
static void transport_default_write(void) {}
static void transport_default_connect_tls(void) {}
static void transport_default_connect_layer(void) {}
static void transport_default_attach_layer(void) {}
static void transport_default_set_blocking_mode(void) {}
static void custom_callback(void) {}
typedef struct { SSL* ssl; BIO* bio; BIO* underlying; } ProbeTls;
typedef struct {
    BIO* frontBio; int layer, GatewayEnabled, WriteLock, blocking;
    struct { void (*SetBlockingMode)(void); void (*WritePdu)(void); void (*TLSConnect)(void); void (*ConnectLayer)(void); void (*AttachLayer)(void); } io;
    ProbeTls* tls;
} rdpTransport;
typedef struct { rdpTransport* transport; } ProbeRdp;
typedef struct { ProbeRdp* rdp; } rdpContext;
static int held, locks;
static void EnterCriticalSection(int* lock) { assert(!*lock && !held); *lock=1;held=1;locks++; }
static void LeaveCriticalSection(int* lock) { assert(*lock && held); *lock=0;held=0; }
// TCP validator seam: exact appended validator, real nonblocking fd flags.
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>
#define BOOL int
#define TRUE 1
#define FALSE 0
typedef struct {int sockfd; void* hEvent;} rdpTcpLayer;
typedef struct {void* userContext; void (*Read)(void);void (*Write)(void);void (*Close)(void);void (*Wait)(void);void (*GetEvent)(void);} rdpTransportLayer;
static void freerdp_tcp_layer_read(void){}
static void freerdp_tcp_layer_write(void){}
static void freerdp_tcp_layer_close(void){}
static void freerdp_tcp_layer_wait(void){}
static void freerdp_tcp_layer_get_event(void){}
BOOL rdpsync_freerdp_tcp_layer_nonblocking(const rdpTransportLayer*);
static long queued;
static int flushes, flush_result=1, flush_reduce, flush_grow;
static int bio_create(BIO* bio) { BIO_set_init(bio,1);return 1; }
static int bio_destroy(BIO* bio) { (void)bio;return 1; }
static long bio_ctrl(BIO* bio,int command,long argument,void* pointer) {(void)bio;(void)argument;(void)pointer;if(command==BIO_CTRL_FLUSH){assert(held);flushes++;if(flush_result==1){queued-=flush_reduce;queued+=flush_grow;}return flush_result;}if(command==BIO_CTRL_WPENDING){assert(held);return queued;}return 0;}
int rdpsync_freerdp_tcp_drain_buffered(BIO* buffered){return BIO_ctrl(buffered,BIO_CTRL_FLUSH,0,NULL)==1?0:-1;}
INT64 rdpsync_freerdp_pending_bytes(rdpContext*);
INT64 rdpsync_freerdp_drain_pending(rdpContext*);
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
    transport.layer=TRANSPORT_LAYER_TLS;
    transport.io.SetBlockingMode=transport_default_set_blocking_mode;
    BIO_METHOD* lower_method=BIO_meth_new(BIO_TYPE_SIMPLE,"TransportLayer");assert(lower_method);
    assert(BIO_meth_set_create(lower_method,bio_create)&&BIO_meth_set_destroy(lower_method,bio_destroy));
    BIO* lower=BIO_new(lower_method);assert(lower);BIO_push(buffered,lower);
    int sockets[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,sockets));
    assert(!fcntl(sockets[0],F_SETFL,fcntl(sockets[0],F_GETFL)|O_NONBLOCK));
    rdpTcpLayer tcp={.sockfd=sockets[0],.hEvent=&tcp};
    rdpTransportLayer layer={.userContext=&tcp,.Read=freerdp_tcp_layer_read,.Write=freerdp_tcp_layer_write,.Close=freerdp_tcp_layer_close,.Wait=freerdp_tcp_layer_wait,.GetEvent=freerdp_tcp_layer_get_event};
    BIO_set_data(lower,&layer);
    queued=123456;assert(rdpsync_freerdp_pending_bytes(&context)==queued && flushes==0);
    assert(rdpsync_freerdp_drain_pending(&context)==queued && flushes==1 && !held); /* EAGAIN, no credit */
    flush_reduce=123456;assert(rdpsync_freerdp_drain_pending(&context)==0 && flushes==2);flush_reduce=0;
    assert(rdpsync_freerdp_drain_pending(&context)==0 && flushes==2);
    queued=1;flush_result=-1;assert(rdpsync_freerdp_drain_pending(&context)==-3 && flushes==3);flush_result=0;
    assert(rdpsync_freerdp_drain_pending(&context)==-3 && flushes==4);flush_result=1;
    flush_grow=1;assert(rdpsync_freerdp_drain_pending(&context)==-3);flush_grow=0;
    queued=9*1024*1024;assert(rdpsync_freerdp_drain_pending(&context)==queued);
    flush_reduce=256*1024+1;assert(rdpsync_freerdp_drain_pending(&context)==-3);flush_reduce=0;int saved=flushes;queued=1;
    transport.blocking=1;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.blocking=0;
    transport.GatewayEnabled=1;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.GatewayEnabled=0;
    transport.io.SetBlockingMode=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.io.SetBlockingMode=transport_default_set_blocking_mode;
    transport.io.ConnectLayer=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.io.ConnectLayer=transport_default_connect_layer;
    transport.io.AttachLayer=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.io.AttachLayer=transport_default_attach_layer;
    transport.io.TLSConnect=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.io.TLSConnect=transport_default_connect_tls;
    transport.io.WritePdu=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);transport.io.WritePdu=transport_default_write;
    transport.layer=TRANSPORT_LAYER_CLOSED;assert(rdpsync_freerdp_drain_pending(&context)==-1);transport.layer=TRANSPORT_LAYER_TLS;
    BIO_push(lower,other);assert(rdpsync_freerdp_drain_pending(&context)==-2);BIO_pop(lower);
    BIO_pop(buffered);assert(rdpsync_freerdp_drain_pending(&context)==-2);
    BIO_METHOD* wrong_method=BIO_meth_new(BIO_TYPE_SIMPLE,"SimpleSocket");assert(wrong_method);BIO_meth_set_create(wrong_method,bio_create);BIO_meth_set_destroy(wrong_method,bio_destroy);
    BIO* wrong=BIO_new(wrong_method);BIO_push(buffered,wrong);assert(rdpsync_freerdp_drain_pending(&context)==-2);BIO_pop(buffered);BIO_free(wrong);BIO_meth_free(wrong_method);
    BIO_push(buffered,lower);
    BIO_set_data(lower,NULL);assert(rdpsync_freerdp_drain_pending(&context)==-2);BIO_set_data(lower,&layer);
    layer.Read=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.Read=freerdp_tcp_layer_read;
    layer.Write=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.Write=freerdp_tcp_layer_write;
    layer.Close=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.Close=freerdp_tcp_layer_close;
    layer.Wait=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.Wait=freerdp_tcp_layer_wait;
    layer.GetEvent=custom_callback;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.GetEvent=freerdp_tcp_layer_get_event;
    layer.userContext=NULL;assert(rdpsync_freerdp_drain_pending(&context)==-2);layer.userContext=&tcp;
    tcp.hEvent=NULL;assert(rdpsync_freerdp_drain_pending(&context)==-2);tcp.hEvent=&tcp;
    assert(!fcntl(sockets[0],F_SETFL,fcntl(sockets[0],F_GETFL)&~O_NONBLOCK));assert(rdpsync_freerdp_drain_pending(&context)==-2);
    close(sockets[0]);assert(rdpsync_freerdp_drain_pending(&context)==-2);tcp.sockfd=-1;assert(rdpsync_freerdp_drain_pending(&context)==-2);close(sockets[1]);BIO_pop(buffered);
    assert(flushes==saved && !held);BIO_free(lower);BIO_meth_free(lower_method);
    BIO_free(other);SSL_free(ssl);SSL_CTX_free(ssl_context);BIO_meth_free(method);
    puts("exact pending accessor guards: real OpenSSL BIO identity/pending, closed/gateway/custom-stack negatives and lock release PASS (isolated object shapes)");
}
