#define _GNU_SOURCE
#include "rdpsync_workspace.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <time.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef RDPSYNC_WS_TEST_FAULTS
extern bool rdp_ws_test_fail_created_directory_open;
#endif

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1U
#endif

typedef struct {
    uint32_t id;
    uint64_t owner;
    int fd, parent_fd;
    dev_t dev;
    ino_t ino;
    uint32_t access, share, granted_access, create_options, create_disposition;
    bool append_only, write_through;
    bool directory, read_only, pending, forced_delete;
    bool legacy_directory_unlink, namespace_removed;
    char name[RDP_WS_MAX_NAME + 1];
    char path[RDP_WS_MAX_PATH + 1];
    DIR* cursor;
    uint64_t cursor_epoch;
    uint32_t cursor_count;
} WorkspaceHandle;

struct RdpWorkspace {
    pthread_mutex_t mutex;
    pthread_cond_t drained;
    atomic_bool shutting_down;
    int root_fd, pending_fd;
    RdpWorkspaceNameKey name_key;
    void* name_context;
    WorkspaceHandle handles[RDP_WS_MAX_HANDLES];
    uint32_t next_handle;
    uint32_t entries;
    uint64_t used_bytes, reserved_bytes, namespace_epoch, next_import;
    bool importing;
};

static uint32_t from_errno(int value) {
    switch (value) {
        case ENOENT: return RDP_WS_OBJECT_NAME_NOT_FOUND;
        case ENOTDIR: return RDP_WS_NOT_A_DIRECTORY;
        case EISDIR: return RDP_WS_FILE_IS_A_DIRECTORY;
        case EEXIST: return RDP_WS_OBJECT_NAME_COLLISION;
        case ENOTEMPTY: return RDP_WS_DIRECTORY_NOT_EMPTY;
        case ENOSPC: case EDQUOT: return RDP_WS_DISK_FULL;
        case EACCES: case EPERM: case ELOOP: return RDP_WS_ACCESS_DENIED;
        case ENAMETOOLONG: return RDP_WS_OBJECT_NAME_INVALID;
        case EMFILE: case ENFILE: case ENOMEM: return RDP_WS_INSUFFICIENT_RESOURCES;
        case ENOSYS: case ENOTSUP: return RDP_WS_NOT_SUPPORTED;
        case EINVAL: return RDP_WS_INVALID_PARAMETER;
        default: return RDP_WS_UNSUCCESSFUL;
    }
}

static int duplicate(int fd) { return fcntl(fd, F_DUPFD_CLOEXEC, 0); }
static int open_directory(int parent, const char* name) {
    return openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}
static bool same_inode(const WorkspaceHandle* handle, const struct stat* st) {
    return handle->id && handle->dev == st->st_dev && handle->ino == st->st_ino;
}
static bool pending_inode(RdpWorkspace* ws, const struct stat* st) {
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++)
        if (same_inode(&ws->handles[i], st) && ws->handles[i].pending) return true;
    return false;
}
static uint32_t regular_or_directory(const struct stat* st) {
    if (st->st_uid != geteuid() || (!S_ISREG(st->st_mode) && !S_ISDIR(st->st_mode)))
        return RDP_WS_ACCESS_DENIED;
    if (S_ISREG(st->st_mode) && st->st_nlink != 1) return RDP_WS_ACCESS_DENIED;
    if (st->st_size < 0 || (S_ISREG(st->st_mode) && (uint64_t)st->st_size > RDP_WS_MAX_FILE_BYTES))
        return RDP_WS_FILE_TOO_LARGE;
    return RDP_WS_OK;
}
static bool key_for(RdpWorkspace* ws, const char* name, char* key) {
    const size_t len = strnlen(name, RDP_WS_MAX_NAME + 1);
    if (!len || len > RDP_WS_MAX_NAME || !strcmp(name, ".") || !strcmp(name, "..") ||
        name[len - 1] == '.' || name[len - 1] == ' ') return false;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)name[i];
        if (c < 32 || c == 127 || strchr("/\\:<>\"|?*", c)) return false;
    }
    memset(key, 0, RDP_WS_MAX_KEY + 1);
    if (!ws->name_key(ws->name_context, name, key, RDP_WS_MAX_KEY + 1)) return false;
    return key[0] && strnlen(key, RDP_WS_MAX_KEY + 1) <= RDP_WS_MAX_KEY;
}

/* Both the protocol's backslash and the browser's slash are separators. No
 * dot segments, drive prefixes, UNC paths, empty components or silent rewrites. */
static uint32_t path_for(RdpWorkspace* ws, const char* input, char* path) {
    if (!input) return RDP_WS_OBJECT_NAME_INVALID;
    size_t n = strnlen(input, RDP_WS_MAX_PATH + 2);
    if (n > RDP_WS_MAX_PATH) return RDP_WS_OBJECT_NAME_INVALID;
    if (n && (*input == '/' || *input == '\\')) { input++; n--; }
    if (n && (*input == '/' || *input == '\\')) return RDP_WS_OBJECT_NAME_INVALID;
    if (!n) { path[0] = 0; return RDP_WS_OK; }
    memcpy(path, input, n + 1);
    uint32_t depth = 0;
    char* component = path;
    for (size_t i = 0; i <= n; i++) {
        if (path[i] == '\\') path[i] = '/';
        if (path[i] == '/' || !path[i]) {
            const char old = path[i]; path[i] = 0;
            char key[RDP_WS_MAX_KEY + 1];
            if (++depth > RDP_WS_MAX_DEPTH || !key_for(ws, component, key)) return RDP_WS_OBJECT_NAME_INVALID;
            path[i] = old; component = path + i + 1;
        }
    }
    return RDP_WS_OK;
}

static DIR* directory_stream(int fd) {
    int copy = open_directory(fd, ".");
    if (copy < 0) return NULL;
    DIR* stream = fdopendir(copy);
    if (!stream) close(copy);
    return stream;
}
static uint32_t find_child(RdpWorkspace* ws, int parent, const char* requested,
                           char* actual, struct stat* st) {
    char target[RDP_WS_MAX_KEY + 1];
    if (!key_for(ws, requested, target)) return RDP_WS_OBJECT_NAME_INVALID;
    DIR* stream = directory_stream(parent);
    if (!stream) return from_errno(errno);
    uint32_t result = RDP_WS_OBJECT_NAME_NOT_FOUND, count = 0;
    struct dirent* item;
    errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        char key[RDP_WS_MAX_KEY + 1];
        if (++count > RDP_WS_MAX_ENTRIES || !key_for(ws, item->d_name, key)) {
            result = RDP_WS_OBJECT_NAME_INVALID; break;
        }
        if (!strcmp(target, key)) {
            if (result == RDP_WS_OK) { result = RDP_WS_OBJECT_NAME_COLLISION; break; }
            if (fstatat(parent, item->d_name, st, AT_SYMLINK_NOFOLLOW) < 0) { result = from_errno(errno); break; }
            result = regular_or_directory(st);
            if (result != RDP_WS_OK) break;
            memcpy(actual, item->d_name, strlen(item->d_name) + 1);
        }
        errno = 0;
    }
    if (!item && errno) result = from_errno(errno);
    closedir(stream);
    return result;
}

static uint32_t parent_for(RdpWorkspace* ws, const char* path, int* parent,
                           char* name, char* canonical) {
    char normalized[RDP_WS_MAX_PATH + 1];
    uint32_t result = path_for(ws, path, normalized);
    if (result != RDP_WS_OK) return result;
    int fd = duplicate(ws->root_fd);
    if (fd < 0) return from_errno(errno);
    canonical[0] = 0;
    char* component = normalized;
    char* separator;
    while ((separator = strchr(component, '/'))) {
        *separator = 0;
        char actual[RDP_WS_MAX_NAME + 1]; struct stat st;
        result = find_child(ws, fd, component, actual, &st);
        if (result == RDP_WS_OK && !S_ISDIR(st.st_mode)) result = RDP_WS_NOT_A_DIRECTORY;
        if (result == RDP_WS_OK && pending_inode(ws, &st)) result = RDP_WS_DELETE_PENDING;
        if (result != RDP_WS_OK) { close(fd); return result; }
        int next = open_directory(fd, actual);
        if (next < 0) { result = from_errno(errno); close(fd); return result; }
        struct stat opened;
        if (fstat(next, &opened) || opened.st_dev != st.st_dev || opened.st_ino != st.st_ino) {
            close(next); close(fd); return RDP_WS_ACCESS_DENIED;
        }
        if (canonical[0]) strcat(canonical, "/");
        strcat(canonical, actual);
        close(fd); fd = next; component = separator + 1;
    }
    memcpy(name, component, strlen(component) + 1);
    *parent = fd;
    return RDP_WS_OK;
}

static int compare_keys(const void* a, const void* b) {
    return strcmp(*(const char* const*)a, *(const char* const*)b);
}
/* Bounded, fail-closed startup scan. No cleanup of unexpected user data. */
static uint32_t scan_directory(RdpWorkspace* ws, int fd, uint32_t depth,
                               uint64_t* bytes, uint32_t* entries, bool recurse) {
    DIR* stream = directory_stream(fd);
    if (!stream) return from_errno(errno);
    char** keys = calloc(RDP_WS_MAX_ENTRIES, sizeof(char*));
    if (!keys) { closedir(stream); return RDP_WS_INSUFFICIENT_RESOURCES; }
    size_t count = 0;
    uint32_t result = RDP_WS_OK;
    struct dirent* item;
    errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        char key[RDP_WS_MAX_KEY + 1]; struct stat st;
        if (count == RDP_WS_MAX_ENTRIES || ++*entries > RDP_WS_MAX_ENTRIES ||
            depth >= RDP_WS_MAX_DEPTH || !key_for(ws, item->d_name, key)) { result = RDP_WS_OBJECT_NAME_INVALID; break; }
        keys[count] = strdup(key);
        if (!keys[count++]) { result = RDP_WS_INSUFFICIENT_RESOURCES; break; }
        if (fstatat(fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW)) { result = from_errno(errno); break; }
        result = regular_or_directory(&st);
        if (result != RDP_WS_OK) break;
        if (S_ISREG(st.st_mode)) {
            if ((uint64_t)st.st_size > RDP_WS_MAX_BYTES - *bytes) { result = RDP_WS_DISK_FULL; break; }
            *bytes += (uint64_t)st.st_size;
        } else if (recurse) {
            int child = open_directory(fd, item->d_name);
            if (child < 0) { result = from_errno(errno); break; }
            result = scan_directory(ws, child, depth + 1, bytes, entries, true);
            close(child);
            if (result != RDP_WS_OK) break;
        }
        errno = 0;
    }
    if (!item && errno && result == RDP_WS_OK) result = from_errno(errno);
    if (result == RDP_WS_OK) {
        qsort(keys, count, sizeof(char*), compare_keys);
        for (size_t i = 1; i < count; i++) if (!strcmp(keys[i - 1], keys[i])) { result = RDP_WS_OBJECT_NAME_COLLISION; break; }
    }
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys); closedir(stream);
    return result;
}

/* Preflight ALL descendants, including those without an open handle, before
 * moving a directory deeper. Otherwise a legal rename could strand persistent
 * files beyond the path/depth limits and prevent the next startup. */
static uint32_t subtree_extent(int fd, uint32_t depth, size_t length,
    uint32_t* max_depth, size_t* max_length, uint32_t* count) {
    DIR* stream = directory_stream(fd);
    if (!stream) return from_errno(errno);
    uint32_t rc = RDP_WS_OK; struct dirent* item; errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        size_t next_length = length + strlen(item->d_name) + 1;
        if (++*count > RDP_WS_MAX_ENTRIES || depth + 1 > RDP_WS_MAX_DEPTH || next_length > RDP_WS_MAX_PATH) { rc = RDP_WS_OBJECT_NAME_INVALID; break; }
        if (depth + 1 > *max_depth) *max_depth = depth + 1;
        if (next_length > *max_length) *max_length = next_length;
        struct stat st;
        if (fstatat(fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW)) { rc = from_errno(errno); break; }
        rc = regular_or_directory(&st); if (rc != RDP_WS_OK) break;
        if (S_ISDIR(st.st_mode)) {
            int child = open_directory(fd, item->d_name);
            if (child < 0) { rc = from_errno(errno); break; }
            rc = subtree_extent(child, depth + 1, next_length, max_depth, max_length, count);
            close(child); if (rc != RDP_WS_OK) break;
        }
        errno = 0;
    }
    if (!item && errno && rc == RDP_WS_OK) rc = from_errno(errno);
    closedir(stream); return rc;
}

/* Accept only our exact crash-recovery names. Manual bounded parsing avoids
 * scanf integer overflow on unexpected files in the private pending directory. */
static bool pending_name(const char* name) {
    if (strnlen(name, 64) >= 64 || strncmp(name, "import-", 7)) return false;
    const char* cursor = name + 7;
    if (*cursor < '1' || *cursor > '9') return false;
    unsigned pid = 0;
    while (*cursor >= '0' && *cursor <= '9') {
        unsigned digit = (unsigned)(*cursor++ - '0');
        if (pid > ((unsigned)INT_MAX - digit) / 10) return false;
        pid = pid * 10 + digit;
    }
    if (*cursor++ != '-') return false;
    bool nonzero = false;
    for (unsigned i = 0; i < 16; i++) {
        unsigned char c = (unsigned char)*cursor++;
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        nonzero |= c != '0';
    }
    return nonzero && !strcmp(cursor, ".part");
}

static uint32_t clean_pending(RdpWorkspace* ws) {
    DIR* stream = directory_stream(ws->pending_fd);
    if (!stream) return from_errno(errno);
    uint32_t rc = RDP_WS_OK, count = 0; struct dirent* item; errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        struct stat st;
        if (++count > RDP_WS_MAX_ENTRIES || !pending_name(item->d_name) ||
            fstatat(ws->pending_fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW) || !S_ISREG(st.st_mode) ||
            st.st_uid != geteuid() || st.st_nlink != 1 || st.st_size < 0 || (uint64_t)st.st_size > RDP_WS_MAX_FILE_BYTES || (st.st_mode & 0077)) { rc = RDP_WS_ACCESS_DENIED; break; }
        if (unlinkat(ws->pending_fd, item->d_name, 0)) { rc = from_errno(errno); break; }
        errno = 0;
    }
    if (!item && errno && rc == RDP_WS_OK) rc = from_errno(errno);
    closedir(stream); return rc;
}

static uint32_t space_for(RdpWorkspace* ws, uint64_t growth) {
    if (ws->used_bytes > RDP_WS_MAX_BYTES || ws->reserved_bytes > RDP_WS_MAX_BYTES - ws->used_bytes ||
        growth > RDP_WS_MAX_BYTES - ws->used_bytes - ws->reserved_bytes) return RDP_WS_DISK_FULL;
    struct statvfs fs;
    if (fstatvfs(ws->root_fd, &fs)) return from_errno(errno);
    uint64_t available = fs.f_bavail > UINT64_MAX / (fs.f_frsize ? fs.f_frsize : 1) ?
        UINT64_MAX : (uint64_t)fs.f_bavail * fs.f_frsize;
    if (available < RDP_WS_RESERVE_BYTES || growth > available - RDP_WS_RESERVE_BYTES) return RDP_WS_DISK_FULL;
    return RDP_WS_OK;
}
static WorkspaceHandle* find_handle(RdpWorkspace* ws, uint64_t owner, uint32_t id) {
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++)
        if (ws->handles[i].id == id && id && ws->handles[i].owner == owner) return &ws->handles[i];
    return NULL;
}
/* Retained xrdp mkdir IDs survive namespace removal only for CLOSE. */
static WorkspaceHandle* get_handle(RdpWorkspace* ws, uint64_t owner, uint32_t id) {
    WorkspaceHandle* h = find_handle(ws, owner, id);
    return h && !h->namespace_removed ? h : NULL;
}
static WorkspaceHandle* empty_handle(RdpWorkspace* ws) {
    if (!ws->next_handle) return NULL; /* never recycle IDs after wrap */
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (!ws->handles[i].id) return &ws->handles[i];
    return NULL;
}
static uint32_t share_for(RdpWorkspace* ws, const struct stat* st, uint32_t access, uint32_t share) {
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* h = &ws->handles[i];
        if (!same_inode(h, st)) continue;
        if (h->pending) return RDP_WS_DELETE_PENDING;
        if ((access & ~h->share) || (h->access & ~share)) return RDP_WS_SHARING_VIOLATION;
    }
    return RDP_WS_OK;
}
static uint32_t effective_access(uint32_t requested) {
    uint32_t result = 0;
    if (requested & (UINT32_C(0x80000000) | UINT32_C(0x10000000) | 1U)) result |= RDP_WS_READ;
    if (requested & (UINT32_C(0x40000000) | UINT32_C(0x10000000) | 2U | 4U)) result |= RDP_WS_WRITE;
    if (requested & (UINT32_C(0x10000000) | 0x10000U)) result |= RDP_WS_DELETE;
    return result;
}
static void entry_for(WorkspaceHandle* h, const struct stat* st, RdpWorkspaceEntry* entry) {
    memset(entry, 0, sizeof(*entry));
    memcpy(entry->name, h->name, strlen(h->name) + 1);
    entry->directory = S_ISDIR(st->st_mode);
    entry->read_only = h->read_only || !(st->st_mode & S_IWUSR);
    entry->delete_pending = h->pending;
    entry->size = entry->directory ? 0 : (uint64_t)st->st_size;
    entry->modified_millis = (int64_t)st->st_mtim.tv_sec * 1000 + st->st_mtim.tv_nsec / 1000000;
    entry->access_millis = (int64_t)st->st_atim.tv_sec * 1000 + st->st_atim.tv_nsec / 1000000;
    entry->change_millis = (int64_t)st->st_ctim.tv_sec * 1000 + st->st_ctim.tv_nsec / 1000000;
}
static bool directory_empty(int fd) {
    DIR* stream = directory_stream(fd);
    if (!stream) return false;
    bool empty = true; struct dirent* item;
    errno = 0;
    while ((item = readdir(stream))) {
        if (strcmp(item->d_name, ".") && strcmp(item->d_name, "..")) { empty = false; break; }
        errno = 0;
    }
    if (!item && errno) empty = false;
    closedir(stream); return empty;
}
static int exclusive_rename(int old_fd, const char* old_name, int new_fd, const char* new_name) {
#ifdef SYS_renameat2
    return (int)syscall(SYS_renameat2, old_fd, old_name, new_fd, new_name, RENAME_NOREPLACE);
#else
    (void)old_fd; (void)old_name; (void)new_fd; (void)new_name;
    errno = ENOSYS; return -1;
#endif
}

uint32_t rdp_ws_create(int trusted_home_fd, RdpWorkspaceNameKey name_key, void* name_context,
                       RdpWorkspace** result) {
    if (!result || !name_key) return RDP_WS_INVALID_PARAMETER;
    *result = NULL;
    struct stat home;
    if (fstat(trusted_home_fd, &home) || !S_ISDIR(home.st_mode) || home.st_uid != geteuid() || (home.st_mode & 0022))
        return RDP_WS_ACCESS_DENIED;
    RdpWorkspace* ws = calloc(1, sizeof(*ws));
    if (!ws) return RDP_WS_INSUFFICIENT_RESOURCES;
    ws->root_fd = ws->pending_fd = -1;
    ws->name_key = name_key; ws->name_context = name_context; ws->next_handle = 1; ws->namespace_epoch = 1;
    if (pthread_mutex_init(&ws->mutex, NULL)) { free(ws); return RDP_WS_INSUFFICIENT_RESOURCES; }
    if (pthread_cond_init(&ws->drained, NULL)) { pthread_mutex_destroy(&ws->mutex); free(ws); return RDP_WS_INSUFFICIENT_RESOURCES; }
    atomic_init(&ws->shutting_down, false);
    if ((mkdirat(trusted_home_fd, "shared-drive", 0700) && errno != EEXIST) ||
        (mkdirat(trusted_home_fd, "shared-drive-pending", 0700) && errno != EEXIST)) {
        uint32_t rc = from_errno(errno); rdp_ws_destroy(ws); return rc;
    }
    ws->root_fd = open_directory(trusted_home_fd, "shared-drive");
    ws->pending_fd = open_directory(trusted_home_fd, "shared-drive-pending");
    struct stat root, pending;
    if (ws->root_fd < 0 || ws->pending_fd < 0 || fstat(ws->root_fd, &root) || fstat(ws->pending_fd, &pending) ||
        root.st_uid != geteuid() || pending.st_uid != geteuid() || (root.st_mode & 0077) || (pending.st_mode & 0077)) {
        rdp_ws_destroy(ws); return RDP_WS_ACCESS_DENIED;
    }
    if (flock(ws->root_fd, LOCK_EX | LOCK_NB)) { rdp_ws_destroy(ws); return RDP_WS_SHARING_VIOLATION; }
    uint32_t rc = clean_pending(ws);
    if (rc == RDP_WS_OK) rc = scan_directory(ws, ws->root_fd, 0, &ws->used_bytes, &ws->entries, true);
    if (rc != RDP_WS_OK) { rdp_ws_destroy(ws); return rc; }
    *result = ws; return RDP_WS_OK;
}

uint32_t rdp_ws_space(RdpWorkspace* ws, RdpWorkspaceSpace* result) {
    if (!ws || !result) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    struct statvfs fs; uint32_t rc = RDP_WS_OK;
    if (fstatvfs(ws->root_fd, &fs)) rc = from_errno(errno);
    else {
        uint64_t available = fs.f_bavail > UINT64_MAX / (fs.f_frsize ? fs.f_frsize : 1) ? UINT64_MAX : (uint64_t)fs.f_bavail * fs.f_frsize;
        available = available > RDP_WS_RESERVE_BYTES ? available - RDP_WS_RESERVE_BYTES : 0;
        uint64_t quota = RDP_WS_MAX_BYTES - ws->used_bytes - ws->reserved_bytes;
        result->used_bytes = ws->used_bytes; result->available_bytes = available < quota ? available : quota;
        result->entries = ws->entries;
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_open(RdpWorkspace* ws, uint64_t owner, const char* path,
    uint32_t desired_access, uint32_t share_access, uint32_t disposition,
    uint32_t options, bool read_only, uint64_t allocation_size,
    uint32_t* handle, uint32_t* create_information) {
    if (!ws || !handle || !create_information || share_access > 7 || disposition > 5) return RDP_WS_INVALID_PARAMETER;
    *handle = 0; *create_information = 0;
    if (disposition == RDP_WS_FILE_SUPERSEDE) return RDP_WS_NOT_SUPPORTED;
    /* Synchronous IO is already serialized. Sequential/random are cache hints.
     * Write-through is implemented using O_SYNC plus metadata fsync. Buffering,
     * reparse, by-ID, oplock and unknown options are not pretended. Backup
     * intent permits ordinary directory opens but NEVER overrides any access,
     * read-only, sharing, containment or quota check (no backup privilege). */
    const uint32_t supported_options = 1U | 2U | 4U | 0x10U | 0x20U | 0x40U | 0x800U | 0x1000U | 0x4000U;
    if (options & ~supported_options) return RDP_WS_NOT_SUPPORTED;
    if ((options & 0x30U) == 0x30U || ((options & 4U) && (options & 0x800U))) return RDP_WS_INVALID_PARAMETER;
    if ((options & RDP_WS_DIRECTORY_FILE) && (options & RDP_WS_NON_DIRECTORY_FILE)) return RDP_WS_INVALID_PARAMETER;
    if ((options & RDP_WS_DIRECTORY_FILE) && disposition != RDP_WS_FILE_OPEN && disposition != RDP_WS_FILE_CREATE && disposition != RDP_WS_FILE_OPEN_IF)
        return RDP_WS_INVALID_PARAMETER;
    if (desired_access & (0x00040000U | 0x00080000U | 0x01000000U | 0x02000000U)) return RDP_WS_NOT_SUPPORTED;
    uint32_t access = effective_access(desired_access);
    if (read_only && ((access & (RDP_WS_WRITE | RDP_WS_DELETE)) || disposition != RDP_WS_FILE_OPEN ||
        (options & RDP_WS_DELETE_ON_CLOSE))) return RDP_WS_ACCESS_DENIED;
    if ((options & RDP_WS_DELETE_ON_CLOSE) && !(access & RDP_WS_DELETE)) return RDP_WS_ACCESS_DENIED;
    if (allocation_size > RDP_WS_MAX_FILE_BYTES) return RDP_WS_FILE_TOO_LARGE;
    if (allocation_size) return RDP_WS_NOT_SUPPORTED;
    pthread_mutex_lock(&ws->mutex);
    uint32_t rc = RDP_WS_OK; int parent = -1, fd = -1;
    char name[RDP_WS_MAX_NAME + 1], actual[RDP_WS_MAX_NAME + 1], canonical[RDP_WS_MAX_PATH + 1];
    WorkspaceHandle* target = empty_handle(ws);
    if (!target) { rc = RDP_WS_INSUFFICIENT_RESOURCES; goto out; }
    rc = parent_for(ws, path, &parent, name, canonical);
    if (rc != RDP_WS_OK) goto out;
    struct stat st;
    bool exists = false, created = false;
    if (!name[0]) {
        if (fstat(parent, &st)) { rc = from_errno(errno); goto out; }
        actual[0] = 0; exists = true;
        if ((access & RDP_WS_DELETE) || (options & RDP_WS_DELETE_ON_CLOSE)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    } else {
        rc = find_child(ws, parent, name, actual, &st);
        if (rc == RDP_WS_OK) exists = true;
        else if (rc != RDP_WS_OBJECT_NAME_NOT_FOUND) goto out;
    }
    if (exists && disposition == RDP_WS_FILE_CREATE) { rc = RDP_WS_OBJECT_NAME_COLLISION; goto out; }
    if (!exists && (disposition == RDP_WS_FILE_OPEN || disposition == RDP_WS_FILE_OVERWRITE)) { rc = RDP_WS_OBJECT_NAME_NOT_FOUND; goto out; }
    if (exists) {
        if (S_ISREG(st.st_mode) && !(st.st_mode & S_IWUSR) && (access & (RDP_WS_WRITE | RDP_WS_DELETE))) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        if ((options & RDP_WS_DIRECTORY_FILE) && !S_ISDIR(st.st_mode)) { rc = RDP_WS_NOT_A_DIRECTORY; goto out; }
        if ((options & RDP_WS_NON_DIRECTORY_FILE) && S_ISDIR(st.st_mode)) { rc = RDP_WS_FILE_IS_A_DIRECTORY; goto out; }
        rc = share_for(ws, &st, access, share_access);
        if (rc != RDP_WS_OK) goto out;
        fd = !actual[0] ? duplicate(parent) : openat(parent, actual,
            (S_ISDIR(st.st_mode) ? O_RDONLY | O_DIRECTORY : ((access & RDP_WS_WRITE) ? O_RDWR : O_RDONLY) | ((options & 2U) ? O_SYNC : 0)) | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) { rc = from_errno(errno); goto out; }
        struct stat opened;
        if (fstat(fd, &opened) || opened.st_dev != st.st_dev || opened.st_ino != st.st_ino || regular_or_directory(&opened) != RDP_WS_OK) {
            rc = RDP_WS_ACCESS_DENIED; goto out;
        }
        if (disposition == RDP_WS_FILE_OVERWRITE || disposition == RDP_WS_FILE_OVERWRITE_IF) {
            if (!(desired_access & (2U | UINT32_C(0x40000000) | UINT32_C(0x10000000))) || S_ISDIR(st.st_mode)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
            if (ftruncate(fd, 0)) { rc = from_errno(errno); goto out; }
            if ((options & 2U) && fsync(fd)) { rc = from_errno(errno); ws->used_bytes -= (uint64_t)st.st_size; goto out; }
            ws->used_bytes -= (uint64_t)st.st_size; st.st_size = 0;
            *create_information = 3; /* FILE_OVERWRITTEN */
        } else *create_information = 1; /* FILE_OPENED */
    } else {
        if (read_only || ws->entries >= RDP_WS_MAX_ENTRIES) { rc = read_only ? RDP_WS_ACCESS_DENIED : RDP_WS_DISK_FULL; goto out; }
        rc = space_for(ws, allocation_size);
        if (rc != RDP_WS_OK) goto out;
        if (options & RDP_WS_DIRECTORY_FILE) {
            if (mkdirat(parent, name, 0700)) { rc = from_errno(errno); goto out; }
            /* Account immediately after publication, even if descriptor pressure
             * prevents returning a handle. Never hide a committed empty item. */
            ws->entries++; ws->namespace_epoch++;
#ifdef RDPSYNC_WS_TEST_FAULTS
            if (rdp_ws_test_fail_created_directory_open) { errno = EMFILE; fd = -1; }
            else
#endif
            fd = open_directory(parent, name);
        } else {
            fd = openat(parent, name, O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW | O_CLOEXEC | ((options & 2U) ? O_SYNC : 0), 0600);
            if (fd >= 0) { ws->entries++; ws->namespace_epoch++; }
        }
        if (fd < 0) { rc = from_errno(errno); goto out; }
        created = true; memcpy(actual, name, strlen(name) + 1);
        if (fstat(fd, &st) || regular_or_directory(&st) != RDP_WS_OK) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        *create_information = 2; /* FILE_CREATED */
    }
    if ((options & RDP_WS_DELETE_ON_CLOSE) && (!(access & RDP_WS_DELETE) || (S_ISDIR(st.st_mode) && !directory_empty(fd)))) {
        rc = RDP_WS_ACCESS_DENIED;
        /* A committed FILE_CREATE is not silently rolled back on later failure. */
        goto out;
    }
    if (created && (options & 2U) && fsync(parent)) { rc = from_errno(errno); goto out; }
    memset(target, 0, sizeof(*target));
    target->fd = fd; fd = -1; target->parent_fd = parent; parent = -1;
    target->owner = owner; target->id = ws->next_handle++; target->access = access; target->share = share_access;
    target->granted_access = desired_access; target->create_options = options; target->create_disposition = disposition; target->write_through = (options & 2U) != 0;
    target->append_only = (desired_access & 4U) && !(desired_access & (2U | UINT32_C(0x40000000) | UINT32_C(0x10000000)));
    target->directory = S_ISDIR(st.st_mode); target->read_only = read_only;
    target->dev = st.st_dev; target->ino = st.st_ino;
    target->pending = (options & RDP_WS_DELETE_ON_CLOSE) != 0; target->forced_delete = target->pending;
    memcpy(target->name, actual, strlen(actual) + 1);
    if (canonical[0] && actual[0]) strcat(canonical, "/");
    strcat(canonical, actual); memcpy(target->path, canonical, strlen(canonical) + 1);
    if (target->pending) for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (same_inode(&ws->handles[i], &st)) { ws->handles[i].pending = true; ws->handles[i].forced_delete = true; }
    *handle = target->id; rc = RDP_WS_OK;
out:
    if (fd >= 0) close(fd);
    if (parent >= 0) close(parent);
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_stat(RdpWorkspace* ws, uint64_t owner, uint32_t handle, RdpWorkspaceEntry* entry) {
    if (!ws || !entry) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) { struct stat st; rc = fstat(h->fd, &st) ? from_errno(errno) : regular_or_directory(&st); if (rc == RDP_WS_OK) entry_for(h, &st, entry); }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_read(RdpWorkspace* ws, uint64_t owner, uint32_t handle, uint64_t offset,
                     void* buffer, uint32_t length, uint32_t* actual) {
    if (!ws || !actual || (!buffer && length) || length > RDP_WS_MAX_IO || offset > INT64_MAX || length > INT64_MAX - offset)
        return RDP_WS_INVALID_PARAMETER;
    *actual = 0; pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->directory) rc = RDP_WS_FILE_IS_A_DIRECTORY;
        else if (!(h->access & RDP_WS_READ)) rc = RDP_WS_ACCESS_DENIED;
        else {
            ssize_t count; do { count = pread(h->fd, buffer, length, (off_t)offset); } while (count < 0 && errno == EINTR);
            if (count < 0) rc = from_errno(errno); else { *actual = (uint32_t)count; rc = RDP_WS_OK; }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_write(RdpWorkspace* ws, uint64_t owner, uint32_t handle, uint64_t offset,
                      const void* buffer, uint32_t length, uint32_t* actual) {
    if (!ws || !actual || (!buffer && length) || length > RDP_WS_MAX_IO || offset > RDP_WS_MAX_FILE_BYTES ||
        length > RDP_WS_MAX_FILE_BYTES - offset) return RDP_WS_INVALID_PARAMETER;
    *actual = 0; pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->directory) rc = RDP_WS_FILE_IS_A_DIRECTORY;
        else if (h->read_only || !(h->access & RDP_WS_WRITE)) rc = RDP_WS_ACCESS_DENIED;
        else {
            struct stat before, after;
            if (fstat(h->fd, &before)) rc = from_errno(errno);
            else if (!(before.st_mode & S_IWUSR)) rc = RDP_WS_ACCESS_DENIED;
            else {
                uint64_t end = length ? offset + length : (uint64_t)before.st_size;
                if (h->append_only && offset != (uint64_t)before.st_size) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_ACCESS_DENIED; }
                rc = space_for(ws, end > (uint64_t)before.st_size ? end - (uint64_t)before.st_size : 0);
                if (rc == RDP_WS_OK) {
                    while (*actual < length) {
                        ssize_t count = pwrite(h->fd, (const char*)buffer + *actual, length - *actual, (off_t)(offset + *actual));
                        if (count < 0 && errno == EINTR) continue;
                        if (count <= 0) { rc = count < 0 ? from_errno(errno) : RDP_WS_UNSUCCESSFUL; break; }
                        *actual += (uint32_t)count;
                    }
                    if (fstat(h->fd, &after)) rc = from_errno(errno);
                    else if (after.st_size >= before.st_size) ws->used_bytes += (uint64_t)(after.st_size - before.st_size);
                    else rc = RDP_WS_UNSUCCESSFUL;
                }
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_truncate(RdpWorkspace* ws, uint64_t owner, uint32_t handle, uint64_t size) {
    if (!ws || size > RDP_WS_MAX_FILE_BYTES) return RDP_WS_FILE_TOO_LARGE;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->directory) rc = RDP_WS_FILE_IS_A_DIRECTORY;
        else if (h->read_only || h->append_only || !(h->access & RDP_WS_WRITE)) rc = RDP_WS_ACCESS_DENIED;
        else {
            struct stat st;
            if (fstat(h->fd, &st)) rc = from_errno(errno);
            else if (!(st.st_mode & S_IWUSR)) rc = RDP_WS_ACCESS_DENIED;
            else {
                rc = space_for(ws, size > (uint64_t)st.st_size ? size - (uint64_t)st.st_size : 0);
                if (rc == RDP_WS_OK) {
                    if (ftruncate(h->fd, (off_t)size)) rc = from_errno(errno);
                    else { ws->used_bytes = ws->used_bytes - (uint64_t)st.st_size + size; if (h->write_through && fsync(h->fd)) rc = from_errno(errno); }
                }
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

static uint32_t set_delete(RdpWorkspace* ws, uint64_t owner, uint32_t handle, bool deleting, bool legacy_wire) {
    if (!ws) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        /* A legacy command is not an access grant. Fixed metadata-open only. */
        bool legacy_eligible = legacy_wire && owner != 0 &&
            h->granted_access == 0x00100080U && h->create_options == 0x20U && h->create_disposition == RDP_WS_FILE_OPEN && h->share == 7;
        if (!h->name[0] || h->read_only || (!(h->access & RDP_WS_DELETE) && !legacy_eligible)) rc = RDP_WS_ACCESS_DENIED;
        else if (deleting && h->directory && !directory_empty(h->fd)) rc = RDP_WS_DIRECTORY_NOT_EMPTY;
        else {
            struct stat current;
            rc = fstat(h->fd, &current) ? from_errno(errno) : RDP_WS_OK;
            if (rc == RDP_WS_OK && !h->directory && !(current.st_mode & S_IWUSR)) rc = RDP_WS_ACCESS_DENIED;
            if (rc == RDP_WS_OK && legacy_wire) {
                struct stat named;
                if (!(current.st_mode & S_IWUSR) || !same_inode(h, &current) || regular_or_directory(&current) != RDP_WS_OK ||
                    fstatat(h->parent_fd, h->name, &named, AT_SYMLINK_NOFOLLOW) ||
                    !same_inode(h, &named)) rc = RDP_WS_ACCESS_DENIED;
            }
            for (size_t i = 0; i < RDP_WS_MAX_HANDLES && rc == RDP_WS_OK; i++) {
                WorkspaceHandle* other = &ws->handles[i];
                if (other->id && other != h && other->dev == h->dev && other->ino == h->ino && !(other->share & RDP_WS_DELETE))
                    rc = RDP_WS_SHARING_VIOLATION;
            }
            if (rc == RDP_WS_OK) for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
                WorkspaceHandle* other = &ws->handles[i];
                if (other->id && other->dev == h->dev && other->ino == h->ino) {
                    other->pending = deleting || other->forced_delete;
                    /* Ordinary dispositions/cancellation cannot inherit this exception. */
                    other->legacy_directory_unlink = false;
                }
            }
            if (rc == RDP_WS_OK && legacy_eligible && deleting && h->directory)
                h->legacy_directory_unlink = true;
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_set_delete(RdpWorkspace* ws, uint64_t owner, uint32_t handle, bool deleting) {
    return set_delete(ws, owner, handle, deleting, false);
}
uint32_t rdp_ws_wire_legacy_delete(RdpWorkspace* ws, uint64_t owner, uint32_t handle) {
    if (!owner) return RDP_WS_ACCESS_DENIED;
    return set_delete(ws, owner, handle, true, true);
}

uint32_t rdp_ws_rename(RdpWorkspace* ws, uint64_t owner, uint32_t handle, const char* new_path, bool replace) {
    if (!ws) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    int parent = -1; int parent_copies[RDP_WS_MAX_HANDLES];
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) parent_copies[i] = -1;
    if (!h) goto out;
    if (!h->name[0] || h->read_only || !(h->access & RDP_WS_DELETE) || h->pending) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other->id && other != h && other->dev == h->dev && other->ino == h->ino && !(other->share & RDP_WS_DELETE)) {
            rc = RDP_WS_SHARING_VIOLATION; goto out;
        }
    }
    char name[RDP_WS_MAX_NAME + 1], actual[RDP_WS_MAX_NAME + 1], canonical[RDP_WS_MAX_PATH + 1];
    rc = parent_for(ws, new_path, &parent, name, canonical);
    if (rc != RDP_WS_OK) goto out;
    if (!name[0]) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    if (canonical[0]) strcat(canonical, "/");
    strcat(canonical, name);
    size_t old_length = strlen(h->path);
    if (h->directory) {
        uint32_t depth = 1, extra_depth = 0, count = 0; size_t extra_length = 0;
        for (const char* p = canonical; *p; p++) if (*p == '/') depth++;
        rc = subtree_extent(h->fd, 0, 0, &extra_depth, &extra_length, &count);
        if (rc != RDP_WS_OK) goto out;
        if (depth + extra_depth > RDP_WS_MAX_DEPTH || strlen(canonical) + extra_length > RDP_WS_MAX_PATH) { rc = RDP_WS_OBJECT_NAME_INVALID; goto out; }
    }
    if (h->directory && !strncmp(canonical, h->path, old_length) && canonical[old_length] == '/') {
        rc = RDP_WS_ACCESS_DENIED; goto out;
    }
    /* Renaming an ancestor must not make a descendant handle exceed our path bound. */
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other->id && h->directory && !strncmp(other->path, h->path, old_length) && other->path[old_length] == '/' &&
            (!(other->share & RDP_WS_DELETE) || strlen(canonical) + strlen(other->path + old_length) > RDP_WS_MAX_PATH)) { rc = RDP_WS_SHARING_VIOLATION; goto out; }
    }
    struct stat destination, original;
    if (fstatat(h->parent_fd, h->name, &original, AT_SYMLINK_NOFOLLOW) || !same_inode(h, &original)) {
        rc = RDP_WS_ACCESS_DENIED; goto out;
    }
    if (S_ISREG(original.st_mode) && !(original.st_mode & S_IWUSR)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    rc = find_child(ws, parent, name, actual, &destination);
    bool exists = rc == RDP_WS_OK;
    if (!exists && rc != RDP_WS_OBJECT_NAME_NOT_FOUND) goto out;
    bool same = exists && destination.st_dev == h->dev && destination.st_ino == h->ino;
    if (exists && !same) {
        if (!replace) { rc = RDP_WS_OBJECT_NAME_COLLISION; goto out; }
        if (S_ISREG(destination.st_mode) && !(destination.st_mode & S_IWUSR)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        if (S_ISDIR(destination.st_mode) != h->directory) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (same_inode(&ws->handles[i], &destination)) {
            rc = RDP_WS_SHARING_VIOLATION; goto out;
        }
        if (h->directory) {
            int target = open_directory(parent, actual);
            if (target < 0) { rc = from_errno(errno); goto out; }
            bool empty = directory_empty(target); close(target);
            if (!empty) { rc = RDP_WS_DIRECTORY_NOT_EMPTY; goto out; }
        }
    }
    /* Reserve replacement parent descriptors before namespace mutation. */
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other->id && other->dev == h->dev && other->ino == h->ino) {
            parent_copies[i] = duplicate(parent);
            if (parent_copies[i] < 0) { rc = from_errno(errno); goto out; }
        }
    }
    /* Case-only rename is permitted for this same inode. A distinct target is
     * never replaced unless the remote/local caller explicitly requested it. */
    int moved = (replace || same) ? renameat(h->parent_fd, h->name, parent, exists && !same ? actual : name) :
        exclusive_rename(h->parent_fd, h->name, parent, name);
    if (moved) { rc = from_errno(errno); goto out; }
    const char* new_name = exists && !same ? actual : name;
    if (exists && !same) {
        ws->entries--;
        if (S_ISREG(destination.st_mode)) ws->used_bytes -= (uint64_t)destination.st_size;
        char* last = strrchr(canonical, '/');
        strcpy(last ? last + 1 : canonical, actual);
    }
    char old_path[RDP_WS_MAX_PATH + 1]; strcpy(old_path, h->path);
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (!other->id) continue;
        if (other->dev == h->dev && other->ino == h->ino) {
            close(other->parent_fd); other->parent_fd = parent_copies[i]; parent_copies[i] = -1;
            strcpy(other->name, new_name); strcpy(other->path, canonical);
        } else if (h->directory && !strncmp(other->path, old_path, old_length) && other->path[old_length] == '/') {
            char updated[RDP_WS_MAX_PATH + 1]; strcpy(updated, canonical); strcat(updated, other->path + old_length);
            strcpy(other->path, updated);
        }
    }
    ws->namespace_epoch++; (void)fsync(parent); rc = RDP_WS_OK;
out:
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (parent_copies[i] >= 0) close(parent_copies[i]);
    if (parent >= 0) close(parent);
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_flush(RdpWorkspace* ws, uint64_t owner, uint32_t handle) {
    if (!ws) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) rc = fsync(h->fd) ? from_errno(errno) : RDP_WS_OK;
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_next(RdpWorkspace* ws, uint64_t owner, uint32_t handle, bool restart, RdpWorkspaceEntry* entry) {
    if (!ws || !entry) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (!h) goto out;
    if (!h->directory) { rc = RDP_WS_NOT_A_DIRECTORY; goto out; }
    if (!(h->access & RDP_WS_READ)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    if (restart && h->cursor) { closedir(h->cursor); h->cursor = NULL; }
    if (h->cursor && h->cursor_epoch != ws->namespace_epoch) {
        closedir(h->cursor); h->cursor = NULL; rc = RDP_WS_NOTIFY_ENUM_DIR; goto out;
    }
    if (!h->cursor) {
        uint64_t bytes = 0; uint32_t entries = 0;
        rc = scan_directory(ws, h->fd, 0, &bytes, &entries, false);
        if (rc != RDP_WS_OK) goto out;
        h->cursor = directory_stream(h->fd); h->cursor_count = 0; h->cursor_epoch = ws->namespace_epoch;
        if (!h->cursor) { rc = from_errno(errno); goto out; }
    }
    struct dirent* item; errno = 0;
    while ((item = readdir(h->cursor))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        if (++h->cursor_count > RDP_WS_MAX_ENTRIES) { rc = RDP_WS_INSUFFICIENT_RESOURCES; goto out; }
        struct stat st; char key[RDP_WS_MAX_KEY + 1];
        if (!key_for(ws, item->d_name, key)) { rc = RDP_WS_OBJECT_NAME_INVALID; goto out; }
        if (fstatat(h->fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW)) { rc = from_errno(errno); goto out; }
        rc = regular_or_directory(&st);
        if (rc != RDP_WS_OK) goto out;
        WorkspaceHandle temporary = { 0 }; strcpy(temporary.name, item->d_name);
        temporary.read_only = h->read_only || !(st.st_mode & S_IWUSR); temporary.pending = pending_inode(ws, &st);
        entry_for(&temporary, &st, entry); rc = RDP_WS_OK; goto out;
    }
    rc = errno ? from_errno(errno) : RDP_WS_NO_MORE_FILES;
out:
    pthread_mutex_unlock(&ws->mutex); return rc;
}

static uint32_t close_locked(RdpWorkspace* ws, WorkspaceHandle* h) {
    uint32_t rc = RDP_WS_OK;
    bool last = true;
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other != h && other->id && other->dev == h->dev && other->ino == h->ino) last = false;
    }
    /* Pinned xrdp never closes the original mkdir FileId. Only an accepted
     * exact legacy directory command may unlink on its requesting CLOSE.
     * Retained IDs are detached, not granted access or silently recycled. */
    bool legacy_directory = h->legacy_directory_unlink && h->directory;
    if (h->pending && !h->namespace_removed && (last || legacy_directory) && h->name[0]) {
        struct stat st;
        if (fstatat(h->parent_fd, h->name, &st, AT_SYMLINK_NOFOLLOW) || !same_inode(h, &st)) rc = RDP_WS_ACCESS_DENIED;
        else if (legacy_directory && (h->read_only || !(st.st_mode & S_IWUSR))) rc = RDP_WS_ACCESS_DENIED;
        else if (h->directory && !directory_empty(h->fd)) rc = RDP_WS_DIRECTORY_NOT_EMPTY;
        if (rc == RDP_WS_OK && legacy_directory) {
            struct stat current;
            if (fstat(h->fd, &current) || !same_inode(h, &current) || regular_or_directory(&current) != RDP_WS_OK ||
                !S_ISDIR(current.st_mode) || !(current.st_mode & S_IWUSR)) rc = RDP_WS_ACCESS_DENIED;
            for (size_t i = 0; i < RDP_WS_MAX_HANDLES && rc == RDP_WS_OK; i++) {
                WorkspaceHandle* other = &ws->handles[i];
                if (same_inode(other, &st) && !(other->share & RDP_WS_DELETE)) rc = RDP_WS_SHARING_VIOLATION;
            }
        }
        if (rc == RDP_WS_OK && unlinkat(h->parent_fd, h->name, h->directory ? AT_REMOVEDIR : 0)) rc = from_errno(errno);
        else if (rc == RDP_WS_OK) {
            ws->entries--; if (!h->directory) ws->used_bytes -= (uint64_t)st.st_size;
            ws->namespace_epoch++; (void)fsync(h->parent_fd);
            if (legacy_directory) for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
                WorkspaceHandle* other = &ws->handles[i];
                if (same_inode(other, &st)) {
                    other->namespace_removed = true;
                    other->legacy_directory_unlink = false;
                }
            }
        }
    }
    if (h->cursor) closedir(h->cursor);
    close(h->fd); close(h->parent_fd); memset(h, 0, sizeof(*h));
    return rc;
}

uint32_t rdp_ws_close(RdpWorkspace* ws, uint64_t owner, uint32_t handle) {
    if (!ws) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = find_handle(ws, owner, handle);
    uint32_t rc = h ? close_locked(ws, h) : RDP_WS_INVALID_HANDLE;
    pthread_mutex_unlock(&ws->mutex); return rc;
}
void rdp_ws_close_owner(RdpWorkspace* ws, uint64_t owner) {
    if (!ws) return;
    pthread_mutex_lock(&ws->mutex);
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (ws->handles[i].id && ws->handles[i].owner == owner)
        (void)close_locked(ws, &ws->handles[i]);
    pthread_mutex_unlock(&ws->mutex);
}
void rdp_ws_destroy(RdpWorkspace* ws) {
    if (!ws) return;
    pthread_mutex_lock(&ws->mutex);
    atomic_store(&ws->shutting_down, true);
    while (ws->importing) pthread_cond_wait(&ws->drained, &ws->mutex);
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) if (ws->handles[i].id) (void)close_locked(ws, &ws->handles[i]);
    if (ws->root_fd >= 0) close(ws->root_fd);
    if (ws->pending_fd >= 0) close(ws->pending_fd);
    pthread_mutex_unlock(&ws->mutex); pthread_cond_destroy(&ws->drained); pthread_mutex_destroy(&ws->mutex); free(ws);
}

static uint32_t import_into(RdpWorkspace* ws, uint64_t parent_owner, uint32_t parent_handle,
    const char* parent_path, const char* name, int source_fd, uint64_t size,
    RdpWorkspaceCancelled cancelled, void* cancel_context) {
    if (!ws || (!parent_path && !parent_handle) || !name || size > RDP_WS_MAX_FILE_BYTES) return RDP_WS_INVALID_PARAMETER;
    struct stat source;
    int flags = fcntl(source_fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY || fstat(source_fd, &source) ||
        !S_ISREG(source.st_mode) || source.st_uid != geteuid() || source.st_size < 0 || (uint64_t)source.st_size != size)
        return RDP_WS_ACCESS_DENIED;
    char path[RDP_WS_MAX_PATH + 1], normalized[RDP_WS_MAX_PATH + 1], key[RDP_WS_MAX_KEY + 1];
    pthread_mutex_lock(&ws->mutex);
    uint32_t rc = path_for(ws, parent_path ? parent_path : "", normalized);
    if (rc != RDP_WS_OK || !key_for(ws, name, key)) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_OBJECT_NAME_INVALID; }
    if (strlen(normalized) + strlen(name) + (normalized[0] ? 1 : 0) > RDP_WS_MAX_PATH) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_OBJECT_NAME_INVALID; }
    strcpy(path, normalized); if (path[0]) strcat(path, "/"); strcat(path, name);
    if (ws->importing || atomic_load(&ws->shutting_down)) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_SHARING_VIOLATION; }
    rc = space_for(ws, size);
    if (rc != RDP_WS_OK || ws->entries >= RDP_WS_MAX_ENTRIES) { pthread_mutex_unlock(&ws->mutex); return rc != RDP_WS_OK ? rc : RDP_WS_DISK_FULL; }
    if (cancelled && cancelled(cancel_context)) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_CANCELLED; }
    int selected_parent = -1;
    if (parent_handle) {
        WorkspaceHandle* selection = get_handle(ws, parent_owner, parent_handle);
        if (!selection || !selection->directory || selection->read_only || !(selection->access & RDP_WS_WRITE) || selection->pending) {
            pthread_mutex_unlock(&ws->mutex); return RDP_WS_INVALID_HANDLE;
        }
        uint32_t depth = selection->path[0] ? 1 : 0;
        for (const char* p = selection->path; *p; p++) if (*p == '/') depth++;
        if (depth >= RDP_WS_MAX_DEPTH || strlen(selection->path) + strlen(name) + 1 > RDP_WS_MAX_PATH) {
            pthread_mutex_unlock(&ws->mutex); return RDP_WS_OBJECT_NAME_INVALID;
        }
        selected_parent = duplicate(selection->fd);
        if (selected_parent < 0) { rc = from_errno(errno); pthread_mutex_unlock(&ws->mutex); return rc; }
    }
    char pending_name[64]; int output = -1;
    for (unsigned attempt = 0; attempt < 16 && output < 0; attempt++) {
        snprintf(pending_name, sizeof(pending_name), "import-%ld-%016llx.part", (long)getpid(), (unsigned long long)++ws->next_import);
        output = openat(ws->pending_fd, pending_name, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (output < 0 && errno != EEXIST) break;
    }
    if (output < 0) { rc = from_errno(errno); if (selected_parent >= 0) close(selected_parent); pthread_mutex_unlock(&ws->mutex); return rc; }
    ws->reserved_bytes += size; ws->importing = true;
    pthread_mutex_unlock(&ws->mutex);
    char buffer[64 * 1024]; uint64_t offset = 0;
    while (offset < size) {
        if (atomic_load(&ws->shutting_down) || (cancelled && cancelled(cancel_context))) { rc = RDP_WS_CANCELLED; break; }
        size_t wanted = size - offset < sizeof(buffer) ? (size_t)(size - offset) : sizeof(buffer);
        ssize_t count = pread(source_fd, buffer, wanted, (off_t)offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { rc = count < 0 ? from_errno(errno) : RDP_WS_UNSUCCESSFUL; break; }
        size_t written = 0;
        while (written < (size_t)count) {
            if (atomic_load(&ws->shutting_down) || (cancelled && cancelled(cancel_context))) { rc = RDP_WS_CANCELLED; break; }
            struct statvfs fs;
            if (fstatvfs(output, &fs) || fs.f_bavail < (RDP_WS_RESERVE_BYTES + (uint64_t)count + fs.f_frsize - 1) / (fs.f_frsize ? fs.f_frsize : 1)) { rc = RDP_WS_DISK_FULL; break; }
            ssize_t next = write(output, buffer + written, (size_t)count - written);
            if (next < 0 && errno == EINTR) continue;
            if (next <= 0) { rc = next < 0 ? from_errno(errno) : RDP_WS_UNSUCCESSFUL; break; }
            written += (size_t)next;
        }
        if (rc != RDP_WS_OK) break;
        offset += (uint64_t)count;
    }
    if (rc == RDP_WS_OK && (fstat(source_fd, &source) || (uint64_t)source.st_size != size || fsync(output))) rc = RDP_WS_UNSUCCESSFUL;
    close(output);
    pthread_mutex_lock(&ws->mutex);
    ws->reserved_bytes -= size;
    if (rc == RDP_WS_OK && (atomic_load(&ws->shutting_down) || (cancelled && cancelled(cancel_context)))) rc = RDP_WS_CANCELLED;
    int parent = -1;
    if (rc == RDP_WS_OK) {
        char final_name[RDP_WS_MAX_NAME + 1], actual[RDP_WS_MAX_NAME + 1], canonical[RDP_WS_MAX_PATH + 1]; struct stat existing;
        if (parent_handle) {
            WorkspaceHandle* selection = get_handle(ws, parent_owner, parent_handle);
            struct stat st;
            if (!selection || selection->pending || fstat(selected_parent, &st) || !same_inode(selection, &st)) rc = RDP_WS_CANCELLED;
            else { parent = selected_parent; selected_parent = -1; strcpy(final_name, name); rc = RDP_WS_OK; }
        } else rc = parent_for(ws, path, &parent, final_name, canonical);
        if (rc == RDP_WS_OK) {
            rc = find_child(ws, parent, final_name, actual, &existing);
            if (rc == RDP_WS_OK) rc = RDP_WS_OBJECT_NAME_COLLISION;
            else if (rc == RDP_WS_OBJECT_NAME_NOT_FOUND) {
                rc = space_for(ws, 0);
                if (rc == RDP_WS_OK && ws->entries >= RDP_WS_MAX_ENTRIES) rc = RDP_WS_DISK_FULL;
                if (rc == RDP_WS_OK) {
                    if (exclusive_rename(ws->pending_fd, pending_name, parent, final_name)) rc = from_errno(errno);
                    else { ws->used_bytes += size; ws->entries++; ws->namespace_epoch++; (void)fsync(parent); }
                }
            }
        }
    }
    if (parent >= 0) close(parent);
    if (selected_parent >= 0) close(selected_parent);
    if (rc != RDP_WS_OK) (void)unlinkat(ws->pending_fd, pending_name, 0);
    ws->importing = false; pthread_cond_broadcast(&ws->drained);
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_set_basic(RdpWorkspace* ws, uint64_t owner, uint32_t handle,
                          uint32_t attributes, int64_t access_millis, int64_t write_millis) {
    if (!ws || access_millis < -1 || write_millis < -1) return RDP_WS_INVALID_PARAMETER;
    if (attributes & ~(1U | 0x10U | 0x20U | 0x80U)) return RDP_WS_NOT_SUPPORTED;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->read_only || !(h->granted_access & (0x100U | UINT32_C(0x40000000) | UINT32_C(0x10000000)))) rc = RDP_WS_ACCESS_DENIED;
        else if ((attributes & 0x10U) && !h->directory) rc = RDP_WS_INVALID_PARAMETER;
        else if (h->directory && (attributes & 1U)) rc = RDP_WS_NOT_SUPPORTED;
        else {
            struct stat st;
            rc = fstat(h->fd, &st) ? from_errno(errno) : RDP_WS_OK;
            if (rc == RDP_WS_OK && attributes) {
                mode_t mode = st.st_mode & 0777;
                if (attributes & 1U) mode &= ~S_IWUSR; else mode |= S_IWUSR;
                if (fchmod(h->fd, mode)) rc = from_errno(errno);
            }
            if (rc == RDP_WS_OK && (access_millis != -1 || write_millis != -1)) {
                struct timespec times[2];
                times[0].tv_sec = access_millis == -1 ? 0 : (time_t)(access_millis / 1000);
                times[0].tv_nsec = access_millis == -1 ? UTIME_OMIT : (long)(access_millis % 1000) * 1000000;
                times[1].tv_sec = write_millis == -1 ? 0 : (time_t)(write_millis / 1000);
                times[1].tv_nsec = write_millis == -1 ? UTIME_OMIT : (long)(write_millis % 1000) * 1000000;
                if (futimens(h->fd, times)) rc = from_errno(errno);
            }
            if (rc == RDP_WS_OK && h->write_through && fsync(h->fd)) rc = from_errno(errno);
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_import(RdpWorkspace* ws, const char* parent_path, const char* name,
    int source_fd, uint64_t size, RdpWorkspaceCancelled cancelled, void* cancel_context) {
    return import_into(ws, 0, 0, parent_path, name, source_fd, size, cancelled, cancel_context);
}
uint32_t rdp_ws_import_at(RdpWorkspace* ws, uint64_t owner, uint32_t directory_handle,
    const char* name, int source_fd, uint64_t size, RdpWorkspaceCancelled cancelled, void* cancel_context) {
    if (!directory_handle) return RDP_WS_INVALID_HANDLE;
    return import_into(ws, owner, directory_handle, NULL, name, source_fd, size, cancelled, cancel_context);
}
uint32_t rdp_ws_handle_path_matches(RdpWorkspace* ws, uint64_t owner, uint32_t handle,
                                  const char* path, bool* matches) {
    if (!ws || !matches) return RDP_WS_INVALID_PARAMETER;
    *matches = false; pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        char normalized[RDP_WS_MAX_PATH + 1], actual[RDP_WS_MAX_PATH + 1];
        rc = path_for(ws, path, normalized);
        if (rc == RDP_WS_OK) {
            strcpy(actual, h->path); char* requested = normalized; char* existing = actual;
            *matches = true;
            while (*requested || *existing) {
                char* a = strchr(requested, '/'); char* b = strchr(existing, '/');
                if (a) *a = 0;
                if (b) *b = 0;
                char key_a[RDP_WS_MAX_KEY + 1], key_b[RDP_WS_MAX_KEY + 1];
                if (!key_for(ws, requested, key_a) || !key_for(ws, existing, key_b) || strcmp(key_a, key_b) || (!!a != !!b)) { *matches = false; break; }
                if (!a) break;
                requested = a + 1; existing = b + 1;
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}
/* A Unicode code point, not a UTF-8 byte, is consumed by '?'. All strings have
 * already passed the pure Java UTF-16/NFC policy; malformed input still fails. */
static const char* next_utf8(const char* p) {
    if (!*p) return p;
    unsigned char c = (unsigned char)*p; unsigned extra = 0;
    if (c < 0x80) return p + 1;
    if (c >= 0xC2 && c <= 0xDF) extra = 1;
    else if (c >= 0xE0 && c <= 0xEF) extra = 2;
    else if (c >= 0xF0 && c <= 0xF4) extra = 3;
    else return NULL;
    for (unsigned i = 1; i <= extra; i++) if (!p[i] || ((unsigned char)p[i] & 0xC0) != 0x80) return NULL;
    if ((c == 0xE0 && (unsigned char)p[1] < 0xA0) || (c == 0xED && (unsigned char)p[1] >= 0xA0) ||
        (c == 0xF0 && (unsigned char)p[1] < 0x90) || (c == 0xF4 && (unsigned char)p[1] >= 0x90)) return NULL;
    return p + extra + 1;
}
uint32_t rdp_ws_pattern_matches(RdpWorkspace* ws, const char* pattern_key, const char* name, bool* matches) {
    if (!ws || !pattern_key || !name || !matches || strnlen(pattern_key, RDP_WS_MAX_KEY + 1) > RDP_WS_MAX_KEY)
        return RDP_WS_INVALID_PARAMETER;
    *matches = false;
    pthread_mutex_lock(&ws->mutex);
    char key[RDP_WS_MAX_KEY + 1];
    if (!key_for(ws, name, key)) { pthread_mutex_unlock(&ws->mutex); return RDP_WS_OBJECT_NAME_INVALID; }
    pthread_mutex_unlock(&ws->mutex);
    const char* p = !strcmp(pattern_key, "*.*") ? "*" : pattern_key;
    const char* n = key; const char* star = NULL; const char* retry = NULL;
    unsigned steps = 0;
    while (*n) {
        if (++steps > 65536) return RDP_WS_NOT_SUPPORTED;
        const char* nn = next_utf8(n); if (!nn) return RDP_WS_OBJECT_NAME_INVALID;
        if (*p == '*') { star = ++p; retry = n; continue; }
        if (*p == '?') { p++; n = nn; continue; }
        const char* np = next_utf8(p); if (!np) return RDP_WS_OBJECT_NAME_INVALID;
        if (np != p && np - p == nn - n && !memcmp(p, n, (size_t)(nn - n))) { p = np; n = nn; continue; }
        if (!star) return RDP_WS_OK;
        retry = next_utf8(retry); if (!retry) return RDP_WS_OBJECT_NAME_INVALID;
        n = retry; p = star;
    }
    while (*p == '*') p++;
    *matches = !*p; return RDP_WS_OK;
}

uint32_t rdp_ws_mkdir_at(RdpWorkspace* ws, uint64_t owner, uint32_t directory_handle, const char* name) {
    if (!ws || !name) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, directory_handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (!h->directory) rc = RDP_WS_NOT_A_DIRECTORY;
        else if (h->read_only || !(h->access & RDP_WS_WRITE)) rc = RDP_WS_ACCESS_DENIED;
        else if (h->pending) rc = RDP_WS_DELETE_PENDING;
        else {
            uint32_t depth = h->path[0] ? 1 : 0;
            for (const char* p = h->path; *p; p++) if (*p == '/') depth++;
            char key[RDP_WS_MAX_KEY + 1], actual[RDP_WS_MAX_NAME + 1]; struct stat st;
            if (depth >= RDP_WS_MAX_DEPTH || !key_for(ws, name, key) || strlen(h->path) + strlen(name) + 1 > RDP_WS_MAX_PATH)
                rc = RDP_WS_OBJECT_NAME_INVALID;
            else {
                rc = find_child(ws, h->fd, name, actual, &st);
                if (rc == RDP_WS_OK) rc = RDP_WS_OBJECT_NAME_COLLISION;
                else if (rc == RDP_WS_OBJECT_NAME_NOT_FOUND) {
                    rc = space_for(ws, 0);
                    if (rc == RDP_WS_OK && ws->entries >= RDP_WS_MAX_ENTRIES) rc = RDP_WS_DISK_FULL;
                    if (rc == RDP_WS_OK) {
                        if (mkdirat(h->fd, name, 0700)) rc = from_errno(errno);
                        else { ws->entries++; ws->namespace_epoch++; (void)fsync(h->fd); }
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}
