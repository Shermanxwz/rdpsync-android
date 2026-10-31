#!/usr/bin/env python3
"""Exact pinned buffered BIO/ringbuffer + drain helper, real nonblocking socket.
The FreeRDP object shapes and WinPR syscall aliases are isolated seams, NOT TLS/RDP
integration. No synthetic pending counter replaces the actual buffered queue.
"""
import hashlib, runpy, subprocess, os, resource
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=Path(os.environ.get('RDPSYNC_DEPS_DIR',str(root/'build/freerdp-android')))/'freerdp-src'
patch=runpy.run_path(str(root/'scripts/patch-freerdp-flow-control.py'))
tcp=(source/'libfreerdp/core/tcp.c').read_bytes()
if tcp.endswith(patch['TCP_SUFFIX']): tcp=tcp[:-len(patch['TCP_SUFFIX'])]
assert hashlib.sha256(tcp).hexdigest()==patch['PINNED']['libfreerdp/core/tcp.c']
tcp=tcp.decode()
ring_bytes=(source/'libfreerdp/utils/ringbuffer.c').read_bytes()
assert hashlib.sha256(ring_bytes).hexdigest()=='68bd5f9ea7644b1fc1170938ee9cc46d366e8f933a0deff503f4902f86adb04a'
ring=ring_bytes.decode()
transport=(source/'libfreerdp/core/transport.c').read_bytes()
for suffix in (patch['SUFFIX'],patch['READ_ONLY_SUFFIX']):
 if transport.endswith(suffix): transport=transport[:-len(suffix)];break
assert hashlib.sha256(transport).hexdigest()==patch['PINNED']['libfreerdp/core/transport.c']
transport=transport.decode()
def section(s,start,end): return s[s.index(start):s.index(end,s.index(start))]
head=section((root/'app/src/test/cpp/flow_accessor_test.c').read_text(),'#include <assert.h>','// TCP validator seam')
head += '''
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <stdlib.h>
#include <signal.h>
#include <openssl/err.h>
#define BIO_C_READ_BLOCKED 1105
#define BIO_C_WRITE_BLOCKED 1106
#define SOCKET int
#define WSAGetLastError() errno
#define WSAEWOULDBLOCK EAGAIN
#define WSAEINTR EINTR
#define WSAEINPROGRESS EINPROGRESS
#define WSAEALREADY EALREADY
static unsigned send_calls;
static int forced_errno, forced_zero, send_limit;
static unsigned fault_call;
static int fault_errno, fault_limit;
static ssize_t counted_send(int fd,const void* bytes,size_t size,int flags){
 send_calls++;
 if(send_calls==fault_call){if(fault_errno){errno=fault_errno;return -1;}if(fault_limit&&size>(size_t)fault_limit)size=(size_t)fault_limit;}
 if(forced_errno){errno=forced_errno;return -1;}
 if(forced_zero){errno=0;return 0;}
 if(send_limit && size>(size_t)send_limit)size=(size_t)send_limit;
 return send(fd,bytes,size,flags);
}
#define _send counted_send
#define IFCALLRESULT(fallback,fn,...) ((fn)?(fn)(__VA_ARGS__):(fallback))
typedef struct {int sockfd;void* hEvent;} rdpTcpLayer;
typedef struct {void* userContext; int (*Read)(void*,void*,int);int (*Write)(void*,const void*,int);int (*Close)(void*);int (*Wait)(void*,int,unsigned);void* (*GetEvent)(void*);} rdpTransportLayer;
static int freerdp_tcp_layer_read(void* p,void* b,int n){(void)p;(void)b;(void)n;return -1;}
static int freerdp_tcp_layer_close(void* p){(void)p;return 1;}
static int freerdp_tcp_layer_wait(void* p,int w,unsigned t){(void)p;(void)w;(void)t;return 0;}
static void* freerdp_tcp_layer_get_event(void* p){return p;}
#define TEST_BYTES (16U*1024U*1024U)
#define BOOL int
#define BYTE unsigned char
#define TRUE 1
#define FALSE 0
#define nullptr NULL
#define WINPR_C_ARRAY_INIT {0}
#define WINPR_ASSERT assert
#define WINPR_ASSERTING_INT_CAST(t,x) ((t)(x))
#define WLog_ERR(...) ((void)0)
#define DEBUG_RINGBUFFER(...) ((void)0)
#define MIN(a,b) ((a)<(b)?(a):(b))
typedef struct { size_t initialSize,freeSize,size,readPtr,writePtr; BYTE* buffer; } RingBuffer;
typedef struct {size_t size; const BYTE* data;} DataChunk;
size_t ringbuffer_used(const RingBuffer*);
BYTE* ringbuffer_ensure_linear_write(RingBuffer*,size_t);
BOOL ringbuffer_commit_written_bytes(RingBuffer*,size_t);
'''
body=ring[ring.index('BOOL ringbuffer_init'):]
body+=section(tcp,'typedef struct\n{\n\tBIO* bufferedBio;', 'static int transport_bio_buffered_read')
body+=section(tcp,'static long transport_bio_buffered_ctrl','BIO_METHOD* BIO_s_buffered_socket')
body+=section(tcp,'static int freerdp_tcp_layer_write','static BOOL freerdp_tcp_layer_close')
body+=section(transport,'static int transport_layer_bio_write','static int transport_layer_bio_read')
main=r'''
INT64 rdpsync_freerdp_pending_bytes(rdpContext*);
INT64 rdpsync_freerdp_drain_pending(rdpContext*);
static int sock;
static int lower_create(BIO* b){BIO_set_init(b,1);return 1;}
static void receive_available(int fd,unsigned char* target,size_t* count){
 for(;;){if(*count==TEST_BYTES)return;ssize_t n=read(fd,target+*count,TEST_BYTES-*count);if(n>0)*count+=(size_t)n;else {assert(n<0 && errno==EAGAIN);return;}}
}
static INT64 checked_drain(rdpContext* context){
 INT64 before=rdpsync_freerdp_pending_bytes(context);unsigned calls=send_calls;
 INT64 after=rdpsync_freerdp_drain_pending(context);assert(send_calls-calls<=2);
 if(after>=0)assert(after<=before && before-after<=256*1024);
 return after;
}
int main(int argc,char**argv){
 signal(SIGPIPE,SIG_IGN);
 int fd[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,fd));sock=fd[0];
 for(int i=0;i<2;i++)assert(!fcntl(fd[i],F_SETFL,fcntl(fd[i],F_GETFL)|O_NONBLOCK));
 int cap=1024*1024;assert(!setsockopt(sock,SOL_SOCKET,SO_SNDBUF,&cap,sizeof(cap)));
 BIO_METHOD* lm=BIO_meth_new(BIO_TYPE_SIMPLE,"TransportLayer");assert(lm);BIO_meth_set_create(lm,lower_create);BIO_meth_set_write(lm,transport_layer_bio_write);
 BIO_METHOD* bm=BIO_meth_new(BIO_TYPE_BUFFERED,"BufferedSocket");assert(bm);BIO_meth_set_create(bm,transport_bio_buffered_new);BIO_meth_set_destroy(bm,transport_bio_buffered_free);BIO_meth_set_write(bm,transport_bio_buffered_write);BIO_meth_set_ctrl(bm,transport_bio_buffered_ctrl);
 BIO* buffered=BIO_new(bm);BIO* lower=BIO_new(lm);assert(buffered&&lower);rdpTcpLayer tcp_layer={.sockfd=sock,.hEvent=&tcp_layer};rdpTransportLayer layer={.userContext=&tcp_layer,.Read=freerdp_tcp_layer_read,.Write=freerdp_tcp_layer_write,.Close=freerdp_tcp_layer_close,.Wait=freerdp_tcp_layer_wait,.GetEvent=freerdp_tcp_layer_get_event};BIO_set_data(lower,&layer);BIO_push(buffered,lower);
 SSL_CTX* sc=SSL_CTX_new(TLS_client_method());SSL* ssl=SSL_new(sc);assert(ssl);SSL_set_bio(ssl,buffered,buffered);
 ProbeTls tls={.ssl=ssl,.bio=buffered,.underlying=buffered};rdpTransport transport={.frontBio=buffered,.layer=TRANSPORT_LAYER_TLS,.tls=&tls};ProbeRdp rdp={.transport=&transport};rdpContext context={.rdp=&rdp};
 transport.io.WritePdu=transport_default_write;transport.io.TLSConnect=transport_default_connect_tls;transport.io.ConnectLayer=transport_default_connect_layer;transport.io.AttachLayer=transport_default_attach_layer;transport.io.SetBlockingMode=transport_default_set_blocking_mode;
 unsigned char* input=malloc(TEST_BYTES);unsigned char* output=malloc(TEST_BYTES);assert(input&&output);
 for(size_t i=0;i<TEST_BYTES;i++)input[i]=(unsigned char)((i*31+i/997)&255);
 assert(BIO_write(buffered,input,TEST_BYTES)==TEST_BYTES);
 INT64 pending=rdpsync_freerdp_pending_bytes(&context);assert(pending>8*1024*1024);size_t received=0;
 /* Exact old behavior: repeated read-only pumps stop at high water even after
  * peer resumes and drains every byte currently delivered to kernel. */
 receive_available(fd[1],output,&received);
 for(int i=0;i<100;i++)assert(rdpsync_freerdp_pending_bytes(&context)==pending);
 if(argc>1 && !strcmp(argv[1],"old")){fprintf(stderr,"OLD RED: peer resumed, %lld buffered bytes remain after 100 read-only probes\n",(long long)pending);assert(rdpsync_freerdp_pending_bytes(&context)==0);}
 forced_zero=1;assert(checked_drain(&context)==pending);forced_zero=0;
 forced_errno=EINTR;assert(checked_drain(&context)==pending);forced_errno=0;
 send_limit=17;assert(checked_drain(&context)==pending-17);pending-=17;send_limit=0;
 /* Resume once; then deliberately stop reading again to prove EAGAIN finite. */
 INT64 after=checked_drain(&context);assert(after>=0&&after<pending&&pending-after<=256*1024);pending=after;
 for(int i=0;i<10;i++){after=checked_drain(&context);assert(after>=0&&after<=pending&&pending-after<=256*1024);if(after==pending)break;pending=after;}
 for(int i=0;i<100;i++)assert(checked_drain(&context)==pending);
 unsigned iterations=0;
 while(pending){receive_available(fd[1],output,&received);after=checked_drain(&context);assert(after>=0&&after<=pending&&pending-after<=256*1024);pending=after;assert(++iterations<10000);}
 receive_available(fd[1],output,&received);assert(received==TEST_BYTES && !memcmp(input,output,received));
 assert(checked_drain(&context)==0 && !held);
 /* Ring wrap: first span succeeds; second span EAGAIN or short-write must
  * commit only FIFO prefix, with no skip/duplicate across the wrap. */
 WINPR_BIO_BUFFERED_SOCKET* state=BIO_get_data(buffered);
 for(int scenario=0;scenario<2;scenario++){
  ringbuffer_destroy(&state->xmitBuffer);assert(ringbuffer_init(&state->xmitBuffer,65536));
  assert(ringbuffer_write(&state->xmitBuffer,input,60000));ringbuffer_commit_read_bytes(&state->xmitBuffer,60000);
  assert(ringbuffer_write(&state->xmitBuffer,input,10000));DataChunk spans[2];assert(ringbuffer_peek(&state->xmitBuffer,spans,10000)==2&&spans[0].size==5536);
  fault_call=send_calls+2;fault_errno=scenario==0?EAGAIN:0;fault_limit=scenario==1?17:0;
  assert(checked_drain(&context)==4464-(scenario==1?17:0));fault_call=0;fault_errno=0;fault_limit=0;
  received=0;receive_available(fd[1],output,&received);assert(received==5536+(scenario==1?17:0));
  assert(checked_drain(&context)==0);receive_available(fd[1],output,&received);assert(received==10000&&!memcmp(input,output,10000));
 }
 /* Preserve queued bytes on fatal, never claim success or replay an IRP. */
 assert(BIO_write(buffered,input,TEST_BYTES)==TEST_BYTES);close(fd[1]);assert(checked_drain(&context)==-3 && !held);
 free(input);free(output);SSL_free(ssl);SSL_CTX_free(sc);BIO_meth_free(bm);BIO_meth_free(lm);close(fd[0]);
 puts("real nonblocking socket + exact stock buffered/ringbuffer: old gate strands queue; bounded short-write/EAGAIN, resume exact 16MiB order/bytes, each call <=256KiB, pending zero, fatal fail-closed PASS");
}
'''
out=root/'build/flow-drain-socket';out.mkdir(parents=True,exist_ok=True)
(out/'test.c').write_bytes((head+body+main).encode()+patch['SUFFIX']+patch['TCP_SUFFIX'])
subprocess.run(['cc','-std=c11','-g','-O1','-Wall','-Wextra','-Wno-unused-function','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(out/'test.c'),'-lssl','-lcrypto','-o',str(out/'test')],check=True)
env={**os.environ,'ASAN_OPTIONS':os.environ.get('ASAN_OPTIONS','detect_leaks=0')}
with (out/'old-red.log').open('w') as log:
 r=subprocess.run([str(out/'test'),'old'],stdout=log,stderr=log,env=env,timeout=10)
 assert r.returncode==-6, f'Expected assertion SIGABRT, got {r.returncode}'
 old_log=(out/'old-red.log').read_text()
 assert 'OLD RED: peer resumed' in old_log and 'rdpsync_freerdp_pending_bytes(&context)==0' in old_log, old_log
 print('Preserved old failure exit',r.returncode)
with (out/'fixed-green.log').open('w') as log:
 subprocess.run([str(out/'test')],stdout=log,stderr=log,env=env,check=True,timeout=10)
print((out/'fixed-green.log').read_text())
