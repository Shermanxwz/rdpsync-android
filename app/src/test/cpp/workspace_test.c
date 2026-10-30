#define _GNU_SOURCE
#include "../../main/cpp/rdpsync_workspace.h"
#include <assert.h>
#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* JNI instrumentation verifies the real NFC/Unicode policy. This bounded host
 * policy is intentionally only an ASCII case-fold seam plus literal UTF-8. */
#ifdef RDPSYNC_WS_TEST_FAULTS
bool rdp_ws_test_fail_created_directory_open;
#endif

static bool key(void* ignored, const char* name, char* out, size_t capacity) {
    (void)ignored;
    if (strlen(name) + 1 > capacity) return false;
    for (size_t i = 0; i <= strlen(name); i++) out[i] = (char)toupper((unsigned char)name[i]);
    if (!strcmp(out, "NUL") || !strcmp(out, "CON") || !strncmp(out, "COM1.", 5)) return false;
    return true;
}
static uint32_t open_file(RdpWorkspace* ws, uint64_t owner, const char* name, uint32_t access,
                          uint32_t share, uint32_t disposition, uint32_t options, bool readonly) {
    uint32_t handle = 0, info = 0;
    uint32_t rc = rdp_ws_open(ws, owner, name, access, share, disposition, options, readonly, 0, &handle, &info);
    if (rc != RDP_WS_OK) fprintf(stderr, "open %s failed %08x\n", name, rc);
    assert(rc == RDP_WS_OK); assert(handle); return handle;
}
static void rejected_open(RdpWorkspace* ws, const char* path, uint32_t status) {
    uint32_t handle = 999, info = 0;
    assert(rdp_ws_open(ws, 1, path, 1, 7, 1, 0, false, 0, &handle, &info) == status);
    assert(handle == 0);
}
static int count_files(int fd) {
    int copy = openat(fd, ".", O_RDONLY | O_DIRECTORY); assert(copy >= 0);
    DIR* stream = fdopendir(copy); assert(stream); int count = 0; struct dirent* entry;
    while ((entry = readdir(stream))) if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) count++;
    closedir(stream); return count;
}
static bool cancel(void* context) { int* calls = context; return ++*calls >= 3; }
typedef struct {
    RdpWorkspace* ws; uint32_t parent; int source; uint64_t size;
    pthread_mutex_t mutex; pthread_cond_t condition; bool ready, resume;
    unsigned calls; uint32_t result;
} ImportRace;
static bool pause_import(void* context) {
    ImportRace* race=context;
    if (++race->calls==2) { /* The first callback is bounded and under admission lock. */
        pthread_mutex_lock(&race->mutex); race->ready=true; pthread_cond_signal(&race->condition);
        while(!race->resume) pthread_cond_wait(&race->condition,&race->mutex);
        pthread_mutex_unlock(&race->mutex);
    }
    return false;
}
static void* run_import(void* context) {
    ImportRace* race=context;
    race->result=rdp_ws_import_at(race->ws,0,race->parent,"late.bin",race->source,race->size,pause_import,race);
    return NULL;
}

int main(void) {
    char home[] = "/tmp/rdpsync-workspace-test-XXXXXX"; assert(mkdtemp(home));
    int home_fd = open(home, O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(home_fd >= 0);
    RdpWorkspace* ws = NULL; assert(rdp_ws_create(home_fd, key, NULL, &ws) == RDP_WS_OK);
    uint32_t h = open_file(ws, 9, "bytes.bin", 0xC0010183U, 7, 2, 0, false);
    const unsigned char bytes[] = {0, 1, 255, 127, 16}; uint32_t actual = 0;
    assert(rdp_ws_write(ws, 9, h, 0, bytes, sizeof(bytes), &actual) == 0 && actual == sizeof(bytes));
    assert(rdp_ws_write(ws, 8, h, 0, bytes, 1, &actual) == RDP_WS_INVALID_HANDLE);
    unsigned char readback[16] = {0};
    assert(rdp_ws_read(ws, 9, h, 1, readback, 3, &actual) == 0 && actual == 3 && !memcmp(bytes+1, readback, 3));
    assert(rdp_ws_read(ws, 9, h, UINT64_MAX, readback, 1, &actual) == RDP_WS_INVALID_PARAMETER);
    assert(rdp_ws_truncate(ws, 9, h, 3) == 0);
    assert(rdp_ws_truncate(ws, 9, h, RDP_WS_MAX_FILE_BYTES+1) == RDP_WS_FILE_TOO_LARGE);
    assert(rdp_ws_set_basic(ws, 9, h, 0x80, 1234567800000, 1234567890000) == 0);
    RdpWorkspaceEntry entry; assert(rdp_ws_stat(ws, 9, h, &entry) == 0 && entry.size == 3 && entry.modified_millis == 1234567890000);
    assert(entry.access_millis == 1234567800000 && entry.change_millis > entry.modified_millis);
    assert(rdp_ws_set_basic(ws, 9, h, 0x2, -1, -1) == RDP_WS_NOT_SUPPORTED);
    assert(rdp_ws_close(ws, 9, h) == 0);

    /* Case collisions, traversal, alias and reserved names never create/open. */
    rejected_open(ws, "../bytes.bin", RDP_WS_OBJECT_NAME_INVALID);
    rejected_open(ws, "a//b", RDP_WS_OBJECT_NAME_INVALID);
    rejected_open(ws, "C:bytes.bin", RDP_WS_OBJECT_NAME_INVALID);
    rejected_open(ws, "bytes.bin.", RDP_WS_OBJECT_NAME_INVALID);
    rejected_open(ws, "NUL", RDP_WS_OBJECT_NAME_INVALID);
    uint32_t unused, info;
    assert(rdp_ws_open(ws, 9, "BYTES.BIN", 3, 7, 2, 0, false, 0, &unused, &info) == RDP_WS_OBJECT_NAME_COLLISION);
    h = open_file(ws, 9, "BYTES.BIN", 1, 1, 1, 0, false);
    assert(rdp_ws_open(ws, 10, "bytes.bin", 2, 7, 1, 0, false, 0, &unused, &info) == RDP_WS_SHARING_VIOLATION);
    assert(rdp_ws_open(ws, 0, "bytes.bin", 1, 1, 1, 0, false, 0, &unused, &info) == 0);
    uint32_t local = unused;
    rdp_ws_close_owner(ws, 9); /* revoke remote, not local export or content */
    assert(rdp_ws_read(ws, 9, h, 0, readback, 1, &actual) == RDP_WS_INVALID_HANDLE);
    assert(rdp_ws_read(ws, 0, local, 0, readback, 3, &actual) == 0 && actual == 3);
    assert(rdp_ws_close(ws, 0, local) == 0);

    /* Read-only is a capability, not merely an attribute bit. */
    assert(rdp_ws_open(ws, 7, "new.bin", 3, 7, 2, 0, true, 0, &unused, &info) == RDP_WS_ACCESS_DENIED);
    h = open_file(ws, 7, "bytes.bin", 1, 7, 1, 0, true);
    assert(rdp_ws_write(ws, 7, h, 0, bytes, 1, &actual) == RDP_WS_ACCESS_DENIED);
    assert(rdp_ws_set_delete(ws, 7, h, true) == RDP_WS_ACCESS_DENIED);
    assert(rdp_ws_rename(ws, 7, h, "changed.bin", false) == RDP_WS_ACCESS_DENIED);
    assert(rdp_ws_close(ws, 7, h) == 0);

    /* Rename no-replace preserves target; explicit replacement is atomic and
     * denied while an export/read lease holds that destination. */
    uint32_t target = open_file(ws, 0, "target.bin", 3, 7, 2, 0, false);
    assert(rdp_ws_write(ws, 0, target, 0, bytes+3, 2, &actual) == 0);
    h = open_file(ws, 9, "bytes.bin", 0x10003, 7, 1, 0, false);
    assert(rdp_ws_rename(ws, 9, h, "target.bin", false) == RDP_WS_OBJECT_NAME_COLLISION);
    assert(rdp_ws_rename(ws, 9, h, "target.bin", true) == RDP_WS_SHARING_VIOLATION);
    assert(rdp_ws_close(ws, 0, target) == 0);
    assert(rdp_ws_rename(ws, 9, h, "target.bin", true) == 0);
    assert(rdp_ws_stat(ws, 9, h, &entry) == 0 && !strcmp(entry.name,"target.bin") && entry.size == 3);
    assert(rdp_ws_rename(ws, 9, h, "TARGET.bin", false) == 0);
    assert(rdp_ws_close(ws, 9, h) == 0);

    uint32_t folder = open_file(ws, 9, "嵌套", 0x10001, 7, 2, 1, false);
    h = open_file(ws, 9, "嵌套/empty.txt", 0x10003, 7, 2, 0, false);
    assert(rdp_ws_set_delete(ws, 9, folder, true) == RDP_WS_DIRECTORY_NOT_EMPTY);
    assert(rdp_ws_set_delete(ws, 9, h, true) == 0);
    assert(rdp_ws_open(ws, 9, "嵌套/empty.txt", 1, 7, 1, 0, false, 0, &unused, &info) == RDP_WS_DELETE_PENDING);
    assert(rdp_ws_close(ws, 9, h) == 0);
    assert(rdp_ws_set_delete(ws, 9, folder, true) == 0);
    assert(rdp_ws_open(ws, 9, "嵌套/new", 3, 7, 2, 0, false, 0, &unused, &info) == RDP_WS_DELETE_PENDING);
    assert(rdp_ws_close(ws, 9, folder) == 0);

    /* Real rootfd traversal: neither final nor intermediate symlink escapes;
     * hardlink aliases and special files are rejected. */
    int root = openat(home_fd,"shared-drive",O_RDONLY|O_DIRECTORY); assert(root>=0);
    assert(symlinkat("../outside",root,"escape")==0);
    int outside = openat(home_fd,"outside",O_CREAT|O_EXCL|O_WRONLY,0600); assert(outside>=0); assert(write(outside,"safe",4)==4); close(outside);
    rejected_open(ws,"escape",RDP_WS_ACCESS_DENIED); assert(unlinkat(root,"escape",0)==0);
    assert(symlinkat("..",root,"escape")==0); rejected_open(ws,"escape/outside",RDP_WS_ACCESS_DENIED); assert(unlinkat(root,"escape",0)==0);
    assert(linkat(home_fd,"outside",root,"alias",0)==0); rejected_open(ws,"alias",RDP_WS_ACCESS_DENIED); assert(unlinkat(root,"alias",0)==0);
    assert(mkfifoat(root,"pipe",0600)==0); rejected_open(ws,"pipe",RDP_WS_ACCESS_DENIED); assert(unlinkat(root,"pipe",0)==0);

    /* Import owns only an incomplete private file until exclusive commit. */
    int source = openat(home_fd,"source",O_CREAT|O_EXCL|O_WRONLY,0600); assert(source>=0);
    unsigned char payload[131073]; for(size_t i=0;i<sizeof(payload);i++) payload[i]=(unsigned char)(i*31U+7U);
    assert(write(source,payload,sizeof(payload))==(ssize_t)sizeof(payload)); close(source);
    source=openat(home_fd,"source",O_RDONLY); assert(source>=0);
    int cancel_calls=0;
    assert(rdp_ws_import(ws,"","cancelled.bin",source,sizeof(payload),cancel,&cancel_calls)==RDP_WS_CANCELLED);
    int pending=openat(home_fd,"shared-drive-pending",O_RDONLY|O_DIRECTORY); assert(pending>=0); assert(count_files(pending)==0);
    assert(rdp_ws_import(ws,"","imported.bin",source,sizeof(payload),NULL,NULL)==0);
    assert(rdp_ws_import(ws,"","IMPORTED.BIN",source,sizeof(payload),NULL,NULL)==RDP_WS_OBJECT_NAME_COLLISION);
    assert(count_files(pending)==0); close(pending); close(source);
    h=open_file(ws,0,"imported.bin",1,1,1,0,false);
    unsigned char* imported=malloc(sizeof(payload)); assert(imported);
    assert(rdp_ws_read(ws,0,h,0,imported,sizeof(payload),&actual)==0 && actual==sizeof(payload));
    assert(!memcmp(payload,imported,sizeof(payload))); free(imported); assert(rdp_ws_close(ws,0,h)==0);
    RdpWorkspaceSpace space; assert(rdp_ws_space(ws,&space)==0 && space.used_bytes==sizeof(payload)+3 && space.entries==2);

    /* Browser enumeration uses typed handles and detects mid-enumeration
     * namespace changes rather than silently reusing stale directory cursors. */
    folder=open_file(ws,0,"",1,7,1,1,false);
    assert(rdp_ws_next(ws,0,folder,true,&entry)==0);
    h=open_file(ws,9,"other",3,7,2,0,false); assert(rdp_ws_close(ws,9,h)==0);
    assert(rdp_ws_next(ws,0,folder,false,&entry)==RDP_WS_NOTIFY_ENUM_DIR);
    assert(rdp_ws_close(ws,0,folder)==0);
    /* Closed descendants still constrain moves; active export descendants
     * also block ancestor renames through the common sharing table. */
    uint32_t tree=open_file(ws,9,"tree",0x10001,7,2,1,false);
    h=open_file(ws,9,"tree/child",3,7,2,0,false); assert(rdp_ws_close(ws,9,h)==0);
    char deep[128]="";
    for(unsigned i=0;i<15;i++) { if(i) strcat(deep,"/"); strcat(deep,"d"); h=open_file(ws,9,deep,1,7,2,1,false); assert(rdp_ws_close(ws,9,h)==0); }
    char destination[160]; snprintf(destination,sizeof(destination),"%s/moved",deep);
    assert(rdp_ws_rename(ws,9,tree,destination,false)==RDP_WS_OBJECT_NAME_INVALID);
    h=open_file(ws,0,"tree/child",1,1,1,0,false);
    assert(rdp_ws_rename(ws,9,tree,"tree-moved",false)==RDP_WS_SHARING_VIOLATION);
    assert(rdp_ws_close(ws,0,h)==0); assert(rdp_ws_close(ws,9,tree)==0);
    assert(rdp_ws_open(ws,9,"TARGET.bin",1,7,1,8,false,0,&unused,&info)==RDP_WS_NOT_SUPPORTED);
    assert(rdp_ws_open(ws,9,"TARGET.bin",1,7,1,0x10000000,false,0,&unused,&info)==RDP_WS_NOT_SUPPORTED);
    h=open_file(ws,9,"TARGET.bin",3,7,1,2,false); assert(rdp_ws_write(ws,9,h,0,bytes,1,&actual)==0); assert(rdp_ws_close(ws,9,h)==0);
#ifdef RDPSYNC_WS_TEST_FAULTS
    assert(rdp_ws_space(ws,&space)==0); uint32_t before_entries=space.entries;
    rdp_ws_test_fail_created_directory_open=true;
    assert(rdp_ws_open(ws,9,"fd-pressure",1,7,2,1,false,0,&unused,&info)==RDP_WS_INSUFFICIENT_RESOURCES);
    rdp_ws_test_fail_created_directory_open=false;
    assert(rdp_ws_space(ws,&space)==0 && space.entries==before_entries+1);
    h=open_file(ws,9,"fd-pressure",0x10001,7,1,1,false);
    assert(rdp_ws_set_delete(ws,9,h,true)==0); assert(rdp_ws_close(ws,9,h)==0);
#endif
    /* Import copies outside the broker lock, but a closed picker selection
     * cannot publish into a replacement directory at the same path. */
    folder=open_file(ws,0,"leased-target",3,3,2,1,false);
    source=openat(home_fd,"source",O_RDONLY); assert(source>=0);
    ImportRace race={.ws=ws,.parent=folder,.source=source,.size=sizeof(payload),.mutex=PTHREAD_MUTEX_INITIALIZER,.condition=PTHREAD_COND_INITIALIZER};
    pthread_t worker; assert(pthread_create(&worker,NULL,run_import,&race)==0);
    pthread_mutex_lock(&race.mutex); while(!race.ready) pthread_cond_wait(&race.condition,&race.mutex); pthread_mutex_unlock(&race.mutex);
    assert(rdp_ws_space(ws,&space)==0); /* would deadlock if a source read held the global mutex */
    assert(rdp_ws_close(ws,0,folder)==0);
    folder=open_file(ws,9,"leased-target",0x10001,7,1,1,false); assert(rdp_ws_set_delete(ws,9,folder,true)==0); assert(rdp_ws_close(ws,9,folder)==0);
    folder=open_file(ws,9,"leased-target",0x10003,7,2,1,false);
    pthread_mutex_lock(&race.mutex); race.resume=true; pthread_cond_signal(&race.condition); pthread_mutex_unlock(&race.mutex);
    assert(pthread_join(worker,NULL)==0 && race.result==RDP_WS_CANCELLED); close(source);
    pthread_cond_destroy(&race.condition); pthread_mutex_destroy(&race.mutex);
    assert(rdp_ws_next(ws,9,folder,true,&entry)==RDP_WS_NO_MORE_FILES);
    assert(rdp_ws_set_delete(ws,9,folder,true)==0); assert(rdp_ws_close(ws,9,folder)==0);

    /* Sparse logical size still consumes the stated workspace quota. */
    h=open_file(ws,9,"quota-a",0x10003,7,2,0,false);
    target=open_file(ws,9,"quota-b",0x10003,7,2,0,false);
    assert(rdp_ws_truncate(ws,9,h,RDP_WS_MAX_FILE_BYTES)==0);
    assert(rdp_ws_truncate(ws,9,target,RDP_WS_MAX_FILE_BYTES)==RDP_WS_DISK_FULL);
    assert(rdp_ws_truncate(ws,9,h,0)==0);
    assert(rdp_ws_truncate(ws,9,target,RDP_WS_MAX_FILE_BYTES)==0);
    assert(rdp_ws_truncate(ws,9,target,0)==0);
    assert(rdp_ws_set_delete(ws,9,h,true)==0 && rdp_ws_close(ws,9,h)==0);
    assert(rdp_ws_set_delete(ws,9,target,true)==0 && rdp_ws_close(ws,9,target)==0);

    bool matches=false;
    assert(rdp_ws_pattern_matches(ws,"?好.*","你好.bin",&matches)==0 && matches);
    assert(rdp_ws_pattern_matches(ws,"*.*","nodot",&matches)==0 && matches);
    assert(rdp_ws_pattern_matches(ws,"A?","abc",&matches)==0 && !matches);
    folder=open_file(ws,9,"tree",0x10001,7,1,1,false);
    assert(rdp_ws_handle_path_matches(ws,9,folder,"TREE",&matches)==0 && matches);
    assert(rdp_ws_rename(ws,9,folder,"tree-renamed",false)==0);
    assert(rdp_ws_handle_path_matches(ws,9,folder,"tree",&matches)==0 && !matches);
    assert(rdp_ws_handle_path_matches(ws,9,folder,"TREE-RENAMED",&matches)==0 && matches);
    assert(rdp_ws_close(ws,9,folder)==0);

    /* FILE_ATTRIBUTE_READONLY is persistent metadata, not just a session flag. */
    h=open_file(ws,9,"TARGET.bin",0x10103,7,1,0,false);
    assert(rdp_ws_set_basic(ws,9,h,1,-1,-1)==0);
    assert(rdp_ws_stat(ws,9,h,&entry)==0 && entry.read_only);
    assert(rdp_ws_write(ws,9,h,0,bytes,1,&actual)==RDP_WS_ACCESS_DENIED);
    assert(rdp_ws_set_delete(ws,9,h,true)==RDP_WS_ACCESS_DENIED);
    assert(rdp_ws_set_basic(ws,9,h,0x80,-1,-1)==0);
    assert(rdp_ws_stat(ws,9,h,&entry)==0 && !entry.read_only);
    assert(rdp_ws_close(ws,9,h)==0);

    /* A FALSE disposition cannot undo an accepted CREATE delete-on-close. */
    h=open_file(ws,9,"delete-on-close",0x10003,7,2,RDP_WS_DELETE_ON_CLOSE,false);
    assert(rdp_ws_set_delete(ws,9,h,false)==0);
    assert(rdp_ws_stat(ws,9,h,&entry)==0 && entry.delete_pending);
    rdp_ws_close_owner(ws,9);
    rejected_open(ws,"delete-on-close",RDP_WS_OBJECT_NAME_NOT_FOUND);
    folder=open_file(ws,9,"tree-renamed",1,7,1,RDP_WS_DIRECTORY_FILE|0x4000U,false);
    assert(rdp_ws_close(ws,9,folder)==0);
    assert(rdp_ws_open(ws,9,"TARGET.bin",3,7,1,0x4000U,true,0,&unused,&info)==RDP_WS_ACCESS_DENIED);

    /* The app cannot run two independently synchronized brokers over one root. */
    RdpWorkspace* duplicate_ws=NULL;
    assert(rdp_ws_create(home_fd,key,NULL,&duplicate_ws)==RDP_WS_SHARING_VIOLATION && !duplicate_ws);
    assert(rdp_ws_space(ws,&space)==0); uint32_t final_entries=space.entries;
    rdp_ws_destroy(ws); ws=NULL;
    assert(rdp_ws_create(home_fd,key,NULL,&ws)==0); /* No session/restart cleanup of committed files. */
    assert(rdp_ws_space(ws,&space)==0 && space.used_bytes==sizeof(payload)+3 && space.entries==final_entries);
    rdp_ws_destroy(ws); ws=NULL;
    pending=openat(home_fd,"shared-drive-pending",O_RDONLY|O_DIRECTORY); assert(pending>=0);
    const char* malformed[] = {"import-999999999999999999999999999999-0000000000000001.part",
        "import-01-0000000000000001.part", "import-1-0000000000000000.part", "import-1-000000000000000A.part",
        "import-1-a.part", "import-1-0000000000000001.part.extra"};
    for(size_t i=0;i<sizeof(malformed)/sizeof(malformed[0]);i++) {
        int abandoned=openat(pending,malformed[i],O_CREAT|O_EXCL|O_WRONLY,0600); assert(abandoned>=0); close(abandoned);
        assert(rdp_ws_create(home_fd,key,NULL,&ws)==RDP_WS_ACCESS_DENIED && !ws);
        assert(faccessat(pending,malformed[i],F_OK,0)==0); /* Unexpected names are never deleted. */
        assert(unlinkat(pending,malformed[i],0)==0);
    }
    int abandoned=openat(pending,"import-1-0000000000000001.part",O_CREAT|O_EXCL|O_WRONLY,0600); assert(abandoned>=0); close(abandoned);
    assert(rdp_ws_create(home_fd,key,NULL,&ws)==0 && count_files(pending)==0);
    assert(rdp_ws_space(ws,&space)==0 && space.entries==final_entries);
    rdp_ws_destroy(ws); close(pending); close(root); close(home_fd);
    char command[512]; snprintf(command,sizeof(command),"rm -rf -- '%s'",home); assert(system(command)==0);
    puts("workspace_test: persistent storage, isolation, sharing, quota, import and revocation passed");
    return 0;
}
