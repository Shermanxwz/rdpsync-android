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
    const uint64_t max_file = ws ? max_file_bytes_for(ws) : RDP_WS_MAX_FILE_BYTES;
    if (!ws || !actual || (!buffer && length) || length > RDP_WS_MAX_IO || offset > max_file ||
        length > max_file - offset) return RDP_WS_INVALID_PARAMETER;
    *actual = 0; pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->directory) rc = RDP_WS_FILE_IS_A_DIRECTORY;
        else if (h->read_only || !(h->access & RDP_WS_WRITE)) rc = RDP_WS_ACCESS_DENIED;
        else {
            struct stat before, after;
            if (fstat(h->fd, &before)) rc = from_errno(errno);
            else if (!writable_stat(ws, &before)) rc = RDP_WS_ACCESS_DENIED;
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
                    else if (after.st_size >= before.st_size) {
                        if (!ws->direct_storage) ws->used_bytes += (uint64_t)(after.st_size - before.st_size);
                    } else if (!ws->direct_storage) rc = RDP_WS_UNSUCCESSFUL;
                }
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}

uint32_t rdp_ws_truncate(RdpWorkspace* ws, uint64_t owner, uint32_t handle, uint64_t size) {
    if (!ws || size > max_file_bytes_for(ws)) return RDP_WS_FILE_TOO_LARGE;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->directory) rc = RDP_WS_FILE_IS_A_DIRECTORY;
        else if (h->read_only || h->append_only || !(h->access & RDP_WS_WRITE)) rc = RDP_WS_ACCESS_DENIED;
        else {
            struct stat st;
            if (fstat(h->fd, &st)) rc = from_errno(errno);
            else if (!writable_stat(ws, &st)) rc = RDP_WS_ACCESS_DENIED;
            else {
                rc = space_for(ws, size > (uint64_t)st.st_size ? size - (uint64_t)st.st_size : 0);
                if (rc == RDP_WS_OK) {
                    if (ftruncate(h->fd, (off_t)size)) rc = from_errno(errno);
                    else {
                        if (!ws->direct_storage) ws->used_bytes = ws->used_bytes - (uint64_t)st.st_size + size;
                        if (h->write_through && fsync(h->fd)) rc = from_errno(errno);
                    }
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
        bool legacy_eligible = legacy_wire && owner != 0 &&
            h->granted_access == 0x00100080U && h->create_options == 0x20U && h->create_disposition == RDP_WS_FILE_OPEN && h->share == 7;
        if (!h->name[0] || h->read_only || (!(h->access & RDP_WS_DELETE) && !legacy_eligible)) rc = RDP_WS_ACCESS_DENIED;
        else if (deleting && h->directory && !directory_empty(h->fd)) rc = RDP_WS_DIRECTORY_NOT_EMPTY;
        else {
            struct stat current;
            rc = fstat(h->fd, &current) ? from_errno(errno) : RDP_WS_OK;
            if (rc == RDP_WS_OK && !h->directory && !writable_stat(ws, &current)) rc = RDP_WS_ACCESS_DENIED;
            if (rc == RDP_WS_OK && legacy_wire) {
                struct stat named;
                if (!writable_stat(ws, &current) || !same_inode(h, &current) || regular_or_directory_ws(ws, &current) != RDP_WS_OK ||
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
    const uint32_t limit_depth = max_depth_for(ws);
    if (h->directory) {
        uint32_t depth = 1, extra_depth = 0, count = 0; size_t extra_length = 0;
        for (const char* p = canonical; *p; p++) if (*p == '/') depth++;
        rc = subtree_extent(ws, h->fd, 0, 0, &extra_depth, &extra_length, &count);
        if (rc != RDP_WS_OK) goto out;
        if (depth + extra_depth > limit_depth || strlen(canonical) + extra_length > RDP_WS_MAX_PATH) { rc = RDP_WS_OBJECT_NAME_INVALID; goto out; }
    }
    if (h->directory && !strncmp(canonical, h->path, old_length) && canonical[old_length] == '/') {
        rc = RDP_WS_ACCESS_DENIED; goto out;
    }
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other->id && h->directory && !strncmp(other->path, h->path, old_length) && other->path[old_length] == '/' &&
            (!(other->share & RDP_WS_DELETE) || strlen(canonical) + strlen(other->path + old_length) > RDP_WS_MAX_PATH)) { rc = RDP_WS_SHARING_VIOLATION; goto out; }
    }
    struct stat destination, original;
    if (fstatat(h->parent_fd, h->name, &original, AT_SYMLINK_NOFOLLOW) || !same_inode(h, &original)) {
        rc = RDP_WS_ACCESS_DENIED; goto out;
    }
    if (S_ISREG(original.st_mode) && !writable_stat(ws, &original)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
    rc = find_child(ws, parent, name, actual, &destination);
    bool exists = rc == RDP_WS_OK;
    if (!exists && rc != RDP_WS_OBJECT_NAME_NOT_FOUND) goto out;
    bool same = exists && destination.st_dev == h->dev && destination.st_ino == h->ino;
    if (exists && !same) {
        if (!replace) { rc = RDP_WS_OBJECT_NAME_COLLISION; goto out; }
        if (S_ISREG(destination.st_mode) && !writable_stat(ws, &destination)) { rc = RDP_WS_ACCESS_DENIED; goto out; }
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
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; i++) {
        WorkspaceHandle* other = &ws->handles[i];
        if (other->id && other->dev == h->dev && other->ino == h->ino) {
            parent_copies[i] = duplicate(parent);
            if (parent_copies[i] < 0) { rc = from_errno(errno); goto out; }
        }
    }
    int moved = (replace || same) ? renameat(h->parent_fd, h->name, parent, exists && !same ? actual : name) :
        exclusive_rename(ws, h->parent_fd, h->name, parent, name);
    if (moved) { rc = from_errno(errno); goto out; }
    const char* new_name = exists && !same ? actual : name;
    if (exists && !same) {
        if (ws->entries > 0) ws->entries--;
        if (!ws->direct_storage && S_ISREG(destination.st_mode)) ws->used_bytes -= (uint64_t)destination.st_size;
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
        if (!ws->direct_storage) {
            uint64_t bytes = 0; uint32_t entries = 0;
            rc = scan_directory(ws, h->fd, 0, &bytes, &entries, false);
            if (rc != RDP_WS_OK) goto out;
        }
        h->cursor = directory_stream(h->fd); h->cursor_count = 0; h->cursor_epoch = ws->namespace_epoch;
        if (!h->cursor) { rc = from_errno(errno); goto out; }
    }
    const uint32_t limit = max_entries_for(ws);
    struct dirent* item; errno = 0;
    while ((item = readdir(h->cursor))) {
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        if (++h->cursor_count > limit) {
            rc = ws->direct_storage ? RDP_WS_NO_MORE_FILES : RDP_WS_INSUFFICIENT_RESOURCES;
            goto out;
        }
        struct stat st; char key[RDP_WS_MAX_KEY + 1];
        if (!key_for(ws, item->d_name, key)) {
            if (ws->direct_storage) { errno = 0; continue; }
            rc = RDP_WS_OBJECT_NAME_INVALID; goto out;
        }
        if (fstatat(h->fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW)) {
            if (ws->direct_storage) { errno = 0; continue; }
            rc = from_errno(errno); goto out;
        }
        rc = regular_or_directory_ws(ws, &st);
        if (rc != RDP_WS_OK) {
            if (ws->direct_storage) { errno = 0; continue; }
            goto out;
        }
        WorkspaceHandle temporary = { 0 }; strcpy(temporary.name, item->d_name);
        temporary.read_only = h->read_only || !writable_stat(ws, &st);
        temporary.pending = pending_inode(ws, &st);
        entry_for(ws, &temporary, &st, entry); rc = RDP_WS_OK; goto out;
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
    bool legacy_directory = h->legacy_directory_unlink && h->directory;
    if (h->pending && !h->namespace_removed && (last || legacy_directory) && h->name[0]) {
        struct stat st;
        if (fstatat(h->parent_fd, h->name, &st, AT_SYMLINK_NOFOLLOW) || !same_inode(h, &st)) rc = RDP_WS_ACCESS_DENIED;
        else if (legacy_directory && (h->read_only || !writable_stat(ws, &st))) rc = RDP_WS_ACCESS_DENIED;
        else if (h->directory && !directory_empty(h->fd)) rc = RDP_WS_DIRECTORY_NOT_EMPTY;
        if (rc == RDP_WS_OK && legacy_directory) {
            struct stat current;
            if (fstat(h->fd, &current) || !same_inode(h, &current) || regular_or_directory_ws(ws, &current) != RDP_WS_OK ||
                !S_ISDIR(current.st_mode) || !writable_stat(ws, &current)) rc = RDP_WS_ACCESS_DENIED;
            for (size_t i = 0; i < RDP_WS_MAX_HANDLES && rc == RDP_WS_OK; i++) {
                WorkspaceHandle* other = &ws->handles[i];
                if (same_inode(other, &st) && !(other->share & RDP_WS_DELETE)) rc = RDP_WS_SHARING_VIOLATION;
            }
        }
        if (rc == RDP_WS_OK && unlinkat(h->parent_fd, h->name, h->directory ? AT_REMOVEDIR : 0)) rc = from_errno(errno);
        else if (rc == RDP_WS_OK) {
            if (ws->entries > 0) ws->entries--;
            if (!ws->direct_storage && !h->directory) ws->used_bytes -= (uint64_t)st.st_size;
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
    if (!ws || ws->pending_fd < 0 || (!parent_path && !parent_handle) || !name || size > RDP_WS_MAX_FILE_BYTES) return RDP_WS_INVALID_PARAMETER;
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
                    if (exclusive_rename(ws, ws->pending_fd, pending_name, parent, final_name)) rc = from_errno(errno);
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
    if (!ws->direct_storage && (attributes & ~(1U | 0x10U | 0x20U | 0x80U))) return RDP_WS_NOT_SUPPORTED;
    pthread_mutex_lock(&ws->mutex);
    WorkspaceHandle* h = get_handle(ws, owner, handle); uint32_t rc = RDP_WS_INVALID_HANDLE;
    if (h) {
        if (h->read_only || !(h->granted_access & (0x100U | UINT32_C(0x40000000) | UINT32_C(0x10000000)))) rc = RDP_WS_ACCESS_DENIED;
        else if (!ws->direct_storage && (attributes & 0x10U) && !h->directory) rc = RDP_WS_INVALID_PARAMETER;
        else if (!ws->direct_storage && h->directory && (attributes & 1U)) rc = RDP_WS_NOT_SUPPORTED;
        else {
            struct stat st;
            rc = fstat(h->fd, &st) ? from_errno(errno) : RDP_WS_OK;
            if (rc == RDP_WS_OK && attributes && !ws->direct_storage) {
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
                if (futimens(h->fd, times) && !ws->direct_storage) rc = from_errno(errno);
            }
            if (rc == RDP_WS_OK && h->write_through && fsync(h->fd) && !ws->direct_storage) rc = from_errno(errno);
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
            if (depth >= max_depth_for(ws) || !key_for(ws, name, key) || strlen(h->path) + strlen(name) + 1 > RDP_WS_MAX_PATH)
                rc = RDP_WS_OBJECT_NAME_INVALID;
            else {
                rc = find_child(ws, h->fd, name, actual, &st);
                if (rc == RDP_WS_OK) rc = RDP_WS_OBJECT_NAME_COLLISION;
                else if (rc == RDP_WS_OBJECT_NAME_NOT_FOUND) {
                    rc = space_for(ws, 0);
                    if (rc == RDP_WS_OK && !ws->direct_storage && ws->entries >= RDP_WS_MAX_ENTRIES) rc = RDP_WS_DISK_FULL;
                    if (rc == RDP_WS_OK) {
                        if (mkdirat(h->fd, name, ws->direct_storage ? 0770 : 0700)) rc = from_errno(errno);
                        else { ws->entries++; ws->namespace_epoch++; (void)fsync(h->fd); }
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&ws->mutex); return rc;
}
