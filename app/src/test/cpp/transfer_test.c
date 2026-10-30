#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "../../main/cpp/rdpsync_transfer.c"
static unsigned sends,format_count,response_flags,response_size;
static uint32_t last_stream,last_clip;
static uint8_t response_bytes[TF_MANIFEST_MAX];
static CLIPRDR_FILE_CONTENTS_REQUEST last_request;
static UINT send_caps(CliprdrClientContext* c,const CLIPRDR_CAPABILITIES* p){(void)c;assert(p->cCapabilitiesSets==1);return 0;}
static UINT send_formats(CliprdrClientContext* c,const CLIPRDR_FORMAT_LIST* p){(void)c;format_count=p->numFormats;++sends;return 0;}
static UINT send_format_response(CliprdrClientContext* c,const CLIPRDR_FORMAT_LIST_RESPONSE* p){(void)c;assert(p->common.msgFlags==CB_RESPONSE_OK);return 0;}
static UINT send_data_request(CliprdrClientContext* c,const CLIPRDR_FORMAT_DATA_REQUEST* p){(void)c;assert(p->requestedFormatId==123);++sends;return 0;}
static UINT send_data(CliprdrClientContext* c,const CLIPRDR_FORMAT_DATA_RESPONSE* p){(void)c;response_flags=p->common.msgFlags;response_size=p->common.dataLen;if(response_size)memcpy(response_bytes,p->requestedFormatData,response_size);return 0;}
static UINT send_lock(CliprdrClientContext* c,const CLIPRDR_LOCK_CLIPBOARD_DATA* p){(void)c;last_clip=p->clipDataId;return 0;}
static UINT send_unlock(CliprdrClientContext* c,const CLIPRDR_UNLOCK_CLIPBOARD_DATA* p){(void)c;assert(p->clipDataId);return 0;}
static UINT send_request(CliprdrClientContext* c,const CLIPRDR_FILE_CONTENTS_REQUEST* p){(void)c;last_request=*p;++sends;return 0;}
static UINT send_contents(CliprdrClientContext* c,const CLIPRDR_FILE_CONTENTS_RESPONSE* p){(void)c;response_flags=p->common.msgFlags;response_size=p->cbRequested;last_stream=p->streamId;assert(response_size<=sizeof(response_bytes));if(response_size)memcpy(response_bytes,p->requestedData,response_size);return 0;}
static void ascii(RdpTransferFile* f,const char* name,uint64_t size){memset(f,0,sizeof(*f));for(size_t i=0;name[i];i++)f->name[i]=(uint8_t)name[i];f->size=size;}
int main(void){
    RdpTransferFile files[RDPSYNC_TRANSFER_FILES]={0},parsed[RDPSYNC_TRANSFER_FILES];size_t count=0;uint64_t total=0;uint8_t bytes[TF_MANIFEST_MAX];
    ascii(&files[0],"alpha.txt",4);ascii(&files[1],"empty",0);
    size_t n=rdpsync_transfer_write_manifest(files,2,bytes,sizeof(bytes));assert(n==1188);
    assert(!rdpsync_transfer_parse_manifest(bytes,n,parsed,&count,&total)&&count==2&&total==4);
    assert(rdpsync_transfer_parse_manifest(bytes,n-1,parsed,&count,&total)==TF_INVALID);
    put32(bytes,65);assert(rdpsync_transfer_parse_manifest(bytes,n,parsed,&count,&total)==TF_INVALID);put32(bytes,2);
    put32(bytes+4+36,0x10);assert(rdpsync_transfer_parse_manifest(bytes,n,parsed,&count,&total)==TF_INVALID);put32(bytes+4+36,0x80);
    put32(bytes+4,4);assert(rdpsync_transfer_parse_manifest(bytes,n,parsed,&count,&total)==TF_INVALID);put32(bytes+4,0x44);
    put32(bytes+4+64,1);assert(rdpsync_transfer_parse_manifest(bytes,n,parsed,&count,&total)==TF_INVALID);put32(bytes+4+64,0);
    const char* invalid[]={"../secret","x/y","x\\y","CON.txt","Lpt1","a:","a.","a ","..","","?"};
    for(size_t i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++){RdpTransferFile f;ascii(&f,invalid[i],1);assert(!rdpsync_transfer_name_valid(f.name,name_length(f.name)));}
    uint16_t unicode[]={0x4e2d,0x6587,0xd83d,0xde00};assert(rdpsync_transfer_name_valid(unicode,4));assert(!rdpsync_transfer_name_valid(unicode,3));
    uint16_t bidi[]={'a',0x202e,'b'};assert(!rdpsync_transfer_name_valid(bidi,3));
    ascii(&files[1],"ALPHA.txt",1);assert(!rdpsync_transfer_write_manifest(files,2,bytes,sizeof(bytes)));ascii(&files[1],"empty",0);
    n=rdpsync_transfer_write_manifest(files,2,bytes,sizeof(bytes));
    RdpTransfer* t=rdpsync_transfer_new(77,1);assert(t);
    CliprdrClientContext c={.ClientCapabilities=send_caps,.ClientFormatList=send_formats,.ClientFormatListResponse=send_format_response,.ClientFormatDataRequest=send_data_request,.ClientFormatDataResponse=send_data,.ClientLockClipboardData=send_lock,.ClientUnlockClipboardData=send_unlock,.ClientFileContentsRequest=send_request,.ClientFileContentsResponse=send_contents};
    rdpsync_transfer_attach(t,&c);
    CLIPRDR_GENERAL_CAPABILITY_SET gen={CB_CAPSTYPE_GENERAL,CB_CAPSTYPE_GENERAL_LEN,CB_CAPS_VERSION_2,CB_STREAM_FILECLIP_ENABLED|CB_CAN_LOCK_CLIPDATA};
    CLIPRDR_CAPABILITIES caps={.cCapabilitiesSets=1,.capabilitySets=(void*)&gen};assert(!c.ServerCapabilities(&c,&caps));assert(!c.MonitorReady(&c,NULL));assert(!rdpsync_transfer_pump(t)&&format_count==0);
    int64_t state[10];rdpsync_transfer_state(t,state);assert(state[0]==77&&state[1]==1);
    char path[]="/tmp/rdpsync-transfer-test-XXXXXX";int rw=mkstemp(path);assert(rw>=0&&write(rw,"ABCD",4)==4);int fd=open(path,O_RDONLY|O_CLOEXEC);assert(fd>=0);unlink(path);
    assert(rdpsync_transfer_offer(t,76,files,&fd,1)==TF_STALE);assert(rdpsync_transfer_offer(t,77,files,&rw,1)==TF_INVALID);
    int64_t offered=rdpsync_transfer_offer(t,77,files,&fd,1);assert(offered>0);close(fd);close(rw);assert(rdpsync_transfer_retained(t,offered));assert(!rdpsync_transfer_pump(t)&&format_count==1);
    CLIPRDR_FORMAT_DATA_REQUEST fr={.requestedFormatId=TF_FORMAT_ID};assert(!c.ServerFormatDataRequest(&c,&fr)&&response_flags==CB_RESPONSE_OK&&response_size==596);
    fr.requestedFormatId=13;assert(!c.ServerFormatDataRequest(&c,&fr)&&response_flags==CB_RESPONSE_FAIL);
    CLIPRDR_LOCK_CLIPBOARD_DATA lock={.clipDataId=41};assert(!c.ServerLockClipboardData(&c,&lock));
    CLIPRDR_FILE_CONTENTS_REQUEST req={.streamId=11,.listIndex=0,.dwFlags=FILECONTENTS_RANGE,.cbRequested=4,.haveClipDataId=1,.clipDataId=41};
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_OK&&response_size==4&&!memcmp(response_bytes,"ABCD",4));
    req.nPositionHigh=UINT32_MAX;assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);req.nPositionHigh=0;
    req.cbRequested=RDPSYNC_TRANSFER_PEER_RANGE+1;assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);
    req.cbRequested=8;req.dwFlags=FILECONTENTS_SIZE;assert(!c.ServerFileContentsRequest(&c,&req)&&response_size==8&&get32(response_bytes)==4);
    assert(!rdpsync_transfer_cancel(t,offered)&&!rdpsync_transfer_retained(t,offered));assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);
    /* Replacing a revoked snapshot must never resolve an old unlocked index
     * or reused old lock ID to newly selected bytes. */
    char second[]="/tmp/rdpsync-transfer-replace-XXXXXX";rw=mkstemp(second);assert(rw>=0&&write(rw,"WXYZ",4)==4);fd=open(second,O_RDONLY|O_CLOEXEC);unlink(second);close(rw);
    int64_t replacement=rdpsync_transfer_offer(t,77,files,&fd,1);assert(replacement>offered);close(fd);
    req.dwFlags=FILECONTENTS_RANGE;req.cbRequested=4;req.haveClipDataId=0;
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);
    assert(!c.ServerLockClipboardData(&c,&lock));req.haveClipDataId=1;req.clipDataId=41;
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);
    lock.clipDataId=42;assert(!c.ServerLockClipboardData(&c,&lock));req.clipDataId=42;
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_OK&&!memcmp(response_bytes,"WXYZ",4));
    assert(!rdpsync_transfer_cancel(t,replacement));
    CLIPRDR_FORMAT fmt={123,"FileGroupDescriptorW"};CLIPRDR_FORMAT_LIST fl={.numFormats=1,.formats=&fmt};assert(!c.ServerFormatList(&c,&fl));
    assert(rdpsync_transfer_request_list(t,76)==TF_STALE);int64_t receive=rdpsync_transfer_request_list(t,77);assert(receive>offered);assert(!rdpsync_transfer_pump(t)&&last_clip);
    /* xrdp's descriptor response has dataLen=count*592+4 plus a zero DWORD
     * trailer. The admitted metadata size must exclude that trailer. */
    uint8_t padded[TF_MANIFEST_MAX+12]={0};put16(padded,CB_FORMAT_DATA_RESPONSE);put16(padded+2,CB_RESPONSE_OK);put32(padded+4,(uint32_t)n);memcpy(padded+8,bytes,n);
    RdpTransferIngress ingress={0};assert(rdpsync_transfer_ingress(&ingress,padded,12,CHANNEL_FLAG_FIRST,n+12));
    assert(rdpsync_transfer_admit_incoming(t,padded,12,n+12));
    assert(rdpsync_transfer_ingress(&ingress,padded+12,n,CHANNEL_FLAG_LAST,n+12));
    CLIPRDR_FORMAT_DATA_RESPONSE descriptor={.common={.msgFlags=CB_RESPONSE_OK,.dataLen=(UINT32)n},.requestedFormatData=bytes};assert(!c.ServerFormatDataResponse(&c,&descriptor));
    assert(!rdpsync_transfer_manifest(t,receive,parsed,&count)&&count==2);
    assert(rdpsync_transfer_request_chunk(t,receive,0,UINT64_MAX,1)==TF_INVALID);assert(rdpsync_transfer_request_chunk(t,receive,0,0,65537)==TF_INVALID);
    assert(!rdpsync_transfer_request_chunk(t,receive,0,0,4));assert(rdpsync_transfer_request_chunk(t,receive,0,0,4)==TF_BUSY);assert(!rdpsync_transfer_pump(t));
    CLIPRDR_FILE_CONTENTS_RESPONSE answer={.common={.msgFlags=CB_RESPONSE_OK},.streamId=last_request.streamId+1,.cbRequested=4,.requestedData=(const BYTE*)"EFGH"};
    assert(!c.ServerFileContentsResponse(&c,&answer));uint8_t chunk[65536];assert(rdpsync_transfer_take_chunk(t,receive,chunk,sizeof(chunk))==0);
    answer.streamId=last_request.streamId;
    memset(padded,0,20);put16(padded,CB_FILECONTENTS_RESPONSE);put16(padded+2,CB_RESPONSE_OK);put32(padded+4,8);put32(padded+8,last_request.streamId);memcpy(padded+12,"EFGH",4);
    ingress=(RdpTransferIngress){0};assert(rdpsync_transfer_ingress(&ingress,padded,12,CHANNEL_FLAG_FIRST,20));
    assert(rdpsync_transfer_admit_incoming(t,padded,12,20));assert(!rdpsync_transfer_admit_incoming(t,padded,12,20));
    assert(rdpsync_transfer_ingress(&ingress,padded+12,8,CHANNEL_FLAG_LAST,20));
    assert(!c.ServerFileContentsResponse(&c,&answer));assert(rdpsync_transfer_take_chunk(t,receive,chunk,sizeof(chunk))==4&&!memcmp(chunk,"EFGH",4));
    assert(!rdpsync_transfer_request_chunk(t,receive,0,0,4));assert(!rdpsync_transfer_pump(t));
    answer.streamId=last_request.streamId;answer.common.msgFlags=CB_RESPONSE_OK|CB_RESPONSE_FAIL;
    assert(!c.ServerFileContentsResponse(&c,&answer));assert(rdpsync_transfer_take_chunk(t,receive,chunk,sizeof(chunk))==TF_INVALID);
    answer.common.msgFlags=CB_RESPONSE_OK;
    assert(!rdpsync_transfer_finish(t,receive));assert(rdpsync_transfer_take_chunk(t,receive,chunk,sizeof(chunk))==TF_STALE);
    int64_t old=rdpsync_transfer_request_list(t,77);assert(old>0);assert(!rdpsync_transfer_pump(t));assert(!rdpsync_transfer_cancel(t,old));assert(rdpsync_transfer_request_list(t,77)==TF_BUSY);
    assert(!c.ServerFormatDataResponse(&c,&descriptor));int64_t fresh=rdpsync_transfer_request_list(t,77);assert(fresh>old);assert(!rdpsync_transfer_cancel(t,fresh));
    /* A deadline before the owner pump must not emit a metadata request. */
    int64_t expired=rdpsync_transfer_request_list(t,77);assert(expired>0);t->deadline_ms=0;unsigned previous_sends=sends;
    assert(!rdpsync_transfer_pump(t)&&sends==previous_sends&&t->phase==5&&!t->list_inflight);
    assert(!rdpsync_transfer_cancel(t,expired));
    int64_t replaced=rdpsync_transfer_request_list(t,77);assert(replaced>0);assert(!rdpsync_transfer_pump(t));assert(!c.ServerFormatDataResponse(&c,&descriptor));
    assert(!rdpsync_transfer_request_chunk(t,replaced,0,0,4));assert(!rdpsync_transfer_pump(t));
    answer.streamId=last_request.streamId;assert(!c.ServerFormatList(&c,&fl));assert(!c.ServerFileContentsResponse(&c,&answer));
    assert(rdpsync_transfer_take_chunk(t,replaced,chunk,sizeof(chunk))==TF_STALE);
    /* Admission occurs before FreeRDP's async reassembly/message queue. */
    int64_t admitted=rdpsync_transfer_request_list(t,77);assert(admitted>0);assert(!rdpsync_transfer_pump(t));
    uint8_t header[16]={0};put16(header,CB_FORMAT_DATA_RESPONSE);put32(header+4,(uint32_t)n);
    assert(!rdpsync_transfer_admit_incoming(t,header,4,n+8));
    assert(rdpsync_transfer_admit_incoming(t,header,8,n+8));
    assert(!rdpsync_transfer_admit_incoming(t,header,8,n+8)); /* duplicate before callback */
    assert(!c.ServerFormatDataResponse(&c,&descriptor));assert(!t->format_admitted);
    assert(!rdpsync_transfer_request_chunk(t,admitted,0,0,4));assert(!rdpsync_transfer_pump(t));
    put16(header,CB_FILECONTENTS_RESPONSE);put32(header+4,8);put32(header+8,last_request.streamId+1);
    assert(!rdpsync_transfer_admit_incoming(t,header,12,16)); /* wrong stream */
    put32(header+8,last_request.streamId);
    assert(rdpsync_transfer_admit_incoming(t,header,12,16));assert(!rdpsync_transfer_admit_incoming(t,header,12,16));
    assert(!rdpsync_transfer_cancel(t,admitted)&&t->data_admitted);
    assert(rdpsync_transfer_request_list(t,77)==TF_BUSY); /* genuine sent chunk drain barrier */
    answer.streamId=last_request.streamId;assert(!c.ServerFileContentsResponse(&c,&answer));assert(!t->data_admitted&&!t->retired_request_id);
    int64_t unsent=rdpsync_transfer_request_list(t,77);assert(unsent>0);assert(!rdpsync_transfer_cancel(t,unsent));
    unsent=rdpsync_transfer_request_list(t,77);assert(unsent>0);assert(!rdpsync_transfer_cancel(t,unsent)); /* no false barrier */
    uint8_t caps_packet[28]={0};put16(caps_packet,CB_CLIP_CAPS);put32(caps_packet+4,16);t->incoming_control_remaining=28;
    assert(rdpsync_transfer_admit_incoming(t,caps_packet,28,28)&&t->incoming_control_remaining==0); /* actual wirebytes inclpad */
    assert(!rdpsync_transfer_admit_incoming(t,caps_packet,28,28));
    t->incoming_control_remaining=16;put16(header,CB_CLIP_CAPS);put32(header+4,8);
    assert(rdpsync_transfer_admit_incoming(t,header,16,16));assert(!rdpsync_transfer_admit_incoming(t,header,16,16));
    /* Simulated stalled peer: send delegates never acknowledge/drain. Quotas
     * nevertheless stop queue admission and survive cancel/reoffer. */
    uint64_t remaining=t->send_remaining;assert(remaining==RDPSYNC_TRANSFER_SEND_BYTES-8);
    char third[]="/tmp/rdpsync-transfer-budget-XXXXXX";rw=mkstemp(third);assert(rw>=0&&write(rw,"IJKL",4)==4);fd=open(third,O_RDONLY|O_CLOEXEC);unlink(third);close(rw);
    t->send_remaining=3;assert(rdpsync_transfer_offer(t,77,files,&fd,1)==TF_QUOTA);
    t->send_remaining=4;int64_t budget_offer=rdpsync_transfer_offer(t,77,files,&fd,1);assert(budget_offer>0);close(fd);
    lock.clipDataId=43;assert(!c.ServerLockClipboardData(&c,&lock));req.clipDataId=43;req.cbRequested=4;req.dwFlags=FILECONTENTS_RANGE;
    assert(!c.ServerFileContentsRequest(&c,&req)&&t->send_remaining==0);
    assert(c.ServerFileContentsRequest(&c,&req)==ERROR_NOT_ENOUGH_QUOTA);
    assert(!rdpsync_transfer_cancel(t,budget_offer)&&t->send_remaining==0);
    rdpsync_transfer_state(t,state);assert(state[9]==0);remaining=0;
    t->control_remaining=8;fr.requestedFormatId=13;
    assert(!c.ServerFormatDataRequest(&c,&fr));assert(c.ServerFormatDataRequest(&c,&fr)==ERROR_NOT_ENOUGH_QUOTA);
    assert(t->send_remaining==remaining);
    rdpsync_transfer_detach(t,&c);rdpsync_transfer_free(t);
    /* A no-lock peer's optional zero ID is only an absent-ID sentinel on the
     * first snapshot. It never grants a lock or revives cancelled/replaced data. */
    t=rdpsync_transfer_new(78,1);rdpsync_transfer_attach(t,&c);gen.generalFlags=CB_STREAM_FILECLIP_ENABLED;
    assert(!c.ServerCapabilities(&c,&caps)&&!c.MonitorReady(&c,NULL));
    char nolock[]="/tmp/rdpsync-transfer-nolock-XXXXXX";rw=mkstemp(nolock);assert(rw>=0&&write(rw,"ABCD",4)==4);fd=open(nolock,O_RDONLY|O_CLOEXEC);unlink(nolock);close(rw);
    int64_t first=rdpsync_transfer_offer(t,78,files,&fd,1);assert(first>0&&!t->require_lock&&!t->peer_lock);
    req=(CLIPRDR_FILE_CONTENTS_REQUEST){.streamId=40,.listIndex=0,.dwFlags=FILECONTENTS_RANGE,.cbRequested=4,.haveClipDataId=1,.clipDataId=0};
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_OK&&!memcmp(response_bytes,"ABCD",4));
    req.clipDataId=99;assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);req.clipDataId=0;
    gen.generalFlags|=CB_CAN_LOCK_CLIPDATA;assert(!c.ServerCapabilities(&c,&caps));
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL); /* no zero-ID lock was granted */
    gen.generalFlags=CB_STREAM_FILECLIP_ENABLED;assert(!c.ServerCapabilities(&c,&caps));
    assert(!rdpsync_transfer_cancel(t,first));assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL);
    remaining=t->send_remaining;assert(rdpsync_transfer_offer(t,78,files,&fd,1)==TF_RECONNECT_REQUIRED&&t->send_remaining==remaining);
    gen.generalFlags|=CB_CAN_LOCK_CLIPDATA;assert(!c.ServerCapabilities(&c,&caps));
    int64_t second_offer=rdpsync_transfer_offer(t,78,files,&fd,1);assert(second_offer>first&&t->require_lock);close(fd);
    gen.generalFlags=CB_STREAM_FILECLIP_ENABLED;assert(!c.ServerCapabilities(&c,&caps));
    assert(!c.ServerFileContentsRequest(&c,&req)&&response_flags==CB_RESPONSE_FAIL&&t->require_lock); /* downgrade can't erase replacement guard */
    assert(!rdpsync_transfer_cancel(t,second_offer));rdpsync_transfer_detach(t,&c);rdpsync_transfer_free(t);
    puts("file-only transfer: manifest bounds/names, readonly FD leases, generations, range requests, cancellation and stale responses PASS");
}
