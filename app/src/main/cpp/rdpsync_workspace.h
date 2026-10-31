#ifndef RDPSYNC_WORKSPACE_H
#define RDPSYNC_WORKSPACE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Persistent app-owned files. This is not a mirror of an Android SAF tree.
 * Every remote IRP and local browser/import/export action uses this broker. */
#define RDP_WS_MAX_FILE_BYTES (UINT64_C(512) * 1024 * 1024)
#define RDP_WS_MAX_BYTES (UINT64_C(1024) * 1024 * 1024)
#define RDP_WS_RESERVE_BYTES (UINT64_C(128) * 1024 * 1024)
#define RDP_WS_MAX_ENTRIES 4096U
#define RDP_WS_MAX_DEPTH 16U
#define RDP_WS_MAX_PATH 4096U
#define RDP_WS_MAX_NAME 255U
#define RDP_WS_MAX_KEY 1024U
#define RDP_WS_MAX_HANDLES 128U
#define RDP_WS_MAX_IO (1024U * 1024U)

#define RDP_WS_OK UINT32_C(0x00000000)
#define RDP_WS_NO_MORE_FILES UINT32_C(0x80000006)
#define RDP_WS_UNSUCCESSFUL UINT32_C(0xC0000001)
#define RDP_WS_NOT_IMPLEMENTED UINT32_C(0xC0000002)
#define RDP_WS_INVALID_HANDLE UINT32_C(0xC0000008)
#define RDP_WS_INVALID_PARAMETER UINT32_C(0xC000000D)
#define RDP_WS_NO_SUCH_FILE UINT32_C(0xC000000F)
#define RDP_WS_END_OF_FILE UINT32_C(0xC0000011)
#define RDP_WS_ACCESS_DENIED UINT32_C(0xC0000022)
#define RDP_WS_BUFFER_TOO_SMALL UINT32_C(0xC0000023)
#define RDP_WS_OBJECT_NAME_INVALID UINT32_C(0xC0000033)
#define RDP_WS_OBJECT_NAME_NOT_FOUND UINT32_C(0xC0000034)
#define RDP_WS_OBJECT_NAME_COLLISION UINT32_C(0xC0000035)
#define RDP_WS_OBJECT_PATH_NOT_FOUND UINT32_C(0xC000003A)
#define RDP_WS_SHARING_VIOLATION UINT32_C(0xC0000043)
#define RDP_WS_DELETE_PENDING UINT32_C(0xC0000056)
#define RDP_WS_DISK_FULL UINT32_C(0xC000007F)
#define RDP_WS_INSUFFICIENT_RESOURCES UINT32_C(0xC000009A)
#define RDP_WS_FILE_IS_A_DIRECTORY UINT32_C(0xC00000BA)
#define RDP_WS_NOT_SUPPORTED UINT32_C(0xC00000BB)
#define RDP_WS_DIRECTORY_NOT_EMPTY UINT32_C(0xC0000101)
#define RDP_WS_NOT_A_DIRECTORY UINT32_C(0xC0000103)
#define RDP_WS_CANCELLED UINT32_C(0xC0000120)
#define RDP_WS_FILE_TOO_LARGE UINT32_C(0xC0000904)
#define RDP_WS_NOTIFY_ENUM_DIR UINT32_C(0x0000010C)

/* Windows CREATE access/share/disposition/options values, not POSIX flags. */
#define RDP_WS_READ 1U
#define RDP_WS_WRITE 2U
#define RDP_WS_DELETE 4U
#define RDP_WS_FILE_SUPERSEDE 0U
#define RDP_WS_FILE_OPEN 1U
#define RDP_WS_FILE_CREATE 2U
#define RDP_WS_FILE_OPEN_IF 3U
#define RDP_WS_FILE_OVERWRITE 4U
#define RDP_WS_FILE_OVERWRITE_IF 5U
#define RDP_WS_DIRECTORY_FILE 1U
#define RDP_WS_NON_DIRECTORY_FILE 0x40U
#define RDP_WS_DELETE_ON_CLOSE 0x1000U

/* Must be pure, bounded, non-reentrant and perform no IO. Return a strict NFC,
 * case-insensitive collision key, or false for malformed/unsafe UTF-8 names.
 * Android uses the same Java policy for both UI and native operations. */
typedef bool (*RdpWorkspaceNameKey)(void* context, const char* name, char* key, size_t capacity);
typedef bool (*RdpWorkspaceCancelled)(void* context);
typedef struct RdpWorkspace RdpWorkspace;
typedef struct {
    char name[RDP_WS_MAX_NAME + 1];
    bool directory;
    bool read_only;
    bool delete_pending;
    uint64_t size;
    int64_t modified_millis;
    int64_t access_millis;
    int64_t change_millis; /* POSIX metadata change, not creation/birth time. */
} RdpWorkspaceEntry;
typedef struct {
    uint64_t used_bytes;
    uint64_t available_bytes; /* min(quota remaining, filesystem free less reserve) */
    uint32_t entries;
} RdpWorkspaceSpace;

/* trusted_home_fd is the already validated private runtime HOME directory.
 * Duplicates the descriptor. Creates only shared-drive/shared-drive-pending.
 * Never purges committed files on init/destroy/revoke/restart. Previously
 * accepted delete-on-close intent is applied when its last handle closes,
 * except exact legacy wire directory intent at its requesting close (below). */
uint32_t rdp_ws_create(int trusted_home_fd, RdpWorkspaceNameKey name_key, void* name_context,
                       RdpWorkspace** result);
void rdp_ws_destroy(RdpWorkspace* workspace);
uint32_t rdp_ws_space(RdpWorkspace* workspace, RdpWorkspaceSpace* result);
uint32_t rdp_ws_open(RdpWorkspace* workspace, uint64_t owner, const char* path,
    uint32_t desired_access, uint32_t share_access, uint32_t disposition,
    uint32_t options, bool read_only, uint64_t allocation_size,
    uint32_t* handle, uint32_t* create_information);
uint32_t rdp_ws_stat(RdpWorkspace* workspace, uint64_t owner, uint32_t handle, RdpWorkspaceEntry* entry);
uint32_t rdp_ws_read(RdpWorkspace* workspace, uint64_t owner, uint32_t handle,
    uint64_t offset, void* buffer, uint32_t length, uint32_t* actual);
uint32_t rdp_ws_write(RdpWorkspace* workspace, uint64_t owner, uint32_t handle,
    uint64_t offset, const void* buffer, uint32_t length, uint32_t* actual);
uint32_t rdp_ws_truncate(RdpWorkspace* workspace, uint64_t owner, uint32_t handle, uint64_t size);
uint32_t rdp_ws_rename(RdpWorkspace* workspace, uint64_t owner, uint32_t handle,
    const char* new_path, bool replace);
uint32_t rdp_ws_set_delete(RdpWorkspace* workspace, uint64_t owner, uint32_t handle, bool deleting);
/* Wire-only length-zero disposition; never exposed by JNI/local API.
 * Requires remote owner, RW handle, exact legacy shape or existing DELETE.
 * An exact legacy directory request unlinks at its requesting close after
 * revalidation; retained mkdir IDs then permit CLOSE only. This accommodates
 * pinned xrdp retaining mkdir handles, without changing ordinary dispositions. */
uint32_t rdp_ws_wire_legacy_delete(RdpWorkspace* workspace, uint64_t owner, uint32_t handle);
/* -1 leaves a timestamp unchanged; 0 is the Unix epoch, not a sentinel. */
uint32_t rdp_ws_set_basic(RdpWorkspace* workspace, uint64_t owner, uint32_t handle,
    uint32_t attributes, int64_t access_millis, int64_t write_millis);
uint32_t rdp_ws_flush(RdpWorkspace* workspace, uint64_t owner, uint32_t handle);
uint32_t rdp_ws_next(RdpWorkspace* workspace, uint64_t owner, uint32_t directory_handle,
    bool restart, RdpWorkspaceEntry* entry);
uint32_t rdp_ws_close(RdpWorkspace* workspace, uint64_t owner, uint32_t handle);
void rdp_ws_close_owner(RdpWorkspace* workspace, uint64_t owner);

/* Synchronous, bounded import from a held readonly regular-file FD. Copies into
 * owned pending storage before atomic exclusive publication; no live SAF URI or
 * user-provided filesystem path reaches this API. The caller keeps source_fd
 * valid throughout. Local owner 0 is reserved; imports survive remote revoke.
 * Failure/cancellation deletes only this call's own incomplete pending file. */
uint32_t rdp_ws_import(RdpWorkspace* workspace, const char* parent_path, const char* name,
    int source_fd, uint64_t size, RdpWorkspaceCancelled cancelled, void* cancel_context);

/* Picker/confirmation selections are ordinary bounded local-owner handles.
 * Import selections need WRITE access and should deny share-DELETE. */
uint32_t rdp_ws_import_at(RdpWorkspace* workspace, uint64_t owner, uint32_t directory_handle,
    const char* name, int source_fd, uint64_t size, RdpWorkspaceCancelled cancelled, void* cancel_context);
uint32_t rdp_ws_mkdir_at(RdpWorkspace* workspace, uint64_t owner, uint32_t directory_handle, const char* name);
uint32_t rdp_ws_handle_path_matches(RdpWorkspace* workspace, uint64_t owner, uint32_t handle,
    const char* path, bool* matches);
/* pattern_key must be produced by the pure JNI nativePatternKey policy; only
 * '*' and '?' wildcard semantics are supported, with '*.*' matching all. */
uint32_t rdp_ws_pattern_matches(RdpWorkspace* workspace, const char* pattern_key, const char* name, bool* matches);

#endif
