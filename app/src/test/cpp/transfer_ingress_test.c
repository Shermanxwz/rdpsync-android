#define _GNU_SOURCE
#include <assert.h>
#include "../../main/cpp/freerdp_bridge.c"
static int calls;
const char* freerdp_channels_get_name_by_id(freerdp* instance, UINT16 id){(void)instance;return id==1?CLIPRDR_SVC_CHANNEL_NAME:"drdynvc";}
static BOOL delegate(freerdp* instance,UINT16 id,const BYTE* bytes,size_t size,UINT32 flags,size_t total){(void)instance;(void)id;(void)bytes;(void)size;(void)flags;(void)total;++calls;return TRUE;}
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
    rdpsync_transfer_free(s.transfer);
    pthread_mutex_destroy(&s.mutex);
    puts("production CLIPRDR ingress: cap/fragment sequence checked BEFORE delegate, other channels preserved PASS");
}
