/* Deterministic no-drain send queue and paused local read, NOT a real TCP/RDP
 * benchmark. The mocked send retains packet copies exactly to model admission
 * pressure; real isolated-server/Android transfer remains a separate gate. */
#define _GNU_SOURCE
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
static ssize_t paused_pread(int fd,void* bytes,size_t size,off_t offset);
#define pread paused_pread
#include "../../main/cpp/rdpsync_transfer.c"
#undef pread
static pthread_mutex_t gate=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed=PTHREAD_COND_INITIALIZER;
static int pause_reads,entered,release_read;
static ssize_t paused_pread(int fd,void* bytes,size_t size,off_t offset){
    pthread_mutex_lock(&gate);
    if(pause_reads){entered=1;pthread_cond_broadcast(&changed);while(!release_read)pthread_cond_wait(&changed,&gate);}
    pthread_mutex_unlock(&gate);
    return pread(fd,bytes,size,offset);
}
static void* queued[8];static size_t queued_count,queued_bytes;
static UINT no_drain(CliprdrClientContext* c,const CLIPRDR_FILE_CONTENTS_RESPONSE* p){
    (void)c;if(p->common.msgFlags==CB_RESPONSE_OK&&p->cbRequested){assert(queued_count<8);queued[queued_count]=malloc(p->cbRequested);assert(queued[queued_count]);memcpy(queued[queued_count++],p->requestedData,p->cbRequested);queued_bytes+=p->cbRequested;}return CHANNEL_RC_OK;
}
static void clear_queue(void){for(size_t i=0;i<queued_count;i++)free(queued[i]);queued_count=queued_bytes=0;}
static void setup(RdpTransfer* t,CliprdrClientContext* c){rdpsync_transfer_attach(t,c);t->ready=t->peer_files=t->peer_lock=1;t->capability=1;}
typedef struct { CliprdrClientContext* context; CLIPRDR_FILE_CONTENTS_REQUEST request; UINT result; } Work;
static void* serve(void* p){Work* w=p;w->result=w->context->ServerFileContentsRequest(w->context,&w->request);return NULL;}
static int metadata_entered,release_metadata,cancel_started,cancel_done;
static unsigned metadata_successes,metadata_sequence,cancel_sequence,sequence;
static UINT paused_metadata(CliprdrClientContext* c,const CLIPRDR_FORMAT_DATA_RESPONSE* p){
    RdpTransfer* t=c->custom;
    /* A deterministic synchronization assertion: the old unlock-before-send
     * code fails here, without relying on a scheduler timing window. */
    assert(pthread_mutex_trylock(&t->mutex)==EBUSY);
    if(p->common.msgFlags==CB_RESPONSE_OK){
        assert(p->common.dataLen==596&&p->requestedFormatData);
        pthread_mutex_lock(&gate);metadata_entered=1;pthread_cond_broadcast(&changed);
        while(!release_metadata)pthread_cond_wait(&changed,&gate);
        ++metadata_successes;metadata_sequence=++sequence;pthread_mutex_unlock(&gate);
    }else assert(p->common.msgFlags==CB_RESPONSE_FAIL&&!p->common.dataLen&&!p->requestedFormatData);
    return CHANNEL_RC_OK;
}
static void* serve_metadata(void* p){CliprdrClientContext* c=p;CLIPRDR_FORMAT_DATA_REQUEST request={.requestedFormatId=TF_FORMAT_ID};assert(!c->ServerFormatDataRequest(c,&request));return NULL;}
typedef struct {RdpTransfer* transfer;uint64_t token;} CancelWork;
static void* cancel_metadata(void* p){
    CancelWork* work=p;pthread_mutex_lock(&gate);cancel_started=1;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&gate);
    assert(!rdpsync_transfer_cancel(work->transfer,work->token));
    pthread_mutex_lock(&gate);cancel_done=1;cancel_sequence=++sequence;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&gate);return NULL;
}
int main(void){
    char path[]="/tmp/rdpsync-transfer-stall-XXXXXX";int rw=mkstemp(path);assert(rw>=0&&ftruncate(rw,RDPSYNC_TRANSFER_PEER_RANGE)==0);int fd=open(path,O_RDONLY|O_CLOEXEC);assert(fd>=0);unlink(path);close(rw);
    RdpTransferFile file={.name={'b','i','g','.','b','i','n'},.size=RDPSYNC_TRANSFER_PEER_RANGE};
    RdpTransfer* t=rdpsync_transfer_new(10,1);assert(t);CliprdrClientContext c={.ClientFileContentsResponse=no_drain};setup(t,&c);
    int64_t token=rdpsync_transfer_offer(t,10,&file,&fd,1);assert(token>0);
    Work work={.context=&c,.request={.streamId=1,.listIndex=0,.dwFlags=FILECONTENTS_RANGE,.cbRequested=RDPSYNC_TRANSFER_PEER_RANGE}};
    for(int i=0;i<4;i++){work.request.streamId++;assert(!c.ServerFileContentsRequest(&c,&work.request));}
    assert(queued_count==4&&queued_bytes==RDPSYNC_TRANSFER_SEND_BYTES&&t->send_remaining==0);
    assert(c.ServerFileContentsRequest(&c,&work.request)==ERROR_NOT_ENOUGH_QUOTA&&queued_count==4);
    assert(!rdpsync_transfer_cancel(t,token)&&!rdpsync_transfer_retained(t,token));
    assert(rdpsync_transfer_offer(t,10,&file,&fd,1)==TF_QUOTA); /* no reoffer bypass */
    rdpsync_transfer_detach(t,&c);rdpsync_transfer_free(t);clear_queue();
    /* Cancellation must not wait behind pread, and FD-retained acknowledgment
     * remains true until the last in-progress duplicated handle is closed. */
    t=rdpsync_transfer_new(11,1);setup(t,&c);token=rdpsync_transfer_offer(t,11,&file,&fd,1);assert(token>0);
    pause_reads=1;entered=release_read=0;pthread_t thread;assert(!pthread_create(&thread,NULL,serve,&work));
    pthread_mutex_lock(&gate);while(!entered)pthread_cond_wait(&changed,&gate);pthread_mutex_unlock(&gate);
    assert(!rdpsync_transfer_cancel(t,token));assert(rdpsync_transfer_retained(t,token));
    int64_t state[10];rdpsync_transfer_state(t,state);assert(state[2]==0); /* state/cancel made progress */
    pthread_mutex_lock(&gate);release_read=1;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&gate);assert(!pthread_join(thread,NULL));
    assert(work.result==CHANNEL_RC_OK&&!rdpsync_transfer_retained(t,token)&&queued_count==0);
    assert(t->send_remaining==RDPSYNC_TRANSFER_SEND_BYTES-RDPSYNC_TRANSFER_PEER_RANGE); /* cancel never refunds */
    rdpsync_transfer_detach(t,&c);rdpsync_transfer_free(t);
    /* A metadata copy already in handoff precedes cancel completion; after
     * completion, a repeated old request can only enqueue an empty FAIL. */
    t=rdpsync_transfer_new(12,1);c.ClientFormatDataResponse=paused_metadata;setup(t,&c);token=rdpsync_transfer_offer(t,12,&file,&fd,1);assert(token>0);
    pthread_t metadata_thread,cancel_thread;assert(!pthread_create(&metadata_thread,NULL,serve_metadata,&c));
    pthread_mutex_lock(&gate);while(!metadata_entered)pthread_cond_wait(&changed,&gate);pthread_mutex_unlock(&gate);
    CancelWork cancel={t,(uint64_t)token};assert(!pthread_create(&cancel_thread,NULL,cancel_metadata,&cancel));
    pthread_mutex_lock(&gate);while(!cancel_started)pthread_cond_wait(&changed,&gate);assert(!cancel_done);release_metadata=1;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&gate);
    assert(!pthread_join(metadata_thread,NULL)&&!pthread_join(cancel_thread,NULL));
    assert(metadata_successes==1&&metadata_sequence<cancel_sequence&&!rdpsync_transfer_retained(t,token));
    serve_metadata(&c);assert(metadata_successes==1);
    rdpsync_transfer_detach(t,&c);rdpsync_transfer_free(t);close(fd);
    puts("transfer no-drain admission <=32MiB, reoffer quota, paused-read cancellation, metadata revocation and FD-release acknowledgment PASS (synthetic queue)");
}
