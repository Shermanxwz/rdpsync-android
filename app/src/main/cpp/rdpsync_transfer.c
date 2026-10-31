#define _GNU_SOURCE
#include "rdpsync_transfer.h"
#include "rdpsync_workspace.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <winpr/error.h>
#include <freerdp/channels/channels.h>
#define TF_FORMAT_ID 0xc001U
#define TF_DESCRIPTOR 592U
#define TF_MANIFEST_MAX (4U + TF_DESCRIPTOR * RDPSYNC_TRANSFER_FILES)
#define TF_MAX_LOCKS 16
struct RdpTransfer {
    pthread_mutex_t mutex;
    uint64_t generation;
    int enabled, ready, peer_files, peer_lock, capability, error;
    CliprdrClientContext* context;
    RdpTransferFile offered[RDPSYNC_TRANSFER_FILES], received[RDPSYNC_TRANSFER_FILES];
    int fds[RDPSYNC_TRANSFER_FILES];
    size_t offer_count, receive_count;
    uint64_t offer_token, receive_token, served_bytes, receive_total;
    uint64_t send_remaining, control_remaining, incoming_control_remaining;
    uint32_t retired_request_id, retired_request_length, admitted_stream_id;
    int data_admitted, format_admitted;
    uint64_t active_token;
    int active_reads;
    uint32_t locks[TF_MAX_LOCKS], retired_locks[RDPSYNC_TRANSFER_FILES]; size_t lock_count, retired_count;
    uint64_t retired_tokens[RDPSYNC_TRANSFER_FILES];
    int ever_offered, require_lock;
    uint32_t remote_format, remote_clip_id, unlock_id, request_id;
    int advertise, request_list, list_inflight, list_valid, request_chunk, phase;
    size_t request_index;
    uint64_t request_offset;
    uint32_t request_length;
    uint8_t chunk[RDPSYNC_TRANSFER_CHUNK]; size_t chunk_length;
    uint64_t deadline_ms;
};
static atomic_uint_fast64_t next_token = 1;
static atomic_uint_fast64_t next_wire_id = 1;
static uint64_t token_new(void) { uint64_t n=atomic_fetch_add(&next_token,1); return n && n <= INT64_MAX ? n : 0; }
static uint32_t wire_id_new(void) { uint64_t n=atomic_fetch_add(&next_wire_id,1); return n && n <= UINT32_MAX ? (uint32_t)n : 0; }
static uint64_t now_ms(void) { struct timespec t; clock_gettime(CLOCK_BOOTTIME,&t); return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000; }
static int uuid_valid(const char* value) {
    if(!value||strlen(value)!=36)return 0;
    for(size_t i=0;i<36;i++) {
        if(i==8||i==13||i==18||i==23){if(value[i]!='-')return 0;}
        else if(!((value[i]>='0'&&value[i]<='9')||(value[i]>='a'&&value[i]<='f')))return 0;
    }
    return 1;
}
static int owned_directory_fd(int fd) {
    struct stat st;return fd>=0&&!fstat(fd,&st)&&S_ISDIR(st.st_mode)&&st.st_uid==geteuid()&&!(st.st_mode&S_IWOTH);
}
/* Walk without following even an intermediate symlink. O_PATH needs search,
 * not directory-list permission: Android app UIDs cannot read /data itself.
 * Only the final app-cache directory must be owned by our UID. */
static int open_directory_path(char* path) {
    if(!path||path[0]!='/'||!path[1])return -1;
    int fd=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);if(fd<0)return -1;
    char* save=NULL;
    for(char* component=strtok_r(path+1,"/",&save);component;component=strtok_r(NULL,"/",&save)) {
        if(!strcmp(component,".")||!strcmp(component,"..")){close(fd);return -1;}
        int next=openat(fd,component,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);close(fd);if(next<0)return -1;fd=next;
    }
    return fd;
}
static int open_staged_bounded(const char* runtime_cache,const char* directory_id,const char* file_id,uint64_t expected_size,uint64_t maximum) {
    if(!uuid_valid(directory_id)||!uuid_valid(file_id)||expected_size>maximum)return TF_INVALID;
    if(!runtime_cache||!runtime_cache[0])return TF_UNAVAILABLE;
    size_t length=strnlen(runtime_cache,PATH_MAX);if(length>=PATH_MAX)return TF_INVALID;
    char parent[PATH_MAX],filename[41];memcpy(parent,runtime_cache,length+1);
    char* slash=strrchr(parent,'/');if(!slash||slash==parent)return TF_INVALID;*slash='\0';
    int root=open_directory_path(parent),base=-1,directory=-1,file=-1;
    if(!owned_directory_fd(root))goto done;
    base=openat(root,"rdpsync-transfer",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(!owned_directory_fd(base))goto done;
    directory=openat(base,directory_id,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(!owned_directory_fd(directory))goto done;
    memcpy(filename,file_id,36);memcpy(filename+36,".bin",5);
    /* NONBLOCK prevents a replaced FIFO/device from blocking before fstat. */
    file=openat(directory,filename,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);
    struct stat st;
    int flags=file>=0?fcntl(file,F_GETFL):-1;
    if(file>=0&&(fstat(file,&st)||!S_ISREG(st.st_mode)||st.st_uid!=geteuid()||st.st_nlink!=1||
        st.st_size<0||(uint64_t)st.st_size!=expected_size||flags<0||fcntl(file,F_SETFL,flags&~O_NONBLOCK)<0)){
        close(file);file=-1;
    }
done:
    if(directory>=0)close(directory);if(base>=0)close(base);if(root>=0)close(root);
    return file>=0?file:TF_IO;
}
/* Separate fixed ceilings: Shared Space does not enlarge the CLIPRDR offer,
 * ingress, connection-response budget, or its existing staging entrypoint. */
int rdpsync_transfer_open_staged(const char* cache,const char* directory,const char* file,uint64_t size) {
    return open_staged_bounded(cache,directory,file,size,RDPSYNC_TRANSFER_FILE_BYTES);
}
int rdpsync_sharedspace_open_staged(const char* cache,const char* directory,const char* file,uint64_t size) {
    return open_staged_bounded(cache,directory,file,size,RDP_WS_MAX_FILE_BYTES);
}
static uint16_t get16(const uint8_t* p) { return (uint16_t)(p[0]|(uint16_t)p[1]<<8); }
static uint32_t get32(const uint8_t* p) { return (uint32_t)get16(p)|(uint32_t)get16(p+2)<<16; }
/* xrdp v0.10.6.1 clipboard.c / clipboard_file.c append one zero DWORD
 * outside dataLen on these message shapes. Accept ONLY that bounded trailer;
 * it is not payload and never increases admitted descriptor/chunk lengths. */
static int zero_trailer_shape(uint16_t type,uint32_t length) {
    switch(type) {
        case CB_CLIP_CAPS:return length==16;
        case CB_MONITOR_READY:case CB_FORMAT_LIST_RESPONSE:return length==0;
        case CB_FORMAT_DATA_REQUEST:return length==4;
        case CB_FORMAT_LIST:return 1; /* variable serialized format records */
        case CB_FORMAT_DATA_RESPONSE:return length<=TF_MANIFEST_MAX;
        case CB_FILECONTENTS_REQUEST:return length==28;
        case CB_FILECONTENTS_RESPONSE:return length>=4&&length<=RDPSYNC_TRANSFER_CHUNK+4;
        default:return 0;
    }
}
static int wire_payload_length(const uint8_t* data,size_t size,size_t total,uint32_t* length) {
    if(!data||size<8||total<8||total>RDPSYNC_TRANSFER_MESSAGE_MAX)return 0;
    uint32_t declared=get32(data+4);
    if(declared>total-8)return 0;
    size_t extra=total-8-declared;
    if(extra&&!(extra==4&&zero_trailer_shape(get16(data),declared)))return 0;
    *length=declared;return 1;
}
int rdpsync_transfer_ingress(RdpTransferIngress* s,const uint8_t* data,size_t size,uint32_t flags,size_t total) {
    if(!s)return 0;
    if(flags&(CHANNEL_FLAG_SUSPEND|CHANNEL_FLAG_RESUME))return 1;
    if(!data||total<8||total>RDPSYNC_TRANSFER_MESSAGE_MAX||!size||size>total)return 0;
    if(flags&CHANNEL_FLAG_FIRST) {
        uint32_t declared=0;
        if(s->active||s->messages>=RDPSYNC_TRANSFER_MAX_MESSAGES||!wire_payload_length(data,size,total,&declared))return 0;
        s->active=1;s->total=total;s->received=0;s->payload_end=(size_t)declared+8;
    }
    if(!s->active||total!=s->total||size>s->total-s->received)return 0;
    /* Validate a split trailer BEFORE forwarding the completing fragment to
     * upstream's async queue. Earlier incomplete reassembly stays size-capped. */
    size_t padding_start=s->received<s->payload_end?s->payload_end-s->received:0;
    for(size_t i=padding_start;i<size;i++)if(data[i]!=0)return 0;
    s->received+=size;
    if(flags&CHANNEL_FLAG_LAST){if(s->received!=s->total)return 0;s->active=0;s->received=0;s->total=0;s->payload_end=0;++s->messages;}
    return 1;
}
/* The generic static-channel worker queues completed PDUs before callbacks.
 * Only explicitly requested data gets a one-message admission slot; other
 * messages share a finite connection-lifetime control-byte budget. */
int rdpsync_transfer_admit_incoming(RdpTransfer* t,const uint8_t* p,size_t size,size_t total) {
    uint32_t payload=0;
    if(!t||!wire_payload_length(p,size,total,&payload))return 0;
    uint16_t type=get16(p);pthread_mutex_lock(&t->mutex);int okay=0;
    if(type==CB_FILECONTENTS_RESPONSE) {
        if(size>=12&&payload>=4&&!t->data_admitted) {
            uint32_t id=get32(p+8),length=0;
            if(t->phase==3&&!t->request_chunk&&id==t->request_id)length=t->request_length;
            else if(id&&id==t->retired_request_id)length=t->retired_request_length;
            if(length&&payload-4<=length){t->data_admitted=1;t->admitted_stream_id=id;okay=1;}
        }
    } else if(type==CB_FORMAT_DATA_RESPONSE) {
        if(t->list_inflight&&!t->format_admitted&&payload<=TF_MANIFEST_MAX){t->format_admitted=1;okay=1;}
    } else if(total<=t->incoming_control_remaining) {
        t->incoming_control_remaining-=total;okay=1;
    }
    if(!okay)t->error=TF_INVALID;
    pthread_mutex_unlock(&t->mutex);return okay;
}
static void put16(uint8_t* p,uint16_t n) { p[0]=(uint8_t)n; p[1]=(uint8_t)(n>>8); }
static void put32(uint8_t* p,uint32_t n) { put16(p,(uint16_t)n); put16(p+2,(uint16_t)(n>>16)); }
static int name_equal(const uint16_t* a,const uint16_t* b) {
    for(size_t i=0;i<=RDPSYNC_TRANSFER_NAME;i++) {
        uint16_t x=a[i],y=b[i]; if(x>='A'&&x<='Z')x+=32; if(y>='A'&&y<='Z')y+=32;
        if(x!=y)return 0; if(!x)return 1;
    } return 0;
}
int rdpsync_transfer_name_valid(const uint16_t* n,size_t len) {
    if(!n||!len||len>RDPSYNC_TRANSFER_NAME||n[len-1]==' '||n[len-1]=='.')return 0;
    char stem[8]={0}; size_t stem_len=0;
    for(size_t i=0;i<len;i++) {
        uint16_t c=n[i];
        if(!c||c<32||(c>=127&&c<=159)||c=='/'||c=='\\'||c==':'||c=='<'||c=='>'||c=='"'||c=='|'||c=='?'||c=='*'||
           (c>=0x200b&&c<=0x200f)||(c>=0x202a&&c<=0x202e)||(c>=0x2060&&c<=0x206f)||c==0xfeff||
           (c>=0xfff9&&c<=0xfffb))return 0;
        if(c>=0xd800&&c<=0xdbff) { if(i+1>=len||n[i+1]<0xdc00||n[i+1]>0xdfff)return 0; ++i; }
        else if(c>=0xdc00&&c<=0xdfff)return 0;
    }
    for(size_t i=0;i<len&&n[i]!='.'&&stem_len<sizeof(stem)-1;i++) {
        uint16_t c=n[i]; stem[stem_len++]=(c<128)?(char)toupper((unsigned char)c):'#';
    }
    if(!strcmp(stem,"CON")||!strcmp(stem,"PRN")||!strcmp(stem,"AUX")||!strcmp(stem,"NUL")||
       (stem_len==4&&(!strncmp(stem,"COM",3)||!strncmp(stem,"LPT",3))&&stem[3]>='1'&&stem[3]<='9'))return 0;
    return 1;
}
static size_t name_length(const uint16_t* name) { size_t n=0; while(n<=RDPSYNC_TRANSFER_NAME&&name[n])++n;return n; }
static int files_valid(const RdpTransferFile* f,size_t count,uint64_t* total) {
    if(!f||!count||count>RDPSYNC_TRANSFER_FILES)return 0; *total=0;
    for(size_t i=0;i<count;i++) {
        if(!rdpsync_transfer_name_valid(f[i].name,name_length(f[i].name))||f[i].size>RDPSYNC_TRANSFER_FILE_BYTES||
           f[i].size>RDPSYNC_TRANSFER_BATCH_BYTES-*total)return 0;
        for(size_t j=0;j<i;j++)if(name_equal(f[i].name,f[j].name))return 0;
        *total+=f[i].size;
    } return 1;
}
int rdpsync_transfer_parse_manifest(const uint8_t* p,size_t len,RdpTransferFile* f,size_t* count,uint64_t* total) {
    if(!p||!f||!count||!total||len<4||len>TF_MANIFEST_MAX)return TF_INVALID;
    size_t n=get32(p); if(!n||n>RDPSYNC_TRANSFER_FILES||len!=4+n*TF_DESCRIPTOR)return TF_INVALID;
    memset(f,0,RDPSYNC_TRANSFER_FILES*sizeof(*f));
    for(size_t i=0;i<n;i++) {
        const uint8_t* d=p+4+i*TF_DESCRIPTOR; uint32_t flags=get32(d),attrs=get32(d+36);
        if(!(flags&0x40)||!(flags&4)||(attrs&0x10)||(attrs&0x400))return TF_INVALID; /* known size, regular, no reparse */
        f[i].size=(uint64_t)get32(d+64)<<32|get32(d+68);
        size_t z=0; for(;z<260;z++) { uint16_t c=get16(d+72+z*2); if(!c)break; if(z>=RDPSYNC_TRANSFER_NAME)return TF_INVALID; f[i].name[z]=c; }
        if(z==260)return TF_INVALID;
    }
    if(!files_valid(f,n,total))return TF_INVALID; *count=n; return 0;
}
size_t rdpsync_transfer_write_manifest(const RdpTransferFile* f,size_t count,uint8_t* out,size_t size) {
    uint64_t total=0; if(!out||!files_valid(f,count,&total)||size<4+count*TF_DESCRIPTOR)return 0;
    size_t n=4+count*TF_DESCRIPTOR; memset(out,0,n);put32(out,(uint32_t)count);
    for(size_t i=0;i<count;i++) { uint8_t* d=out+4+i*TF_DESCRIPTOR;put32(d,0x44);put32(d+36,0x80);put32(d+64,(uint32_t)(f[i].size>>32));put32(d+68,(uint32_t)f[i].size);for(size_t j=0;f[i].name[j];j++)put16(d+72+j*2,f[i].name[j]); }
    return n;
}
static void close_offer(RdpTransfer* t) {
    for(size_t i=0;i<t->offer_count;i++) { if(t->fds[i]>=0)close(t->fds[i]);t->fds[i]=-1; }
    t->offer_count=0;t->offer_token=0;t->lock_count=0;t->advertise=1;
}
static void clear_receive(RdpTransfer* t,int error) {
    if(t->remote_clip_id)t->unlock_id=t->remote_clip_id;
    if(t->phase==3&&t->request_id&&!t->request_chunk){t->retired_request_id=t->request_id;t->retired_request_length=t->request_length;}
    t->remote_clip_id=0;t->receive_token=0;t->receive_count=0;t->receive_total=0;
    t->request_list=0;t->request_chunk=0;t->list_valid=0;t->request_id=0;t->chunk_length=0;t->phase=0;t->error=error;
    /* An untagged outstanding FormatDataResponse must be drained, never
     * mistaken for a new explicit request after cancellation/replacement. */
}
RdpTransfer* rdpsync_transfer_new(uint64_t gen,int enabled) {
    RdpTransfer* t=calloc(1,sizeof(*t));if(!t)return NULL;
    pthread_mutex_init(&t->mutex,NULL);t->send_remaining=RDPSYNC_TRANSFER_SEND_BYTES;t->control_remaining=RDPSYNC_TRANSFER_CONTROL_BYTES;t->incoming_control_remaining=RDPSYNC_TRANSFER_CONTROL_BYTES;t->generation=gen;t->enabled=enabled;t->capability=enabled?0:-2;
    for(size_t i=0;i<RDPSYNC_TRANSFER_FILES;i++)t->fds[i]=-1;return t;
}
void rdpsync_transfer_free(RdpTransfer* t) { if(!t)return;close_offer(t);pthread_mutex_destroy(&t->mutex);free(t); }
/* Admit before Client* methods allocate/queue their packet. This is a finite
 * CONNECTION lifetime payload/control budget, not a claim about total process
 * memory. No cancel/reoffer/retry replenishes credit; only teardown does. */
static UINT control_admit_locked(RdpTransfer* t,uint64_t bytes) {
    if(bytes>t->control_remaining){t->error=TF_QUOTA;return ERROR_NOT_ENOUGH_QUOTA;}
    t->control_remaining-=bytes;return CHANNEL_RC_OK;
}
static UINT control_admit(RdpTransfer* t,uint64_t bytes) {
    pthread_mutex_lock(&t->mutex);UINT rc=control_admit_locked(t,bytes);pthread_mutex_unlock(&t->mutex);return rc;
}
static RdpTransfer* transfer_from(CliprdrClientContext* c) { return c?c->custom:NULL; }
static UINT caps_callback(CliprdrClientContext* c,const CLIPRDR_CAPABILITIES* caps) {
    RdpTransfer* t=transfer_from(c); if(!t||!caps)return ERROR_INVALID_PARAMETER;
    /* Pinned FreeRDP invokes this once per parsed GENERAL set, with one
     * fixed, host-endian structure (not an untrusted serialized array). */
    if(caps->cCapabilitiesSets!=1||!caps->capabilitySets||
       caps->capabilitySets->capabilitySetType!=CB_CAPSTYPE_GENERAL||
       caps->capabilitySets->capabilitySetLength!=CB_CAPSTYPE_GENERAL_LEN)return ERROR_INVALID_DATA;
    const CLIPRDR_GENERAL_CAPABILITY_SET* g=(const void*)caps->capabilitySets;
    int files=!!(g->generalFlags&CB_STREAM_FILECLIP_ENABLED),lock=!!(g->generalFlags&CB_CAN_LOCK_CLIPDATA);
    pthread_mutex_lock(&t->mutex);t->peer_files=files;t->peer_lock=lock;t->capability=t->ready?(files?1:-1):0;pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
static UINT ready_callback(CliprdrClientContext* c,const CLIPRDR_MONITOR_READY* p) {
    (void)p;RdpTransfer* t=transfer_from(c);if(!t)return ERROR_INVALID_PARAMETER;
    CLIPRDR_GENERAL_CAPABILITY_SET general={CB_CAPSTYPE_GENERAL,CB_CAPSTYPE_GENERAL_LEN,CB_CAPS_VERSION_2,
        CB_USE_LONG_FORMAT_NAMES|CB_STREAM_FILECLIP_ENABLED|CB_FILECLIP_NO_FILE_PATHS|CB_CAN_LOCK_CLIPDATA};
    CLIPRDR_CAPABILITIES caps={.cCapabilitiesSets=1,.capabilitySets=(void*)&general};
    UINT rc=control_admit(t,24);if(rc)return rc;rc=c->ClientCapabilities(c,&caps);if(rc)return rc;
    pthread_mutex_lock(&t->mutex);t->ready=1;t->capability=t->peer_files?1:-1;t->advertise=1;pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
static UINT formats_callback(CliprdrClientContext* c,const CLIPRDR_FORMAT_LIST* f) {
    RdpTransfer* t=transfer_from(c);if(!t||!f)return ERROR_INVALID_PARAMETER;uint32_t format=0;
    if(f->numFormats<=256&&(!f->numFormats||f->formats))for(UINT32 i=0;i<f->numFormats;i++)
        if(f->formats[i].formatName&&!strcmp(f->formats[i].formatName,"FileGroupDescriptorW"))format=f->formats[i].formatId;
    pthread_mutex_lock(&t->mutex);t->remote_format=format;if(t->receive_token)clear_receive(t,TF_STALE);pthread_mutex_unlock(&t->mutex);
    UINT rc=control_admit(t,8);if(rc)return rc;CLIPRDR_FORMAT_LIST_RESPONSE response={.common={.msgFlags=CB_RESPONSE_OK}};return c->ClientFormatListResponse(c,&response);
}
static UINT format_reply_callback(CliprdrClientContext* c,const CLIPRDR_FORMAT_LIST_RESPONSE* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;
    if(p->common.msgFlags!=CB_RESPONSE_OK){pthread_mutex_lock(&t->mutex);t->error=TF_UNAVAILABLE;pthread_mutex_unlock(&t->mutex);}return CHANNEL_RC_OK;
}
static UINT format_request_callback(CliprdrClientContext* c,const CLIPRDR_FORMAT_DATA_REQUEST* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;uint8_t data[TF_MANIFEST_MAX];size_t n=0;
    /* Names are private offer data too. Authorize and enqueue their bounded
     * copy atomically with cancellation, just like file-content handoff. This
     * does not perform file I/O or wait for socket drainage. */
    pthread_mutex_lock(&t->mutex);if(t->ready&&t->offer_token&&p->requestedFormatId==TF_FORMAT_ID)n=rdpsync_transfer_write_manifest(t->offered,t->offer_count,data,sizeof(data));
    UINT rc=control_admit_locked(t,8+n);
    if(!rc){CLIPRDR_FORMAT_DATA_RESPONSE response={.common={.msgFlags=n?CB_RESPONSE_OK:CB_RESPONSE_FAIL,.dataLen=(UINT32)n},.requestedFormatData=n?data:NULL};rc=c->ClientFormatDataResponse(c,&response);}
    pthread_mutex_unlock(&t->mutex);return rc;
}
static UINT format_data_callback(CliprdrClientContext* c,const CLIPRDR_FORMAT_DATA_RESPONSE* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&t->mutex);
    if(!t->list_inflight){pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;}
    t->list_inflight=0;t->format_admitted=0;
    if(t->receive_token&&t->list_valid&&t->phase==1) {
        int rc=p->common.msgFlags!=CB_RESPONSE_OK?TF_INVALID:rdpsync_transfer_parse_manifest(p->requestedFormatData,p->common.dataLen,t->received,&t->receive_count,&t->receive_total);
        t->error=rc;t->phase=rc?5:2;
    }
    pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
static UINT lock_callback(CliprdrClientContext* c,const CLIPRDR_LOCK_CLIPBOARD_DATA* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;pthread_mutex_lock(&t->mutex);
    if(t->offer_token) { size_t i=0;for(;i<t->lock_count;i++)if(t->locks[i]==p->clipDataId)break;
        size_t seen=0;for(;seen<t->retired_count;seen++)if(t->retired_locks[seen]==p->clipDataId)break;
        int same=seen<t->retired_count&&t->retired_tokens[seen]==t->offer_token;
        if(i==t->lock_count&&t->lock_count<TF_MAX_LOCKS&&(same||seen==t->retired_count)&&
           (same||t->retired_count<RDPSYNC_TRANSFER_FILES)) {
            t->locks[t->lock_count++]=p->clipDataId;
            if(!same){t->retired_locks[t->retired_count]=p->clipDataId;t->retired_tokens[t->retired_count++]=t->offer_token;}
        } }
    pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
static UINT unlock_callback(CliprdrClientContext* c,const CLIPRDR_UNLOCK_CLIPBOARD_DATA* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;pthread_mutex_lock(&t->mutex);
    for(size_t i=0;i<t->lock_count;i++)if(t->locks[i]==p->clipDataId){t->locks[i]=t->locks[--t->lock_count];break;}
    pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
static UINT contents_request_callback(CliprdrClientContext* c,const CLIPRDR_FILE_CONTENTS_REQUEST* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;
    int fd=-1,valid=0;uint64_t size=0,token=0,offset=(uint64_t)p->nPositionHigh<<32|p->nPositionLow;uint32_t length=0;
    pthread_mutex_lock(&t->mutex);
    /* xrdp sends an optional zero ID without negotiating CAN_LOCK_CLIPDATA.
     * Treat only that sentinel as absent, and only for the first snapshot. */
    int lock_ok=!t->require_lock&&(!p->haveClipDataId||(!t->peer_lock&&p->clipDataId==0));
    if(p->haveClipDataId&&t->peer_lock)for(size_t i=0;i<t->lock_count;i++)if(t->locks[i]==p->clipDataId)lock_ok=1;
    if(t->ready&&t->offer_token&&p->listIndex<t->offer_count&&lock_ok&&!t->active_reads) {
        size=t->offered[p->listIndex].size;token=t->offer_token;
        if(p->dwFlags==FILECONTENTS_SIZE&&p->cbRequested==8&&offset==0) { valid=1;length=8; }
        else if(p->dwFlags==FILECONTENTS_RANGE&&p->cbRequested>0&&p->cbRequested<=RDPSYNC_TRANSFER_PEER_RANGE&&offset<=size) {
            length=(uint32_t)((size-offset<p->cbRequested)?size-offset:p->cbRequested);fd=fcntl(t->fds[p->listIndex],F_DUPFD_CLOEXEC,0);valid=fd>=0;
        }
        if(valid&&p->dwFlags==FILECONTENTS_RANGE) {
            if(length>t->send_remaining){t->error=TF_QUOTA;pthread_mutex_unlock(&t->mutex);if(fd>=0)close(fd);return ERROR_NOT_ENOUGH_QUOTA;}
            t->send_remaining-=length;
        }
        if(valid){t->active_reads=1;t->active_token=token;}
    }
    UINT admission=control_admit_locked(t,p->dwFlags==FILECONTENTS_SIZE&&valid?20:12);
    if(admission&&t->active_token==token){t->active_reads=0;t->active_token=0;}
    pthread_mutex_unlock(&t->mutex);
    if(admission){if(fd>=0)close(fd);return admission;}
    uint8_t* bytes=valid?malloc(length?length:1):NULL;if(valid&&!bytes)valid=0;
    if(valid&&p->dwFlags==FILECONTENTS_SIZE){put32(bytes,(uint32_t)size);put32(bytes+4,(uint32_t)(size>>32));}
    if(valid&&fd>=0){struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||(uint64_t)st.st_size!=size)valid=0;
        for(uint32_t done=0;valid&&done<length;) {
            pthread_mutex_lock(&t->mutex);int current=t->offer_token==token;pthread_mutex_unlock(&t->mutex);if(!current){valid=0;break;}
            size_t chunk=length-done;if(chunk>RDPSYNC_TRANSFER_CHUNK)chunk=RDPSYNC_TRANSFER_CHUNK;
            ssize_t n=pread(fd,bytes+done,chunk,(off_t)(offset+done));if(n<0&&errno==EINTR)continue;if(n<=0){valid=0;break;}done+=(uint32_t)n;
        }
    }
    if(fd>=0)close(fd);
    pthread_mutex_lock(&t->mutex);if(t->offer_token!=token)valid=0;
    CLIPRDR_FILE_CONTENTS_RESPONSE response={.common={.msgFlags=valid?CB_RESPONSE_OK:CB_RESPONSE_FAIL},.streamId=p->streamId,.cbRequested=valid?length:0,.requestedData=valid?bytes:NULL};
    /* Queue handoff is bounded memory copying, not socket-drain I/O. Serialize
     * the last token check with this handoff so cancel returning guarantees no
     * later successful payload enqueue from this lease (already queued bytes cannot be
     * retracted). Never hold the bridge's global/render lock here. */
    UINT rc=c->ClientFileContentsResponse(c,&response);
    if(!rc&&valid&&p->dwFlags==FILECONTENTS_RANGE)t->served_bytes+=length;
    if(t->active_token==token){t->active_reads=0;t->active_token=0;}
    pthread_mutex_unlock(&t->mutex);
    free(bytes);return rc;
}
static UINT contents_response_callback(CliprdrClientContext* c,const CLIPRDR_FILE_CONTENTS_RESPONSE* p) {
    RdpTransfer* t=transfer_from(c);if(!t||!p)return ERROR_INVALID_PARAMETER;pthread_mutex_lock(&t->mutex);
    if(t->admitted_stream_id==p->streamId){t->data_admitted=0;t->admitted_stream_id=0;}
    if(t->retired_request_id==p->streamId){t->retired_request_id=0;t->retired_request_length=0;}
    if(t->receive_token&&t->phase==3&&t->request_id==p->streamId) {
        if(p->common.msgFlags!=CB_RESPONSE_OK||p->cbRequested!=t->request_length||p->cbRequested>RDPSYNC_TRANSFER_CHUNK||(!p->requestedData&&p->cbRequested)) {t->phase=5;t->error=TF_INVALID;}
        else {memcpy(t->chunk,p->requestedData,p->cbRequested);t->chunk_length=p->cbRequested;t->phase=4;}
        t->request_id=0;
    }pthread_mutex_unlock(&t->mutex);return CHANNEL_RC_OK;
}
void rdpsync_transfer_attach(RdpTransfer* t,CliprdrClientContext* c) {
    if(!t||!c||!t->enabled)return;pthread_mutex_lock(&t->mutex);t->context=c;t->capability=0;c->custom=t;
    c->ServerCapabilities=caps_callback;c->MonitorReady=ready_callback;c->ServerFormatList=formats_callback;c->ServerFormatListResponse=format_reply_callback;
    c->ServerFormatDataRequest=format_request_callback;c->ServerFormatDataResponse=format_data_callback;
    c->ServerLockClipboardData=lock_callback;c->ServerUnlockClipboardData=unlock_callback;
    c->ServerFileContentsRequest=contents_request_callback;c->ServerFileContentsResponse=contents_response_callback;pthread_mutex_unlock(&t->mutex);
}
void rdpsync_transfer_detach(RdpTransfer* t,CliprdrClientContext* c) {
    if(!t)return;pthread_mutex_lock(&t->mutex);if(!c||t->context==c){if(c)c->custom=NULL;t->context=NULL;t->ready=0;t->capability=-1;close_offer(t);clear_receive(t,TF_DISCONNECTED);}pthread_mutex_unlock(&t->mutex);
}
UINT rdpsync_transfer_pump(RdpTransfer* t) {
    if(!t)return CHANNEL_RC_OK;pthread_mutex_lock(&t->mutex);CliprdrClientContext* c=t->context;UINT rc=CHANNEL_RC_OK;
    if(!c||!t->ready){pthread_mutex_unlock(&t->mutex);return rc;}
    if((t->phase==1||t->phase==3)&&now_ms()>t->deadline_ms){if(t->phase==3&&!t->request_chunk){t->retired_request_id=t->request_id;t->retired_request_length=t->request_length;}t->phase=5;t->error=TF_TIMEOUT;t->list_valid=0;t->request_list=0;t->request_chunk=0;t->request_id=0;}
    if(t->unlock_id){CLIPRDR_UNLOCK_CLIPBOARD_DATA p={.clipDataId=t->unlock_id};t->unlock_id=0;rc=control_admit_locked(t,12);if(!rc)rc=c->ClientUnlockClipboardData(c,&p);}
    if(!rc&&t->advertise){CLIPRDR_FORMAT f={TF_FORMAT_ID,"FileGroupDescriptorW"};CLIPRDR_FORMAT_LIST p={.numFormats=t->offer_count?1:0,.formats=t->offer_count?&f:NULL};t->advertise=0;rc=control_admit_locked(t,t->offer_count?128:8);if(!rc)rc=c->ClientFormatList(c,&p);}
    if(!rc&&t->request_list){t->request_list=0;
        if(t->peer_lock){t->remote_clip_id=wire_id_new();CLIPRDR_LOCK_CLIPBOARD_DATA p={.clipDataId=t->remote_clip_id};rc=control_admit_locked(t,12);if(!rc)rc=t->remote_clip_id?c->ClientLockClipboardData(c,&p):ERROR_INVALID_DATA;}
        if(!rc){CLIPRDR_FORMAT_DATA_REQUEST p={.requestedFormatId=t->remote_format};rc=control_admit_locked(t,12);if(!rc){t->list_inflight=1;rc=c->ClientFormatDataRequest(c,&p);}}
    }
    if(!rc&&t->request_chunk){t->request_chunk=0;CLIPRDR_FILE_CONTENTS_REQUEST p={.streamId=t->request_id,.listIndex=(UINT32)t->request_index,.dwFlags=FILECONTENTS_RANGE,.nPositionLow=(UINT32)t->request_offset,.nPositionHigh=(UINT32)(t->request_offset>>32),.cbRequested=t->request_length,.haveClipDataId=t->remote_clip_id!=0,.clipDataId=t->remote_clip_id};rc=control_admit_locked(t,40);if(!rc)rc=c->ClientFileContentsRequest(c,&p);}
    if(rc){t->phase=5;t->error=rc==ERROR_NOT_ENOUGH_QUOTA?TF_QUOTA:TF_IO;}pthread_mutex_unlock(&t->mutex);return rc;
}
void rdpsync_transfer_state(RdpTransfer* t,int64_t v[10]) {memset(v,0,10*sizeof(*v));if(!t){v[1]=-2;return;}pthread_mutex_lock(&t->mutex);int64_t a[10]={(int64_t)t->generation,t->capability,(int64_t)t->offer_token,(int64_t)t->served_bytes,(int64_t)t->receive_token,t->phase,(int64_t)t->receive_count,(int64_t)t->receive_total,t->error,(int64_t)t->send_remaining};memcpy(v,a,sizeof(a));pthread_mutex_unlock(&t->mutex);}
int64_t rdpsync_transfer_offer(RdpTransfer* t,uint64_t gen,const RdpTransferFile* files,const int* fds,size_t count) {
    if(!t)return TF_DISCONNECTED;
    pthread_mutex_lock(&t->mutex);int early=t->generation!=gen?TF_STALE:t->capability!=1?TF_UNAVAILABLE:0;pthread_mutex_unlock(&t->mutex);if(early)return early;
    uint64_t total; if(!fds||!files_valid(files,count,&total))return TF_INVALID;
    int copies[RDPSYNC_TRANSFER_FILES];for(size_t i=0;i<RDPSYNC_TRANSFER_FILES;i++)copies[i]=-1;
    for(size_t i=0;i<count;i++){struct stat st;int flags=fcntl(fds[i],F_GETFL);if(flags<0||(flags&O_ACCMODE)!=O_RDONLY||fstat(fds[i],&st)||!S_ISREG(st.st_mode)||st.st_size<0||(uint64_t)st.st_size!=files[i].size)goto invalid;copies[i]=fcntl(fds[i],F_DUPFD_CLOEXEC,0);if(copies[i]<0)goto invalid;}
    pthread_mutex_lock(&t->mutex);int64_t result=t->generation!=gen?TF_STALE:t->capability!=1?TF_UNAVAILABLE:total>t->send_remaining?TF_QUOTA:(t->ever_offered&&!t->peer_lock)?TF_RECONNECT_REQUIRED:(t->lock_count||t->active_reads)?TF_BUSY:0;
    if(!result){uint64_t token=token_new();if(!token)result=TF_INVALID;else{close_offer(t);memcpy(t->offered,files,count*sizeof(*files));memcpy(t->fds,copies,count*sizeof(int));t->offer_count=count;t->offer_token=token;t->require_lock=t->ever_offered;t->ever_offered=1;t->served_bytes=0;t->error=0;t->advertise=1;result=(int64_t)token;}}
    pthread_mutex_unlock(&t->mutex);if(result>0)return result;
    for(size_t i=0;i<count;i++)if(copies[i]>=0)close(copies[i]);return result;
invalid:for(size_t i=0;i<count;i++)if(copies[i]>=0)close(copies[i]);return TF_INVALID;
}
int rdpsync_transfer_retained(RdpTransfer* t,uint64_t token){if(!t||!token)return 0;pthread_mutex_lock(&t->mutex);int yes=t->offer_token==token||(t->active_reads&&t->active_token==token);pthread_mutex_unlock(&t->mutex);return yes;}
int rdpsync_transfer_cancel(RdpTransfer* t,uint64_t token){if(!t||!token)return TF_STALE;pthread_mutex_lock(&t->mutex);int result=TF_STALE;if(t->offer_token==token){close_offer(t);t->error=TF_CANCELLED;result=0;}if(t->receive_token==token){clear_receive(t,TF_CANCELLED);result=0;}pthread_mutex_unlock(&t->mutex);return result;}
int64_t rdpsync_transfer_request_list(RdpTransfer* t,uint64_t gen){if(!t)return TF_DISCONNECTED;pthread_mutex_lock(&t->mutex);int64_t result=t->generation!=gen?TF_STALE:t->capability!=1||!t->remote_format?TF_UNAVAILABLE:t->list_inflight||t->receive_token||t->retired_request_id?TF_BUSY:0;if(!result){uint64_t token=token_new();if(!token)result=TF_INVALID;else{t->receive_token=token;t->phase=1;t->request_list=1;t->list_valid=1;t->error=0;t->deadline_ms=now_ms()+10000;result=(int64_t)token;}}pthread_mutex_unlock(&t->mutex);return result;}
int rdpsync_transfer_manifest(RdpTransfer* t,uint64_t token,RdpTransferFile* files,size_t* count){if(!t||!files||!count)return TF_INVALID;pthread_mutex_lock(&t->mutex);int rc=t->receive_token!=token?TF_STALE:t->phase!=2?TF_BUSY:0;if(!rc){*count=t->receive_count;memcpy(files,t->received,*count*sizeof(*files));}pthread_mutex_unlock(&t->mutex);return rc;}
int rdpsync_transfer_request_chunk(RdpTransfer* t,uint64_t token,size_t index,uint64_t offset,uint32_t length){if(!t)return TF_DISCONNECTED;pthread_mutex_lock(&t->mutex);int rc=t->receive_token!=token?TF_STALE:t->phase!=2?TF_BUSY:0;if(!rc&&(index>=t->receive_count||!length||length>RDPSYNC_TRANSFER_CHUNK||offset>t->received[index].size||length>t->received[index].size-offset))rc=TF_INVALID;if(!rc){uint32_t id=wire_id_new();if(!id)rc=TF_INVALID;else{t->request_id=id;t->request_index=index;t->request_offset=offset;t->request_length=length;t->request_chunk=1;t->phase=3;t->deadline_ms=now_ms()+10000;}}pthread_mutex_unlock(&t->mutex);return rc;}
int rdpsync_transfer_take_chunk(RdpTransfer* t,uint64_t token,uint8_t* out,size_t cap){if(!t)return TF_DISCONNECTED;pthread_mutex_lock(&t->mutex);int rc=t->receive_token!=token?TF_STALE:t->phase==5?t->error:t->phase!=4?0:(int)t->chunk_length;if(rc>0){if(!out||cap<t->chunk_length)rc=TF_INVALID;else{memcpy(out,t->chunk,t->chunk_length);t->chunk_length=0;t->phase=2;}}pthread_mutex_unlock(&t->mutex);return rc;}
int rdpsync_transfer_finish(RdpTransfer* t,uint64_t token){if(!t)return TF_DISCONNECTED;pthread_mutex_lock(&t->mutex);int rc=t->receive_token!=token?TF_STALE:0;if(!rc)clear_receive(t,0);pthread_mutex_unlock(&t->mutex);return rc;}
