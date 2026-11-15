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
    bool direct_storage;
    RdpWorkspaceNameKey name_key;
    void* name_context;
    WorkspaceHandle handles[RDP_WS_MAX_HANDLES];
    uint32_t next_handle;
    uint32_t entries;
    uint64_t used_bytes, reserved_bytes, namespace_epoch, next_import;
    bool importing;
};

bool rdp_ws_is_direct_storage(const RdpWorkspace* ws) {
    return ws && ws->direct_storage;
}

static uint32_t max_depth_for(const RdpWorkspace* ws) {
    return ws->direct_storage ? RDP_WS_DIRECT_MAX_DEPTH : RDP_WS_MAX_DEPTH;
}
static uint32_t max_entries_for(const RdpWorkspace* ws) {
    return ws->direct_storage ? RDP_WS_DIRECT_MAX_ENTRIES : RDP_WS_MAX_ENTRIES;
}
static uint64_t max_file_bytes_for(const RdpWorkspace* ws) {
    return ws->direct_storage ? (uint64_t)INT64_MAX : RDP_WS_MAX_FILE_BYTES;
}

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
static uint32_t regular_or_directory_ws(const RdpWorkspace* ws, const struct stat* st) {
    if (!S_ISREG(st->st_mode) && !S_ISDIR(st->st_mode))
        return RDP_WS_ACCESS_DENIED;
    if (!ws->direct_storage) {
        if (st->st_uid != geteuid()) return RDP_WS_ACCESS_DENIED;
        if (S_ISREG(st->st_mode) && st->st_nlink != 1) return RDP_WS_ACCESS_DENIED;
    }
    if (st->st_size < 0 || (S_ISREG(st->st_mode) && (uint64_t)st->st_size > max_file_bytes_for(ws)))
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

static uint32_t path_for(RdpWorkspace* ws, const char* input, char* path) {
    if (!input) return RDP_WS_OBJECT_NAME_INVALID;
    size_t n = strnlen(input, RDP_WS_MAX_PATH + 2);
    if (n > RDP_WS_MAX_PATH) return RDP_WS_OBJECT_NAME_INVALID;
    if (n && (*input == '/' || *input == '\\')) { input++; n--; }
    if (n && (*input == '/' || *input == '\\')) return RDP_WS_OBJECT_NAME_INVALID;
    if (!n) { path[0] = 0; return RDP_WS_OK; }
    memcpy(path, input, n + 1);
    uint32_t depth = 0;
    const uint32_t max_depth = max_depth_for(ws);
    char* component = path;
    for (size_t i = 0; i <= n; i++) {
        if (path[i] == '\\') path[i] = '/';
        if (path[i] == '/' || !path[i]) {
            const char old = path[i]; path[i] = 0;
            char key[RDP_WS_MAX_KEY + 1];
            if (++depth > max_depth || !key_for(ws, component, key)) return RDP_WS_OBJECT_NAME_INVALID;
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
    const uint32_t limit = max_entries_for(ws);
    struct dirent* item;
    errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        if (++count > limit) {
            if (!ws->direct_storage) { result = RDP_WS_OBJECT_NAME_INVALID; break; }
            break;
        }
        char key[RDP_WS_MAX_KEY + 1];
        if (!key_for(ws, item->d_name, key)) {
            if (ws->direct_storage) { errno = 0; continue; }
            result = RDP_WS_OBJECT_NAME_INVALID; break;
        }
        if (!strcmp(target, key)) {
            if (result == RDP_WS_OK) {
                if (ws->direct_storage && !strcmp(item->d_name, requested)) {
                    struct stat exact_st;
                    if (!fstatat(parent, item->d_name, &exact_st, AT_SYMLINK_NOFOLLOW) &&
                        regular_or_directory_ws(ws, &exact_st) == RDP_WS_OK) {
                        *st = exact_st;
                        memcpy(actual, item->d_name, strlen(item->d_name) + 1);
                    }
                    errno = 0; continue;
                }
                if (ws->direct_storage) { errno = 0; continue; }
                result = RDP_WS_OBJECT_NAME_COLLISION; break;
            }
            if (fstatat(parent, item->d_name, st, AT_SYMLINK_NOFOLLOW) < 0) {
                if (ws->direct_storage) { errno = 0; continue; }
                result = from_errno(errno); break;
            }
            uint32_t check = regular_or_directory_ws(ws, st);
            if (check != RDP_WS_OK) {
                if (ws->direct_storage) { errno = 0; continue; }
                result = check; break;
            }
            result = RDP_WS_OK;
            memcpy(actual, item->d_name, strlen(item->d_name) + 1);
            if (ws->direct_storage && !strcmp(item->d_name, requested)) break;
        }
        errno = 0;
    }
    if (!item && errno && result != RDP_WS_OK) result = from_errno(errno);
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
        result = regular_or_directory_ws(ws, &st);
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

static uint32_t subtree_extent(const RdpWorkspace* ws, int fd, uint32_t depth, size_t length,
    uint32_t* max_depth, size_t* max_length, uint32_t* count) {
    DIR* stream = directory_stream(fd);
    if (!stream) return from_errno(errno);
    const uint32_t limit_entries = max_entries_for(ws);
    const uint32_t limit_depth = max_depth_for(ws);
    uint32_t rc = RDP_WS_OK; struct dirent* item; errno = 0;
    while ((item = readdir(stream))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        size_t next_length = length + strlen(item->d_name) + 1;
        if (++*count > limit_entries || depth + 1 > limit_depth || next_length > RDP_WS_MAX_PATH) { rc = RDP_WS_OBJECT_NAME_INVALID; break; }
        if (depth + 1 > *max_depth) *max_depth = depth + 1;
        if (next_length > *max_length) *max_length = next_length;
        struct stat st;
        if (fstatat(fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW)) {
            if (ws->direct_storage) { errno = 0; continue; }
            rc = from_errno(errno); break;
        }
        rc = regular_or_directory_ws(ws, &st);
        if (rc != RDP_WS_OK) {
            if (ws->direct_storage) { rc = RDP_WS_OK; errno = 0; continue; }
            break;
        }
        if (S_ISDIR(st.st_mode)) {
            int child = open_directory(fd, item->d_name);
            if (child < 0) { rc = from_errno(errno); break; }
            rc = subtree_extent(ws, child, depth + 1, next_length, max_depth, max_length, count);
            close(child); if (rc != RDP_WS_OK) break;
        }
        errno = 0;
    }
    if (!item && errno && rc == RDP_WS_OK) rc = from_errno(errno);
    closedir(stream); return rc;
}

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
    struct statvfs fs;
    if (fstatvfs(ws->root_fd, &fs)) return from_errno(errno);
    uint64_t fr = fs.f_frsize ? (uint64_t)fs.f_frsize : (uint64_t)fs.f_bsize;
    if (!fr) fr = 4096;
    uint64_t available = fs.f_bavail > UINT64_MAX / fr ? UINT64_MAX : (uint64_t)fs.f_bavail * fr;
    if (ws->direct_storage) {
        const uint64_t reserve = UINT64_C(16) * 1024 * 1024;
        if (available < reserve || growth > available - reserve) return RDP_WS_DISK_FULL;
        return RDP_WS_OK;
    }
    if (ws->used_bytes > RDP_WS_MAX_BYTES || ws->reserved_bytes > RDP_WS_MAX_BYTES - ws->used_bytes ||
        growth > RDP_WS_MAX_BYTES - ws->used_bytes - ws->reserved_bytes) return RDP_WS_DISK_FULL;
    if (available < RDP_WS_RESERVE_BYTES || growth > available - RDP_WS_RESERVE_BYTES) return RDP_WS_DISK_FULL;
    return RDP_WS_OK;
}
static WorkspaceHandle* find_handle(RdpWorkspace* ws, uint64_t owner, uint32_t id) {
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++)
        if (ws->handles[i].id == id && id && ws->handles[i].owner == owner) return &ws->handles[i];
    return NULL;
}
static WorkspaceHandle* get_handle(RdpWorkspace* ws, uint64_t owner, uint32_t id) {
    WorkspaceHandle* h = find_handle(ws, owner, id);
    return h && !h->namespace_removed ? h : NULL;
}
static WorkspaceHandle* empty_handle(RdpWorkspace* ws) {
    if (!ws->next_handle) return NULL;
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
static bool writable_stat(const RdpWorkspace* ws, const struct stat* st) {
    if (ws->direct_storage) return (st->st_mode & (S_IWUSR | S_IWGRP | S_IWOTH)) != 0;
    return (st->st_mode & S_IWUSR) != 0;
}
static void entry_for(const RdpWorkspace* ws, WorkspaceHandle* h, const struct stat* st, RdpWorkspaceEntry* entry) {
    memset(entry, 0, sizeof(*entry));
    memcpy(entry->name, h->name, strlen(h->name) + 1);
    entry->directory = S_ISDIR(st->st_mode);
    entry->read_only = h->read_only || !writable_stat(ws, st);
    entry->delete_pending = h->pending;
    entry->size = entry->directory ? 0 : (uint64_t)st->st_size;
    entry->inode = (uint64_t)st->st_ino;
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
static int exclusive_rename(const RdpWorkspace* ws, int old_fd, const char* old_name, int new_fd, const char* new_name) {
#ifdef SYS_renameat2
    int rc = (int)syscall(SYS_renameat2, old_fd, old_name, new_fd, new_name, RENAME_NOREPLACE);
    if (rc == 0) return 0;
    if (ws->direct_storage && (errno == ENOSYS || errno == EINVAL || errno == ENOTSUP || errno == EPERM)) {
        return renameat(old_fd, old_name, new_fd, new_name);
    }
    return rc;
#else
    if (ws->direct_storage) return renameat(old_fd, old_name, new_fd, new_name);
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
    ws->direct_storage = false;
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

uint32_t rdp_ws_create_external(const char* storage_root_path, RdpWorkspaceNameKey name_key,
                                void* name_context, RdpWorkspace** result) {
    if (!result || !name_key || !storage_root_path || storage_root_path[0] != '/')
        return RDP_WS_INVALID_PARAMETER;
    *result = NULL;
    int fd = open(storage_root_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return from_errno(errno);
    struct stat root;
    if (fstat(fd, &root) || !S_ISDIR(root.st_mode)) {
        close(fd);
        return RDP_WS_ACCESS_DENIED;
    }
    /* Verify we actually hold Android storage read permission on this directory */
    DIR* probe = directory_stream(fd);
    if (!probe) {
        uint32_t rc = from_errno(errno);
        close(fd);
        return rc;
    }
    closedir(probe);
    RdpWorkspace* ws = calloc(1, sizeof(*ws));
    if (!ws) { close(fd); return RDP_WS_INSUFFICIENT_RESOURCES; }
    ws->root_fd = fd;
    ws->pending_fd = -1;
    ws->direct_storage = true;
    ws->name_key = name_key;
    ws->name_context = name_context;
    ws->next_handle = 1;
    ws->namespace_epoch = 1;
    if (pthread_mutex_init(&ws->mutex, NULL)) { close(fd); free(ws); return RDP_WS_INSUFFICIENT_RESOURCES; }
    if (pthread_cond_init(&ws->drained, NULL)) { pthread_mutex_destroy(&ws->mutex); close(fd); free(ws); return RDP_WS_INSUFFICIENT_RESOURCES; }
    atomic_init(&ws->shutting_down, false);
    *result = ws;
    return RDP_WS_OK;
}

uint32_t rdp_ws_space(RdpWorkspace* ws, RdpWorkspaceSpace* result) {
    if (!ws || !result) return RDP_WS_INVALID_PARAMETER;
    pthread_mutex_lock(&ws->mutex);
    struct statvfs fs; uint32_t rc = RDP_WS_OK;
    if (fstatvfs(ws->root_fd, &fs)) rc = from_errno(errno);
    else if (ws->direct_storage) {
        uint64_t fr = fs.f_frsize ? (uint64_t)fs.f_frsize : (uint64_t)fs.f_bsize;
        if (!fr) fr = 4096;
        uint64_t total = fs.f_blocks > UINT64_MAX / fr ? UINT64_MAX : (uint64_t)fs.f_blocks * fr;
        uint64_t avail = fs.f_bavail > UINT64_MAX / fr ? UINT64_MAX : (uint64_t)fs.f_bavail * fr;
        result->available_bytes = avail;
        result->used_bytes = total > avail ? total - avail : 0;
        result->entries = ws->entries;
    } else {
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
    if (!ws->direct_storage) {
        if (disposition == RDP_WS_FILE_SUPERSEDE) return RDP_WS_NOT_SUPPORTED;
        const uint32_t supported_options = 1U | 2U | 4U | 0x10U | 0x20U | 0x40U | 0x800U | 0x1000U | 0x4000U;
        if (options & ~supported_options) return RDP_WS_NOT_SUPPORTED;
        if ((options & 0x30U) == 0x30U || ((options & 4U) && (options & 0x800U))) return RDP_WS_INVALID_PARAMETER;
        if (desired_access & (0x00040000U | 0x00080000U | 0x01000000U | 0x02000000U)) return RDP_WS_NOT_SUPPORTED;
        if (allocation_size > RDP_WS_MAX_FILE_BYTES) return RDP_WS_FILE_TOO_LARGE;
        if (allocation_size) return RDP_WS_NOT_SUPPORTED;
    } else {
        if (allocation_size > (uint64_t)INT64_MAX) return RDP_WS_FILE_TOO_LARGE;
        /* Normalize Windows MAXIMUM_ALLOWED (0x02000000) for Explorer / Office */
        if (desired_access & UINT32_C(0x02000000)) {
            desired_access = (desired_access & ~UINT32_C(0x02000000)) |
                (read_only ? UINT32_C(0x80000000) : (UINT32_C(0x80000000) | UINT32_C(0x40000000) | 0x10000U));
        }
    }
    if ((options & RDP_WS_DIRECTORY_FILE) && (options & RDP_WS_NON_DIRECTORY_FILE)) return RDP_WS_INVALID_PARAMETER;
    if ((options & RDP_WS_DIRECTORY_FILE) && disposition != RDP_WS_FILE_OPEN && disposition != RDP_WS_FILE_CREATE && disposition != RDP_WS_FILE_OPEN_IF)
        return RDP_WS_INVALID_PARAMETER;
    uint32_t access = effective_access(desired_access);
    if (ws->direct_storage && !access && (desired_access & 0x00120089U)) access |= RDP_WS_READ;
    if (read_only && ((access & (RDP_WS_WRITE | RDP_WS_DELETE)) || disposition != RDP_WS_FILE_OPEN ||
        (options & RDP_WS_DELETE_ON_CLOSE))) return RDP_WS_ACCESS_DENIED;
    if ((options & RDP_WS_DELETE_ON_CLOSE) && !(access & RDP_WS_DELETE)) return RDP_WS_ACCESS_DENIED;
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
        if (S_ISREG(st.st_mode) && !writable_stat(ws, &st) && (access & (RDP_WS_WRITE | RDP_WS_DELETE))) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        if ((options & RDP_WS_DIRECTORY_FILE) && !S_ISDIR(st.st_mode)) { rc = RDP_WS_NOT_A_DIRECTORY; goto out; }
        if ((options & RDP_WS_NON_DIRECTORY_FILE) && S_ISDIR(st.st_mode)) { rc = RDP_WS_FILE_IS_A_DIRECTORY; goto out; }
        rc = share_for(ws, &st, access, share_access);
        if (rc != RDP_WS_OK) goto out;
        fd = !actual[0] ? duplicate(parent) : openat(parent, actual,
            (S_ISDIR(st.st_mode) ? O_RDONLY | O_DIRECTORY : ((access & RDP_WS_WRITE) ? O_RDWR : O_RDONLY) | ((options & 2U) ? O_SYNC : 0)) | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) { rc = from_errno(errno); goto out; }
        struct stat opened;
        if (fstat(fd, &opened) || opened.st_dev != st.st_dev || opened.st_ino != st.st_ino || regular_or_directory_ws(ws, &opened) != RDP_WS_OK) {
            rc = RDP_WS_ACCESS_DENIED; goto out;
        }
        if (disposition == RDP_WS_FILE_SUPERSEDE || disposition == RDP_WS_FILE_OVERWRITE || disposition == RDP_WS_FILE_OVERWRITE_IF) {
            if (!(desired_access & (2U | UINT32_C(0x40000000) | UINT32_C(0x10000000))) || S_ISDIR(st.st_mode)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
            if (ftruncate(fd, 0)) { rc = from_errno(errno); goto out; }
            if ((options & 2U) && fsync(fd)) { rc = from_errno(errno); if (!ws->direct_storage) ws->used_bytes -= (uint64_t)st.st_size; goto out; }
            if (!ws->direct_storage) ws->used_bytes -= (uint64_t)st.st_size;
            st.st_size = 0;
            *create_information = disposition == RDP_WS_FILE_SUPERSEDE ? 0 : 3;
        } else *create_information = 1;
    } else {
        if (read_only || (!ws->direct_storage && ws->entries >= RDP_WS_MAX_ENTRIES)) {
            rc = read_only ? RDP_WS_ACCESS_DENIED : RDP_WS_DISK_FULL; goto out;
        }
        rc = space_for(ws, allocation_size);
        if (rc != RDP_WS_OK) goto out;
        const mode_t dir_mode = ws->direct_storage ? 0770 : 0700;
        const mode_t file_mode = ws->direct_storage ? 0660 : 0600;
        if (options & RDP_WS_DIRECTORY_FILE) {
            if (mkdirat(parent, name, dir_mode)) { rc = from_errno(errno); goto out; }
            ws->entries++; ws->namespace_epoch++;
#ifdef RDPSYNC_WS_TEST_FAULTS
            if (rdp_ws_test_fail_created_directory_open) { errno = EMFILE; fd = -1; }
            else
#endif
            fd = open_directory(parent, name);
        } else {
            fd = openat(parent, name, O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW | O_CLOEXEC | ((options & 2U) ? O_SYNC : 0), file_mode);
            if (fd >= 0) { ws->entries++; ws->namespace_epoch++; }
        }
        if (fd < 0) { rc = from_errno(errno); goto out; }
        created = true; memcpy(actual, name, strlen(name) + 1);
        if (fstat(fd, &st) || regular_or_directory_ws(ws, &st) != RDP_WS_OK) { rc = RDP_WS_ACCESS_DENIED; goto out; }
        *create_information = 2;
    }
    if ((options & RDP_WS_DELETE_ON_CLOSE) && (!(access & RDP_WS_DELETE) || (S_ISDIR(st.st_mode) && !directory_empty(fd)))) {
        rc = RDP_WS_ACCESS_DENIED;
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
    if (h) {
        struct stat st;
        rc = fstat(h->fd, &st) ? from_errno(errno) : regular_or_directory_ws(ws, &st);
        if (rc == RDP_WS_OK) entry_for(ws, h, &st, entry);
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}


#include "rdpsync_workspace_direct.inc"
