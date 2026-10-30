/* Exact production RDPDR codec/broker/UTF codec and pinned WinPR stream.
 * Only transport/channel dispatch and IRP consumption are deterministic seams.
 * Real channel/TLS/xrdp interoperability is a separate Android fixture. */
#define _GNU_SOURCE
#include <assert.h>
#include <ctype.h>
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include "../../main/cpp/rdpsync_drive.c"
wLog* WLog_Get(const char* name){(void)name;return NULL;}
BOOL WLog_IsLevelActive(wLog* log,DWORD level){(void)log;(void)level;return FALSE;}
BOOL WLog_PrintTextMessage(wLog* log,DWORD level,size_t line,const char* file,const char* function,const char* fmt,...){(void)log;(void)level;(void)line;(void)file;(void)function;(void)fmt;return TRUE;}
BOOL WLog_PrintTextMessageVA(wLog* log,DWORD level,size_t line,const char* file,const char* function,const char* fmt,va_list args){(void)log;(void)level;(void)line;(void)file;(void)function;(void)fmt;(void)args;return TRUE;}
void winpr_log_backtrace(const char* tag,DWORD level,DWORD size){(void)tag;(void)level;(void)size;}
void winpr_log_backtrace_ex(wLog* log,DWORD level,DWORD size){(void)log;(void)level;(void)size;}
static INT64 transport_pending;
static size_t sent_size, consumed, discarded, drained;
static uint32_t sent_status;
static BYTE sent[RDP_WS_MAX_IO + 4096];
static int fail_complete;
static pthread_mutex_t race_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t race_changed = PTHREAD_COND_INITIALIZER;
static bool race_active, race_entered, race_attempting;
static pthread_t protocol_owner;
INT64 rdpsync_freerdp_pending_bytes(rdpContext* context){(void)context;assert(pthread_equal(pthread_self(),protocol_owner));return transport_pending;}
BOOL freerdp_channels_check_fds(rdpChannels* channels,freerdp* instance){(void)channels;(void)instance;assert(pthread_equal(pthread_self(),protocol_owner));transport_pending += (INT64)sent_size;drained++;return TRUE;}
static void put16(BYTE* p,uint16_t n){p[0]=(BYTE)n;p[1]=(BYTE)(n>>8);}
static void put32(BYTE* p,uint32_t n){put16(p,(uint16_t)n);put16(p+2,(uint16_t)(n>>16));}
static void put64(BYTE* p,uint64_t n){put32(p,(uint32_t)n);put32(p+4,(uint32_t)(n>>32));}
static void consume(IRP* irp){Stream_Free(irp->input,TRUE);Stream_Free(irp->output,TRUE);free(irp);}
static UINT complete(IRP* irp){assert(pthread_equal(pthread_self(),protocol_owner));if(race_active){pthread_mutex_lock(&race_lock);race_entered=true;pthread_cond_broadcast(&race_changed);while(!race_attempting)pthread_cond_wait(&race_changed,&race_lock);pthread_mutex_unlock(&race_lock);}sent_size=Stream_GetPosition(irp->output);assert(sent_size<=sizeof(sent));memcpy(sent,Stream_Buffer(irp->output),sent_size);sent_status=irp->IoStatus;consumed++;consume(irp);return fail_complete?ERROR_INTERNAL_ERROR:CHANNEL_RC_OK;}
static UINT discard(IRP* irp){discarded++;consume(irp);return CHANNEL_RC_OK;}
static bool name_key(void* ctx,const char* name,char* key,size_t capacity){(void)ctx;size_t n=strlen(name);if(n>=capacity)return false;for(size_t i=0;i<=n;i++)key[i]=(char)toupper((unsigned char)name[i]);return true;}
typedef struct {char home[128];RdpWorkspace* ws;RdpDrive* drive;RdpSyncContext context;freerdp instance;uint32_t sequence;} Fixture;
static BOOL control(Fixture* f,uint16_t type,const BYTE* body,size_t n){BYTE packet[64]={0};assert(n+4<=sizeof(packet));put16(packet,RDPDR_CTYP_CORE);put16(packet+2,type);if(n)memcpy(packet+4,body,n);return rdpsync_drive_ingress(f->drive,packet,n+4,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,n+4);}
static void announce(Fixture* f){BYTE body[8]={1,0,12,0};assert(control(f,PAKID_CORE_SERVER_ANNOUNCE,body,8));}
static void accept(Fixture* f){BYTE caps[12]={1,0,0,0};put16(caps+4,CAP_DRIVE_TYPE);put16(caps+6,8);put32(caps+8,2);assert(control(f,PAKID_CORE_SERVER_CAPABILITY,caps,12));BYTE reply[8]={0};put32(reply,7);assert(control(f,PAKID_CORE_DEVICE_REPLY,reply,8));}
static void setup(Fixture* f,int mode){memset(f,0,sizeof(*f));strcpy(f->home,"/tmp/rdpsync-drive-test-XXXXXX");assert(mkdtemp(f->home));int fd=open(f->home,O_RDONLY|O_DIRECTORY|O_CLOEXEC);assert(fd>=0);assert(!rdp_ws_create(fd,name_key,NULL,&f->ws));close(fd);f->drive=rdpsync_drive_new(f->ws,41,mode,name_key,NULL);assert(f->drive);DriveDevice* dev=calloc(1,sizeof(*dev));assert(dev);dev->owner=f->drive;dev->device.id=7;dev->device.data=Stream_New(NULL,16);assert(dev->device.data);f->drive->device=dev;f->drive->device_id=7;f->context.drive=f->drive;f->instance.context=&f->context.common.context;f->drive->context=&f->context.common.context;f->sequence=10;transport_pending=0;sent_size=0;sent_status=0;fail_complete=0;announce(f);accept(f);assert(!drive_init(&dev->device));}
static void cleanup(Fixture* f){if(f->drive->device)assert(!drive_free(&f->drive->device->device));rdpsync_drive_free(f->drive);rdp_ws_destroy(f->ws);char cmd[256];snprintf(cmd,sizeof(cmd),"rm -rf -- '%s'",f->home);assert(system(cmd)==0);}
static size_t path_bytes(const char* path,BYTE* output){size_t units=0;uint16_t* wide=rdpsync_utf8_to_utf16(path,strlen(path),&units);assert(wide);for(size_t i=0;i<units;i++)put16(output+i*2,wide[i]);free(wide);return units*2;}
static IRP* prepare(Fixture* f,uint32_t major,uint32_t minor,uint32_t handle,const BYTE* body,size_t n,uint32_t id){BYTE* wire=calloc(1,24+n);assert(wire);put16(wire,RDPDR_CTYP_CORE);put16(wire+2,PAKID_CORE_DEVICE_IOREQUEST);put32(wire+4,7);put32(wire+8,handle);put32(wire+12,id);put32(wire+16,major);put32(wire+20,minor);if(n)memcpy(wire+24,body,n);assert(rdpsync_drive_ingress(f->drive,wire,24+n,CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,24+n));free(wire);IRP* irp=calloc(1,sizeof(*irp));assert(irp);irp->input=Stream_New(NULL,n?n:1);assert(irp->input);if(n)Stream_Write(irp->input,body,n);Stream_SealLength(irp->input);Stream_SetPosition(irp->input,0);irp->output=Stream_New(NULL,128);assert(irp->output);Stream_Zero(irp->output,RDPDR_DEVICE_IO_RESPONSE_LENGTH);irp->MajorFunction=major;irp->MinorFunction=minor;irp->FileId=handle;irp->CompletionId=id;irp->Complete=complete;irp->Discard=discard;return irp;}
static void enqueue(Fixture* f,uint32_t major,uint32_t minor,uint32_t handle,const BYTE* body,size_t n){IRP* irp=prepare(f,major,minor,handle,body,n,++f->sequence);assert(!drive_irp(&f->drive->device->device,irp));}
static uint32_t request(Fixture* f,uint32_t major,uint32_t minor,uint32_t handle,const BYTE* body,size_t n){size_t before=consumed;enqueue(f,major,minor,handle,body,n);transport_pending=0;assert(rdpsync_drive_pump(f->drive,&f->instance));assert(consumed==before+1);return sent_status;}
static uint32_t create(Fixture* f,const char* path,uint32_t access,uint32_t disposition,uint32_t options){BYTE body[32+8192]={0};put32(body,access);put32(body+16,7);put32(body+20,disposition);put32(body+24,options);size_t n=path_bytes(path,body+32);put32(body+28,(uint32_t)n);uint32_t status=request(f,IRP_MJ_CREATE,0,0,body,n+32);if(status)fprintf(stderr,"create %s failed %08x\n",path,status);assert(status==STATUS_SUCCESS);assert(sent_size==21);return get32(sent+16);}
static void write_file(Fixture* f,uint32_t h,uint64_t off,const void* data,size_t n){BYTE* body=calloc(1,32+n);assert(body);put32(body,(uint32_t)n);put64(body+4,off);memcpy(body+32,data,n);assert(!request(f,IRP_MJ_WRITE,0,h,body,32+n));assert(get32(sent+16)==n&&sent_size==21);free(body);}
static void read_file(Fixture* f,uint32_t h,uint64_t off,size_t n,const void* expected,size_t actual){BYTE body[32]={0};put32(body,(uint32_t)n);put64(body+4,off);assert(!request(f,IRP_MJ_READ,0,h,body,32));assert(get32(sent+16)==actual&&sent_size==20+actual);assert(!memcmp(sent+20,expected,actual));}
static uint32_t set(Fixture* f,uint32_t h,uint32_t type,const BYTE* data,size_t n){BYTE body[32+8192]={0};assert(n<=8192);put32(body,type);put32(body+4,(uint32_t)n);memcpy(body+32,data,n);return request(f,IRP_MJ_SET_INFORMATION,0,h,body,32+n);}
static uint32_t directory(Fixture* f,uint32_t h,const char* pattern,bool initial){BYTE body[32+8192]={0};put32(body,FileNamesInformation);body[4]=initial;size_t n=path_bytes(pattern,body+32);put32(body+5,(uint32_t)n);return request(f,IRP_MJ_DIRECTORY_CONTROL,IRP_MN_QUERY_DIRECTORY,h,body,32+n);}
static void utf_tests(void){size_t n;const uint16_t wide[]={0x732b,0xd83d,0xde00};char* text=rdpsync_utf16_to_utf8(wide,3,&n);assert(text&&n==7&&!memcmp(text,"猫😀",7));size_t units;uint16_t* copy=rdpsync_utf8_to_utf16(text,n,&units);assert(copy&&units==3&&!memcmp(wide,copy,sizeof(wide)));free(text);free(copy);const char* bad[]={"\xc0\x80","\xed\xa0\x80","\xf4\x90\x80\x80","\xe7\x8c","\x80","\xf0\x80\x80\x80"};for(size_t i=0;i<sizeof(bad)/sizeof(*bad);i++)assert(!rdpsync_utf8_to_utf16(bad[i],strlen(bad[i]),&n));const uint16_t invalid[][2]={{0xd800,0},{0xdc00,0},{'a',0},{0xd800,'a'}};for(size_t i=0;i<4;i++)assert(!rdpsync_utf16_to_utf8(invalid[i],2,&n));char path[RDP_WS_MAX_PATH+1];BYTE bytes[]={0,0xd8};assert(wire_path(bytes,2,0,path)==STATUS_OBJECT_NAME_INVALID);const char* attacks[]={"../escape","a//b","C:secret","a/..","trail.","a?","a/*/b"};for(size_t i=0;i<sizeof(attacks)/sizeof(*attacks);i++){BYTE encoded[128];size_t len=path_bytes(attacks[i],encoded);assert(wire_path(encoded,len,0,path)!=0);}BYTE term[]={'a',0,0,0};assert(!wire_path(term,4,0,path)&&!strcmp(path,"a"));assert(wire_path(term,3,0,path)!=0);}
static void filesystem_tests(void){Fixture f;setup(&f,2);uint32_t h=create(&f,"a.txt",GENERIC_READ|GENERIC_WRITE|DELETE|FILE_WRITE_ATTRIBUTES,FILE_CREATE,FILE_NON_DIRECTORY_FILE);const BYTE data[]={0,255,2,3,4,5};write_file(&f,h,0,data,sizeof(data));read_file(&f,h,2,9,data+2,4);assert(!request(&f,0x07,0,h,NULL,0));BYTE query[4];put32(query,FileStandardInformation);assert(!request(&f,IRP_MJ_QUERY_INFORMATION,0,h,query,4));assert(get32(sent+16)==22&&get64(sent+28)==6);BYTE size[8];put64(size,3);assert(!set(&f,h,FileEndOfFileInformation,size,8));read_file(&f,h,0,9,data,3);BYTE rename[128]={1,0};size_t n=path_bytes("renamed.txt",rename+6);put32(rename+2,(uint32_t)n+2);assert(!set(&f,h,FileRenameInformation,rename,n+8));assert(!request(&f,IRP_MJ_CLOSE,0,h,NULL,0));h=create(&f,"RENAMED.TXT",GENERIC_READ|DELETE,FILE_OPEN,FILE_NON_DIRECTORY_FILE);read_file(&f,h,0,3,data,3);BYTE deleting=1;assert(!set(&f,h,FileDispositionInformation,&deleting,1));assert(!request(&f,IRP_MJ_CLOSE,0,h,NULL,0));uint32_t root=create(&f,"",FILE_LIST_DIRECTORY|FILE_READ_ATTRIBUTES,FILE_OPEN,FILE_DIRECTORY_FILE);assert(directory(&f,root,"*",true)==STATUS_NO_MORE_FILES);h=create(&f,"a.txt",GENERIC_READ|GENERIC_WRITE,FILE_CREATE,FILE_NON_DIRECTORY_FILE);assert(!request(&f,IRP_MJ_CLOSE,0,h,NULL,0));assert(!directory(&f,root,"*",true));assert(get32(sent+28)==10);assert(directory(&f,root,"",false)==STATUS_NO_MORE_FILES);assert(request(&f,IRP_MJ_DEVICE_CONTROL,0,root,NULL,0)==STATUS_NOT_SUPPORTED);put32(query,FileFsFullSizeInformation);assert(!request(&f,IRP_MJ_QUERY_VOLUME_INFORMATION,0,0,query,4));assert(get32(sent+16)==32&&get64(sent+20)==RDP_WS_MAX_BYTES/4096);cleanup(&f);}
static void timestamp_tests(void) {
    assert(filetime(-1) == 0 && filetime(INT64_MAX) == 0 && filetime(0) == DRIVE_FILETIME_EPOCH);
    Fixture f; setup(&f, 2);
    uint32_t h = create(&f, "times.bin", GENERIC_READ | GENERIC_WRITE | FILE_WRITE_ATTRIBUTES, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
    BYTE data[36] = {0};
    const int64_t access = INT64_C(1234567890000), modified = INT64_C(1234567800000);
    put64(data + 8, filetime(access)); put64(data + 16, filetime(modified));
    assert(!set(&f, h, FileBasicInformation, data, sizeof(data)));
    RdpWorkspaceEntry entry; assert(!rdp_ws_stat(f.ws, 41, h, &entry));
    assert(entry.access_millis == access && entry.modified_millis == modified && entry.change_millis > 0);
    BYTE query[4]; put32(query, FileBasicInformation); assert(!request(&f, IRP_MJ_QUERY_INFORMATION, 0, h, query, sizeof(query)));
    assert(get64(sent + 20) == 0 && get64(sent + 28) == filetime(access));
    assert(get64(sent + 36) == filetime(modified) && get64(sent + 44) == filetime(entry.change_millis));
    put64(data + 8, DRIVE_FILETIME_EPOCH - 1);
    assert(set(&f, h, FileBasicInformation, data, sizeof(data)) == (uint32_t)STATUS_INVALID_PARAMETER);
    put64(data + 8, UINT64_MAX); put64(data + 16, UINT64_MAX); put64(data, DRIVE_FILETIME_EPOCH);
    assert(set(&f, h, FileBasicInformation, data, sizeof(data)) == (uint32_t)STATUS_NOT_SUPPORTED);
    put64(data, 0); put64(data + 24, DRIVE_FILETIME_EPOCH);
    assert(set(&f, h, FileBasicInformation, data, sizeof(data)) == (uint32_t)STATUS_NOT_SUPPORTED);
    put64(data + 24, UINT64_MAX); assert(!set(&f, h, FileBasicInformation, data, sizeof(data)));
    assert(!rdp_ws_stat(f.ws, 41, h, &entry) && entry.access_millis == access && entry.modified_millis == modified);
    cleanup(&f);
}
static void flow_tests(void){Fixture f;setup(&f,2);uint32_t h=create(&f,"flow.bin",GENERIC_READ|GENERIC_WRITE,FILE_CREATE,FILE_NON_DIRECTORY_FILE);BYTE data[1024];memset(data,0xa5,sizeof(data));write_file(&f,h,0,data,sizeof(data));BYTE body[32]={0};put32(body,sizeof(data));size_t before=consumed;enqueue(&f,IRP_MJ_READ,0,h,body,32);transport_pending=RDPSYNC_DRIVE_TRANSPORT_HIGH+1;assert(rdpsync_drive_pump(f.drive,&f.instance));assert(consumed==before&&f.drive->pending_count==1);transport_pending=RDPSYNC_DRIVE_TRANSPORT_HIGH;assert(rdpsync_drive_pump(f.drive,&f.instance));assert(consumed==before+1&&drained&&transport_pending>RDPSYNC_DRIVE_TRANSPORT_HIGH);assert(!memcmp(sent+20,data,sizeof(data)));enqueue(&f,IRP_MJ_READ,0,h,body,32);rdpsync_drive_revoke(f.drive);transport_pending=0;assert(rdpsync_drive_pump(f.drive,&f.instance));assert(sent_status==STATUS_CANCELLED&&sent_size==20&&get32(sent+16)==0);cleanup(&f);
setup(&f,2);h=create(&f,"fail.bin",GENERIC_READ,FILE_CREATE,FILE_NON_DIRECTORY_FILE);enqueue(&f,IRP_MJ_READ,0,h,body,32);transport_pending=0;before=consumed;fail_complete=1;assert(!rdpsync_drive_pump(f.drive,&f.instance));assert(consumed==before+1&&!f.drive->pending_count);cleanup(&f);
setup(&f,2);h=create(&f,"stack.bin",GENERIC_READ,FILE_CREATE,FILE_NON_DIRECTORY_FILE);enqueue(&f,IRP_MJ_READ,0,h,body,32);transport_pending=-1;before=consumed;assert(rdpsync_drive_pump(f.drive,&f.instance)&&consumed==before);transport_pending=-2;assert(rdpsync_drive_pump(f.drive,&f.instance));assert(sent_status==STATUS_NOT_SUPPORTED&&f.drive->state==-4&&f.drive->revoked);cleanup(&f);}
static void epoch_tests(void){Fixture f;setup(&f,2);uint32_t h=create(&f,"epoch.bin",GENERIC_READ,FILE_CREATE,FILE_NON_DIRECTORY_FILE);BYTE body[32]={0};put32(body,8);enqueue(&f,IRP_MJ_READ,0,h,body,32);size_t before=consumed,old_discarded=discarded;announce(&f);accept(&f);transport_pending=0;assert(rdpsync_drive_pump(f.drive,&f.instance)&&consumed==before);BYTE create_body[32]={0};put32(create_body,FILE_LIST_DIRECTORY);put32(create_body+16,7);put32(create_body+20,FILE_OPEN);put32(create_body+24,FILE_DIRECTORY_FILE);IRP* future=prepare(&f,IRP_MJ_CREATE,0,0,create_body,32,++f.sequence);assert(f.drive->pending_count==2);assert(!drive_init(&f.drive->device->device));assert(discarded==old_discarded+1&&f.drive->pending_count==1);assert(rdpsync_drive_pump(f.drive,&f.instance)&&consumed==before);assert(!drive_irp(&f.drive->device->device,future));assert(rdpsync_drive_pump(f.drive,&f.instance)&&consumed==before+1&&sent_status==0);cleanup(&f);}
static void* free_during_complete(void* pointer) {
    Fixture* f = pointer;
    pthread_mutex_lock(&race_lock);
    while (!race_entered) pthread_cond_wait(&race_changed, &race_lock);
    race_attempting = true; pthread_cond_broadcast(&race_changed);
    pthread_mutex_unlock(&race_lock);
    assert(!drive_free(&f->drive->device->device));
    return NULL;
}
static void lifecycle_race_test(void) {
    Fixture f; setup(&f, 2);
    uint32_t h = create(&f, "race.bin", GENERIC_READ, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
    BYTE body[32] = {0}; put32(body, 1); enqueue(&f, IRP_MJ_READ, 0, h, body, 32);
    race_active = true; race_entered = race_attempting = false;
    pthread_t worker; assert(!pthread_create(&worker, NULL, free_during_complete, &f));
    transport_pending = 0; size_t before = consumed;
    assert(rdpsync_drive_pump(f.drive, &f.instance));
    assert(!pthread_join(worker, NULL)); race_active = false;
    assert(consumed == before + 1 && !f.drive->pending_count && !f.drive->device);
    cleanup(&f);
}
static void directory_identity_tests(void) {
    Fixture f; setup(&f, 2);
    uint32_t directory_handle = create(&f, "Before", FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES, FILE_CREATE, FILE_DIRECTORY_FILE);
    uint32_t file = create(&f, "Before\\a.txt", GENERIC_READ | GENERIC_WRITE, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
    assert(!request(&f, IRP_MJ_CLOSE, 0, file, NULL, 0));
    assert(!directory(&f, directory_handle, "\\before\\*.TXT", true));
    assert(!directory(&f, directory_handle, "/before/*.TXT", true));
    assert(directory(&f, directory_handle, "/*", true) == (uint32_t)STATUS_OBJECT_PATH_NOT_FOUND);
    assert(directory(&f, directory_handle, "\\*", true) == (uint32_t)STATUS_OBJECT_PATH_NOT_FOUND);
    uint32_t local = 0, information = 0;
    assert(!rdp_ws_open(f.ws, 0, "BEFORE", DELETE, 7, FILE_OPEN, FILE_DIRECTORY_FILE, false, 0, &local, &information));
    assert(!rdp_ws_rename(f.ws, 0, local, "After", false));
    assert(!rdp_ws_close(f.ws, 0, local));
    assert(!directory(&f, directory_handle, "\\after\\*.TXT", true));
    assert(!directory(&f, directory_handle, "/after/*.TXT", true));
    assert(directory(&f, directory_handle, "/before/*", true) == (uint32_t)STATUS_OBJECT_PATH_NOT_FOUND);
    assert(directory(&f, directory_handle, "\\before\\*", true) == (uint32_t)STATUS_OBJECT_PATH_NOT_FOUND);
    file = create(&f, "After\\猫", GENERIC_READ, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
    assert(!request(&f, IRP_MJ_CLOSE, 0, file, NULL, 0));
    assert(directory(&f, directory_handle, "狗", true) == (uint32_t)STATUS_NO_MORE_FILES);
    assert(!directory(&f, directory_handle, "猫", true));
    assert(!directory(&f, directory_handle, "?", true));
    BYTE malformed[34] = {0}; put32(malformed, FileNamesInformation); malformed[4] = 1; put32(malformed + 5, 2); put16(malformed + 32, 0xd800);
    assert(request(&f, IRP_MJ_DIRECTORY_CONTROL, IRP_MN_QUERY_DIRECTORY, directory_handle, malformed, sizeof(malformed)) == (uint32_t)STATUS_OBJECT_NAME_INVALID);
    cleanup(&f);
}
static void rejection_test(void) {
    Fixture f; setup(&f, 2);
    uint32_t h = create(&f, "rejected.bin", GENERIC_READ, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
    BYTE body[32] = {0}; put32(body, 1); enqueue(&f, IRP_MJ_READ, 0, h, body, 32);
    size_t before = consumed;
    BYTE reply[8] = {0}; put32(reply, 7); put32(reply + 4, STATUS_ACCESS_DENIED);
    assert(control(&f, PAKID_CORE_DEVICE_REPLY, reply, sizeof(reply)));
    assert(f.drive->state == -2 && f.drive->revoked && !f.drive->device_id);
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (f.drive->slots[i].irp) { assert(f.drive->slots[i].irp->IoStatus == (uint32_t)STATUS_DEVICE_NOT_READY); assert(Stream_GetPosition(f.drive->slots[i].irp->output) == 20); }
    transport_pending = 0; assert(rdpsync_drive_pump(f.drive, &f.instance)); assert(consumed == before);
    assert(!drive_free(&f.drive->device->device)); assert(!f.drive->pending_count);
    BYTE caps[12] = {1,0,0,0}; put16(caps + 4, CAP_DRIVE_TYPE); put16(caps + 6, 8); put32(caps + 8, 2);
    assert(control(&f, PAKID_CORE_SERVER_CAPABILITY, caps, sizeof(caps)) && f.drive->state == -2);
    put32(reply + 4, 0); assert(!control(&f, PAKID_CORE_DEVICE_REPLY, reply, sizeof(reply)));
    assert(f.drive->state < 0 && !f.drive->device_id);
    BYTE packet[56] = {0}; put16(packet, RDPDR_CTYP_CORE); put16(packet + 2, PAKID_CORE_DEVICE_IOREQUEST);
    put32(packet + 4, 7); put32(packet + 12, 999); put32(packet + 16, IRP_MJ_READ); put32(packet + 24, 1);
    assert(!rdpsync_drive_ingress(f.drive, packet, sizeof(packet), CHANNEL_FLAG_FIRST | CHANNEL_FLAG_LAST, sizeof(packet)));
    cleanup(&f);
}
static void admission_tests(void){Fixture f;setup(&f,2);BYTE packet[56]={0};put16(packet,RDPDR_CTYP_CORE);put16(packet+2,PAKID_CORE_DEVICE_IOREQUEST);put32(packet+4,7);put32(packet+12,20);put32(packet+16,IRP_MJ_READ);put32(packet+24,RDP_WS_MAX_IO);for(uint32_t i=0;i<7;i++){put32(packet+12,20+i);assert(rdpsync_drive_ingress(f.drive,packet,25,CHANNEL_FLAG_FIRST,sizeof(packet)));assert(rdpsync_drive_ingress(f.drive,packet+25,31,CHANNEL_FLAG_LAST,sizeof(packet)));}assert(f.drive->pending_response_bytes<=RDPSYNC_DRIVE_RESPONSE_BYTES);char metrics[512];rdpsync_drive_diagnostics(f.drive,metrics,sizeof(metrics));assert(strstr(metrics,"shared-space-pending-response-bytes=7354368\n")&&strstr(metrics,"shared-space-peak-response-bytes=7354368\n"));put32(packet+12,99);assert(!rdpsync_drive_ingress(f.drive,packet,sizeof(packet),CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,sizeof(packet)));cleanup(&f);
setup(&f,2);put32(packet+24,1);put32(packet+12,20);assert(rdpsync_drive_ingress(f.drive,packet,sizeof(packet),CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,sizeof(packet)));assert(!rdpsync_drive_ingress(f.drive,packet,sizeof(packet),CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST,sizeof(packet)));cleanup(&f);
setup(&f,2);BYTE caps[12]={1,0,0,0};put16(caps+4,CAP_DRIVE_TYPE);put16(caps+6,8);put32(caps+8,2);size_t controls=0;while(control(&f,PAKID_CORE_SERVER_CAPABILITY,caps,sizeof(caps)))controls++;assert(controls<DRIVE_CONTROL_BYTES/16+1&&f.drive->state==-5);cleanup(&f);
setup(&f,2);BYTE no_caps[4]={0};assert(control(&f,PAKID_CORE_SERVER_CAPABILITY,no_caps,4));assert(f.drive->state==-1);cleanup(&f);}
static void readonly_tests(void){Fixture f;setup(&f,1);uint32_t root=create(&f,"",FILE_LIST_DIRECTORY|FILE_READ_ATTRIBUTES,FILE_OPEN,FILE_DIRECTORY_FILE);BYTE body[32]={0};assert(request(&f,IRP_MJ_WRITE,0,root,body,32)==STATUS_MEDIA_WRITE_PROTECTED);put32(body,GENERIC_WRITE);put32(body+20,FILE_CREATE);put32(body+24,FILE_NON_DIRECTORY_FILE);BYTE create_body[34];memcpy(create_body,body,32);put32(create_body+28,2);put16(create_body+32,'x');assert(request(&f,IRP_MJ_CREATE,0,0,create_body,34)==STATUS_ACCESS_DENIED);cleanup(&f);}

/* Pinned xrdp 0.10.6.1 devredir.c lookup sequence: one CompletionId reused,
 * CREATE FILE_READ_ATTRIBUTES|SYNCHRONIZE, zero options, terminal UTF-16 NUL,
 * then padded Basic36/Standard22 requests, and CLOSE. */
static void diagnostic_privacy_test(void) {
    Fixture f; setup(&f, 1);
    BYTE opaque[32]; memset(opaque, 0x5a, sizeof(opaque));
    (void)request(&f, 0x7fffffff, 0, 0, opaque, sizeof(opaque));
    size_t latest = (size_t)((f.drive->irp_observed - 1) % 16);
    assert(f.drive->irp_trace[latest].arg0 == 0 && f.drive->irp_trace[latest].arg1 == 0);
    (void)request(&f, IRP_MJ_CLOSE, 0, 0, opaque, sizeof(opaque));
    latest = (size_t)((f.drive->irp_observed - 1) % 16);
    assert(f.drive->irp_trace[latest].arg0 == 0 && f.drive->irp_trace[latest].arg1 == 0);
    cleanup(&f);
}

static void xrdp_lookup_replay(void) {
    Fixture f; setup(&f, 1);
    uint32_t local = 0, information = 0;
    assert(!rdp_ws_open(f.ws, 0, "android-empty.txt", GENERIC_READ | GENERIC_WRITE,
        7, FILE_CREATE, FILE_NON_DIRECTORY_FILE, false, 0, &local, &information));
    assert(!rdp_ws_close(f.ws, 0, local));
    BYTE body[256] = {0};
    put32(body, FILE_READ_ATTRIBUTES | SYNCHRONIZE); put32(body + 16, 7);
    put32(body + 20, FILE_OPEN);
    size_t path_length = path_bytes("\\android-empty.txt", body + 32) + 2;
    put32(body + 28, (uint32_t)path_length);
    assert(!request(&f, IRP_MJ_CREATE, 0, 0, body, 32 + path_length));
    uint32_t file = get32(sent + 16), completion = f.sequence;
    memset(body, 0, sizeof(body)); put32(body, FileBasicInformation); put32(body + 4, 36);
    f.sequence = completion - 1;
    assert(!request(&f, IRP_MJ_QUERY_INFORMATION, 0, file, body, 32 + 36));
    assert(get32(sent + 16) == 36 && sent_size == 56);
    assert(get32(sent + 52) == (FILE_ATTRIBUTE_ARCHIVE | FILE_ATTRIBUTE_READONLY));
    put32(body, FileStandardInformation); put32(body + 4, 22);
    f.sequence = completion - 1;
    assert(!request(&f, IRP_MJ_QUERY_INFORMATION, 0, file, body, 32 + 22));
    assert(get32(sent + 16) == 22 && sent_size == 42 && get64(sent + 28) == 0);
    f.sequence = completion - 1;
    assert(!request(&f, IRP_MJ_CLOSE, 0, file, body, 32));
    memset(body, 0, sizeof(body)); put32(body, FILE_READ_DATA | SYNCHRONIZE);
    put32(body + 16, 7); put32(body + 20, FILE_OPEN); put32(body + 24, FILE_SYNCHRONOUS_IO_NONALERT);
    /* Pinned xrdp converts irp->pathname but sends original POSIX path here. */
    path_length = path_bytes("/android-empty.txt", body + 32) + 2;
    put32(body + 28, (uint32_t)path_length);
    assert(!request(&f, IRP_MJ_CREATE, 0, 0, body, 32 + path_length));
    file = get32(sent + 16); read_file(&f, file, 0, 65536, "", 0);
    assert(!request(&f, IRP_MJ_CLOSE, 0, file, NULL, 0));
    cleanup(&f);
}

static void wire_separator_tests(void) {
    char output[RDP_WS_MAX_PATH + 1]; BYTE wire[256];
    const char* accepted[][2] = {
        {"/android-empty.txt", "android-empty.txt"}, {"\\android-empty.txt", "android-empty.txt"},
        {"/nested/file.txt", "nested/file.txt"}, {"\\nested\\file.txt", "nested/file.txt"},
        {"nested/file.txt", "nested/file.txt"}, {"nested\\file.txt", "nested/file.txt"},
        {"/", ""}, {"\\", ""}
    };
    for (size_t i = 0; i < sizeof(accepted)/sizeof(*accepted); ++i) {
        size_t n = path_bytes(accepted[i][0], wire);
        assert(!wire_path(wire, n, 0, output) && !strcmp(output, accepted[i][1]));
    }
    const char* rejected[] = {"//etc/passwd", "\\\\server\\share", "/\\secret", "\\/secret",
        "a//b", "a\\\\b", "/a\\b", "\\a/b", "a/b\\c", "a\\b/c",
        "/../secret", "/a/../secret", "/a/./b", "\\a\\..\\secret", "/a/", "/C:secret",
        "/file:stream", "/a?", "/a/*/b"};
    for (size_t i = 0; i < sizeof(rejected)/sizeof(*rejected); ++i) {
        size_t n = path_bytes(rejected[i], wire);
        assert(wire_path(wire, n, 0, output) == STATUS_OBJECT_NAME_INVALID);
    }
}


/* FreeRDP's CREATE error mapping returns NO_SUCH_FILE for a missing name.
 * xrdp's metadata lookup recognizes this status, not OBJECT_NAME_NOT_FOUND. */
static void missing_name_wire_status_tests(void) {
    Fixture f; setup(&f, 1);
    BYTE body[256] = {0};
    put32(body, FILE_READ_ATTRIBUTES | SYNCHRONIZE); put32(body + 16, 7);
    put32(body + 20, FILE_OPEN);
    size_t n = path_bytes("\\missing-leaf.txt", body + 32) + 2;
    put32(body + 28, (uint32_t)n);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_NO_SUCH_FILE);
    assert(sent_size == 21 && get32(sent + 16) == 0 && sent[20] == 0);
    put32(body, FILE_READ_DATA | SYNCHRONIZE); put32(body + 24, FILE_SYNCHRONOUS_IO_NONALERT);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_NO_SUCH_FILE);
    /* Do not translate denial into absence, or alter local broker semantics. */
    put32(body, FILE_WRITE_DATA | SYNCHRONIZE); put32(body + 20, FILE_CREATE);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_ACCESS_DENIED);
    uint32_t h = 0, info = 0;
    assert(rdp_ws_open(f.ws, 0, "missing-leaf.txt", FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        7, FILE_OPEN, 0, false, 0, &h, &info) == RDP_WS_OBJECT_NAME_NOT_FOUND);
    put32(body, FILE_READ_ATTRIBUTES | SYNCHRONIZE); put32(body + 20, FILE_OPEN); put32(body + 24, 0);
    memset(body + 32, 0, sizeof(body)-32);
    n = path_bytes("\\..\\escape", body+32) + 2; put32(body+28, (uint32_t)n);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_OBJECT_NAME_INVALID);
    memset(body + 32, 0, sizeof(body)-32);
    n = path_bytes("\\absent-parent\\leaf", body+32) + 2; put32(body+28, (uint32_t)n);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_NO_SUCH_FILE);
    /* An existing regular parent is a distinct structural error, not absence. */
    assert(!rdp_ws_open(f.ws, 0, "regular-parent", GENERIC_READ | GENERIC_WRITE,
        7, FILE_CREATE, FILE_NON_DIRECTORY_FILE, false, 0, &h, &info));
    assert(!rdp_ws_close(f.ws, 0, h));
    memset(body + 32, 0, sizeof(body)-32);
    n = path_bytes("regular-parent/leaf", body+32) + 2; put32(body+28, (uint32_t)n);
    assert(request(&f, IRP_MJ_CREATE, 0, 0, body, 32+n) == (uint32_t)STATUS_NOT_A_DIRECTORY);
    cleanup(&f);
}

int main(void){protocol_owner=pthread_self();missing_name_wire_status_tests();wire_separator_tests();diagnostic_privacy_test();xrdp_lookup_replay();utf_tests();filesystem_tests();timestamp_tests();flow_tests();epoch_tests();directory_identity_tests();lifecycle_race_test();rejection_test();admission_tests();readonly_tests();puts("production RDPDR codec: create/read/write/query/list/rename/delete/flush/RO, strict Unicode/path, actual broker, bounded admission, no-drain/revoke, consumed failure, reannounce epoch PASS (synthetic transport)");return 0;}
