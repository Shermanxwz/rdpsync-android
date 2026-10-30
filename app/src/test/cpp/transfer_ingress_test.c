#define _GNU_SOURCE
#include <assert.h>
#include "../../main/cpp/freerdp_bridge.c"
static int calls;
const char* freerdp_channels_get_name_by_id(freerdp* instance, UINT16 id){(void)instance;return id==1?CLIPRDR_SVC_CHANNEL_NAME:"drdynvc";}
static BOOL delegate(freerdp* instance,UINT16 id,const BYTE* bytes,size_t size,UINT32 flags,size_t total){(void)instance;(void)id;(void)bytes;(void)size;(void)flags;(void)total;++calls;return TRUE;}
static void write16(BYTE* p,unsigned value){p[0]=value&255;p[1]=(value>>8)&255;}
static void write32(BYTE* p,unsigned value){write16(p,value);write16(p+2,value>>16);}
static void header(BYTE* p,unsigned type,unsigned flags,unsigned length){memset(p,0,80);write16(p,type);write16(p+2,flags);write32(p+4,length);}
static void check_legacy_controls(RdpSession* s,freerdp* instance){
    BYTE packet[80];int before;
    /* Exact xrdp v0.10.6.1 clipboard.c initial caps serialization: dataLen16
     * but one additional zero DWORD follows the GENERAL capability set. */
    header(packet,CB_CLIP_CAPS,0,16);write16(packet+8,1);write16(packet+12,CB_CAPSTYPE_GENERAL);write16(packet+14,12);
    write32(packet+16,CB_CAPS_VERSION_2);write32(packet+20,CB_USE_LONG_FORMAT_NAMES|CB_STREAM_FILECLIP_ENABLED|CB_FILECLIP_NO_FILE_PATHS);
    s->transfer_ingress=(RdpTransferIngress){0};before=calls;
    assert(receive_channel_data(instance,1,packet,28,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,28)&&calls==before+1);
    for(size_t split=24;split<28;split++){
        s->transfer_ingress=(RdpTransferIngress){0};before=calls;
        assert(receive_channel_data(instance,1,packet,split,CHANNEL_FLAG_FIRST,28));
        assert(receive_channel_data(instance,1,packet+split,28-split,CHANNEL_FLAG_LAST,28)&&calls==before+2);
        s->transfer_ingress=(RdpTransferIngress){0};before=calls;packet[27]=1;
        assert(receive_channel_data(instance,1,packet,split,CHANNEL_FLAG_FIRST,28));
        assert(!receive_channel_data(instance,1,packet+split,28-split,CHANNEL_FLAG_LAST,28)&&calls==before+1);packet[27]=0;
    }
    for(size_t bad=24;bad<28;bad++){
        packet[bad]=1;s->transfer_ingress=(RdpTransferIngress){0};before=calls;
        assert(!receive_channel_data(instance,1,packet,28,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,28)&&calls==before);packet[bad]=0;
    }
    for(size_t total=25;total<=32;total++){if(total==28)continue;s->transfer_ingress=(RdpTransferIngress){0};before=calls;
        assert(!receive_channel_data(instance,1,packet,total,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,total)&&calls==before);}
    write32(packet+4,12);s->transfer_ingress=(RdpTransferIngress){0};assert(!receive_channel_data(instance,1,packet,24,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,24));
    const unsigned types[]={CB_MONITOR_READY,CB_FORMAT_LIST_RESPONSE,CB_FORMAT_DATA_REQUEST,CB_FORMAT_LIST,CB_FILECONTENTS_REQUEST};
    const unsigned sizes[]={0,0,4,46,28};
    for(size_t i=0;i<sizeof(types)/sizeof(types[0]);i++){
        header(packet,types[i],types[i]==CB_FORMAT_LIST_RESPONSE?CB_RESPONSE_OK:0,sizes[i]);
        if(types[i]==CB_FORMAT_DATA_REQUEST)write32(packet+8,0xc001);
        if(types[i]==CB_FORMAT_LIST){write32(packet+8,0xc001);const char* name="FileGroupDescriptorW";for(size_t j=0;name[j];j++)write16(packet+12+2*j,(unsigned char)name[j]);}
        if(types[i]==CB_FILECONTENTS_REQUEST){write32(packet+8,1);write32(packet+16,FILECONTENTS_RANGE);write32(packet+28,4);}
        s->transfer_ingress=(RdpTransferIngress){0};before=calls;size_t total=sizes[i]+12;
        assert(receive_channel_data(instance,1,packet,total,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,total)&&calls==before+1);
    }
    header(packet,CB_LOCK_CLIPDATA,0,4);s->transfer_ingress=(RdpTransferIngress){0};before=calls;
    assert(!receive_channel_data(instance,1,packet,16,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,16)&&calls==before);
    header(packet,CB_MONITOR_READY,0,UINT32_MAX);s->transfer_ingress=(RdpTransferIngress){0};
    assert(!receive_channel_data(instance,1,packet,12,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,12));
}
int main(void){
    RdpSession s={0};RdpSyncContext context={0};freerdp instance={0};pthread_mutex_init(&s.mutex,NULL);
    s.instance=&instance;s.transfer=rdpsync_transfer_new(1,1);s.receive_channel_data=delegate;instance.context=&context.common.context;context.session=&s;
    BYTE bytes[16]={0};bytes[4]=8;
    assert(!receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_FIRST,RDPSYNC_TRANSFER_MESSAGE_MAX+1)&&calls==0);
    assert(!receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_LAST,8)&&calls==0);
    assert(receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_FIRST,16)&&calls==1);
    assert(!receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_LAST,17)&&calls==1);
    assert(receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_LAST,16)&&calls==2);
    assert(receive_channel_data(&instance,1,bytes,16,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,16)&&calls==3);
    assert(!receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,0)&&calls==3);
    assert(receive_channel_data(&instance,2,bytes,8,CHANNEL_FLAG_FIRST,1000000000)&&calls==4);
    assert(receive_channel_data(&instance,1,bytes,8,CHANNEL_FLAG_FIRST,16)&&calls==5);
    assert(!receive_channel_data(&instance,1,bytes,16,CHANNEL_FLAG_LAST,16)&&calls==5);
    s.transfer_ingress=(RdpTransferIngress){.messages=RDPSYNC_TRANSFER_MAX_MESSAGES};
    assert(!receive_channel_data(&instance,1,bytes,16,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,16)&&calls==5);
    check_legacy_controls(&s,&instance);
    rdpsync_transfer_free(s.transfer);
    pthread_mutex_destroy(&s.mutex);
    puts("production CLIPRDR ingress: predelegate caps, exact xrdp zero trailers including split/malformed cases, other channels preserved PASS");
}
