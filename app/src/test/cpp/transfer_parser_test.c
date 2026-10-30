/* Compile the exact pinned upstream CLIPRDR parser and WinPR stream source,
 * not a handwritten parser. Only logging/backtrace sinks are mocked here. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <winpr/stream.h>
#include "cliprdr_common.h"
#include "../../main/cpp/rdpsync_transfer.c"
wLog* WLog_Get(const char* name){(void)name;return NULL;}
BOOL WLog_IsLevelActive(wLog* log,DWORD level){(void)log;(void)level;return FALSE;}
BOOL WLog_PrintTextMessage(wLog* log,DWORD level,size_t line,const char* file,const char* function,const char* fmt,...){(void)log;(void)level;(void)line;(void)file;(void)function;(void)fmt;return TRUE;}
BOOL WLog_PrintTextMessageVA(wLog* log,DWORD level,size_t line,const char* file,const char* function,const char* fmt,va_list args){(void)log;(void)level;(void)line;(void)file;(void)function;(void)fmt;(void)args;return TRUE;}
void winpr_log_backtrace(const char* tag,DWORD level,DWORD size){(void)tag;(void)level;(void)size;}
void winpr_log_backtrace_ex(wLog* log,DWORD level,DWORD size){(void)log;(void)level;(void)size;}
static void packet_header(uint8_t* packet,uint16_t type,uint32_t length){put16(packet,type);put16(packet+2,CB_RESPONSE_OK);put32(packet+4,length);}
static wStream* begin_payload(wStream* stream,uint8_t* packet,size_t total){
    RdpTransferIngress ingress={0};assert(rdpsync_transfer_ingress(&ingress,packet,total,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,total));
    return Stream_StaticInit(stream,packet+8,total-8);
}
int main(void){
    uint8_t packet[TF_MANIFEST_MAX+12]={0};wStream storage;
    /* xrdp clipboard_send_data_request(): declared4 + one trailing zeroDWORD. */
    packet_header(packet,CB_FORMAT_DATA_REQUEST,4);put32(packet+8,TF_FORMAT_ID);
    CLIPRDR_FORMAT_DATA_REQUEST request={.common={.dataLen=4}};
    wStream* stream=begin_payload(&storage,packet,16);assert(!cliprdr_read_format_data_request(stream,&request));
    assert(request.requestedFormatId==TF_FORMAT_ID&&Stream_GetRemainingLength(stream)==4);
    /* Actual descriptor parser preserves declared bytes; application validation
     * sees neither an extra file nor trailer bytes appended to the manifest. */
    RdpTransferFile file={.name={'o','n','e','.','b','i','n'},.size=4},parsed[RDPSYNC_TRANSFER_FILES];
    memset(packet,0,sizeof(packet));size_t length=rdpsync_transfer_write_manifest(&file,1,packet+8,sizeof(packet)-12);assert(length==596);
    packet_header(packet,CB_FORMAT_DATA_RESPONSE,(uint32_t)length);
    CLIPRDR_FORMAT_DATA_RESPONSE descriptor={.common={.dataLen=(UINT32)length}};
    stream=begin_payload(&storage,packet,length+12);assert(!cliprdr_read_format_data_response(stream,&descriptor));
    size_t count=0;uint64_t total=0;assert(!rdpsync_transfer_parse_manifest(descriptor.requestedFormatData,descriptor.common.dataLen,parsed,&count,&total));
    assert(count==1&&total==4&&Stream_GetRemainingLength(stream)==4);
    /* xrdp's RANGE request explicitly carries a zero optional clipDataId even
     * when CAN_LOCK_CLIPDATA was not negotiated. Upstream marks it present. */
    memset(packet,0,40);packet_header(packet,CB_FILECONTENTS_REQUEST,28);put32(packet+8,17);put32(packet+16,FILECONTENTS_RANGE);put32(packet+28,4);
    CLIPRDR_FILE_CONTENTS_REQUEST contents={.common={.dataLen=28}};
    stream=begin_payload(&storage,packet,40);assert(!cliprdr_read_file_contents_request(stream,&contents));
    assert(contents.streamId==17&&contents.haveClipDataId&&contents.clipDataId==0&&contents.cbRequested==4&&Stream_GetRemainingLength(stream)==4);
    put32(packet+16,FILECONTENTS_SIZE);put32(packet+28,0);stream=begin_payload(&storage,packet,40);
    assert(cliprdr_read_file_contents_request(stream,&contents)==ERROR_BAD_ARGUMENTS); /* never weaken SIZE8 */
    put32(packet+28,8);stream=begin_payload(&storage,packet,40);assert(!cliprdr_read_file_contents_request(stream,&contents));
    /* A full requested64KiB body plus trailer remains a64KiB parser response. */
    size_t wire=RDPSYNC_TRANSFER_CHUNK+16;uint8_t* large=calloc(1,wire);assert(large);
    packet_header(large,CB_FILECONTENTS_RESPONSE,RDPSYNC_TRANSFER_CHUNK+4);put32(large+8,19);memset(large+12,0xab,RDPSYNC_TRANSFER_CHUNK);
    CLIPRDR_FILE_CONTENTS_RESPONSE response={.common={.dataLen=RDPSYNC_TRANSFER_CHUNK+4}};
    stream=begin_payload(&storage,large,wire);assert(!cliprdr_read_file_contents_response(stream,&response));
    assert(response.streamId==19&&response.cbRequested==RDPSYNC_TRANSFER_CHUNK&&response.requestedData[RDPSYNC_TRANSFER_CHUNK-1]==0xab&&Stream_GetRemainingLength(stream)==4);
    RdpTransferIngress ingress={0};large[wire-1]=1;assert(!rdpsync_transfer_ingress(&ingress,large,wire,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,wire));free(large);
    puts("pinned upstream CLIPRDR/WinPR parsing: exact padded metadata/range/64KiB responses, optional zero-ID and strict SIZE8 PASS");
}
