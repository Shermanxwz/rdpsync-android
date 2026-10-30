/* Confined RDPDR filesystem device. Stock path-based DRIVE is not built.
 * The RDPDR thread performs bounded broker operations, but NEVER completes an
 * IRP. Only the protocol owner admits a response into the actual TLS queue.
 */
#define _GNU_SOURCE
#include "rdpsync_drive.h"
#include "rdpsync_context.h"
#include <freerdp/addin.h>
#include <freerdp/client/channels.h>
#include <freerdp/channels/rdpdr.h>
#include <freerdp/channels/channels.h>
#include "rdpsync_utf.h"
#include <winpr/nt.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <time.h>

#define DRIVE_CONTROL_BYTES (64U * 1024U)
#define DRIVE_ERROR_RESERVE (1024U * 1024U)
#define DRIVE_PREFIX 4096U
#define DRIVE_MAX_UTF16 2048U
#define DRIVE_FILETIME_EPOCH UINT64_C(116444736000000000)

typedef struct {
    int reserved;
    uint32_t completion;
    uint64_t epoch;
    size_t input_bytes;
    size_t response_bytes;
    int64_t admitted_seconds;
    IRP* irp;
} DriveSlot;
typedef struct {
    uint32_t id;
    char pattern[RDP_WS_MAX_KEY + 1];
    int enumerating;
} DriveHandle;
typedef struct { DEVICE device; RdpDrive* owner; } DriveDevice;
struct RdpDrive {
    pthread_mutex_t mutex;
    RdpWorkspace* workspace;
    RdpWorkspaceNameKey pattern_key;
    void* pattern_context;
    void (*wake)(void*);
    void* wake_context;
    uint64_t owner;
    int mode, state, revoked;
    uint32_t device_id, reply_status;
    int server_drive_capability;
    uint64_t announced_epoch, initialized_epoch, ready_epoch;
    DriveDevice* device;
    rdpContext* context;
    DriveSlot slots[RDPSYNC_DRIVE_PENDING];
    DriveHandle handles[RDP_WS_MAX_HANDLES];
    size_t pending_count, pending_bytes, pending_response_bytes, peak_response_bytes;
    uint64_t read_bytes, written_bytes, completed;
    uint64_t irp_observed;
    struct { uint32_t major, minor, file_id, status, arg0, arg1; } irp_trace[16];
    int64_t transport_pending, peak_transport_pending;
    size_t control_remaining, error_remaining;
    struct {
        int active, slot;
        size_t total, received, prefix_length;
        BYTE prefix[DRIVE_PREFIX];
    } ingress;
};

static uint16_t get16(const BYTE* p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t get32(const BYTE* p) { return (uint32_t)get16(p) | ((uint32_t)get16(p + 2) << 16); }
static uint64_t get64(const BYTE* p) { return (uint64_t)get32(p) | ((uint64_t)get32(p + 4) << 32); }
static int64_t drive_seconds(void) { struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now); return now.tv_sec; }
static uint64_t filetime(int64_t millis) {
    if (millis < 0 || (uint64_t)millis > (UINT64_MAX - DRIVE_FILETIME_EPOCH) / 10000) return 0;
    return DRIVE_FILETIME_EPOCH + (uint64_t)millis * 10000;
}
static void write_file_times(wStream* output, const RdpWorkspaceEntry* entry) {
    /* Android/POSIX stat has no portable birth time. Zero is explicitly
     * unknown, not a relabelled change/modified timestamp. */
    Stream_Write_UINT64(output, 0);
    Stream_Write_UINT64(output, filetime(entry->access_millis));
    Stream_Write_UINT64(output, filetime(entry->modified_millis));
    Stream_Write_UINT64(output, filetime(entry->change_millis));
}
static uint32_t attributes(const RdpWorkspaceEntry* e) {
    return (e->directory ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_ARCHIVE) |
        (e->read_only ? FILE_ATTRIBUTE_READONLY : 0);
}
static DriveSlot* find_slot(RdpDrive* d, uint32_t completion) {
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i)
        if (d->slots[i].reserved && d->slots[i].completion == completion) return &d->slots[i];
    return NULL;
}
static void release_slot(RdpDrive* d, DriveSlot* slot) {
    if (!slot->reserved) return;
    d->pending_bytes -= slot->input_bytes;
    d->pending_response_bytes -= slot->response_bytes;
    --d->pending_count;
    memset(slot, 0, sizeof(*slot));
}
static DriveHandle* find_handle(RdpDrive* d, uint32_t id) {
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; ++i)
        if (d->handles[i].id == id && id) return &d->handles[i];
    return NULL;
}

/* Strict UTF-16 wire decoding. Only one leading separator and one optional
 * final NUL are accepted. No alternate streams, malformed surrogates, '..',
 * empty components, slash aliases, or non-final wildcard components. */
static uint32_t wire_path(const BYTE* p, size_t bytes, int wildcard, char output[RDP_WS_MAX_PATH + 1]) {
    if (bytes % 2 || bytes / 2 > DRIVE_MAX_UTF16) return STATUS_OBJECT_NAME_INVALID;
    size_t units = bytes / 2;
    if (units && get16(p + (units - 1) * 2) == 0) --units;
    WCHAR wide[DRIVE_MAX_UTF16 + 1];
    size_t begin = 0, count = 0;
    if (units && get16(p) == '\\') begin = 1;
    for (size_t i = begin; i < units; ++i) {
        uint16_t ch = get16(p + i * 2);
        if (!ch || ch < 32 || ch == '/' || ch == ':' || ch == '|' || ch == '<' || ch == '>' || ch == '"')
            return STATUS_OBJECT_NAME_INVALID;
        if (ch >= 0xd800 && ch <= 0xdbff) {
            if (i + 1 >= units) return STATUS_OBJECT_NAME_INVALID;
            uint16_t low = get16(p + (i + 1) * 2);
            if (low < 0xdc00 || low > 0xdfff) return STATUS_OBJECT_NAME_INVALID;
            wide[count++] = ch; wide[count++] = low; ++i; continue;
        }
        if (ch >= 0xdc00 && ch <= 0xdfff) return STATUS_OBJECT_NAME_INVALID;
        if (!wildcard && (ch == '*' || ch == '?')) return STATUS_OBJECT_NAME_INVALID;
        wide[count++] = ch == '\\' ? '/' : ch;
    }
    wide[count] = 0;
    if (!count) { output[0] = 0; return STATUS_SUCCESS; }
    size_t utf8_len = 0;
    char* utf8 = rdpsync_utf16_to_utf8(wide, count, &utf8_len);
    if (!utf8) return STATUS_INSUFFICIENT_RESOURCES;
    uint32_t result = STATUS_OBJECT_NAME_INVALID;
    if (utf8_len > RDP_WS_MAX_PATH) goto out;
    size_t start = 0, depth = 0;
    for (size_t i = 0; i <= utf8_len; ++i) {
        if (i < utf8_len && utf8[i] != '/') continue;
        const size_t length = i - start;
        if (!length || length > RDP_WS_MAX_NAME || ++depth > RDP_WS_MAX_DEPTH ||
            (length == 1 && utf8[start] == '.') ||
            (length == 2 && utf8[start] == '.' && utf8[start + 1] == '.') ||
            utf8[i - 1] == '.' || utf8[i - 1] == ' ') goto out;
        if (i < utf8_len && (memchr(utf8 + start, '*', length) || memchr(utf8 + start, '?', length))) goto out;
        start = i + 1;
    }
    memcpy(output, utf8, utf8_len); output[utf8_len] = 0;
    result = STATUS_SUCCESS;
out:
    free(utf8); return result;
}

static int output_room(IRP* irp, size_t length) {
    return length <= RDP_WS_MAX_IO + 2048U && Stream_EnsureRemainingCapacity(irp->output, length);
}
static int input_bytes(IRP* irp, size_t length, const BYTE** data) {
    if (Stream_GetRemainingLength(irp->input) < length) return 0;
    *data = Stream_ConstPointer(irp->input);
    return 1;
}
static void empty_response(IRP* irp) {
    /* Every response retains its common header. Never leak a cancelled payload. */
    (void)Stream_SetPosition(irp->output, RDPDR_DEVICE_IO_RESPONSE_LENGTH);
    if (!output_room(irp, 5)) return;
    Stream_Write_UINT32(irp->output, 0);
    if (irp->MajorFunction == IRP_MJ_CREATE || irp->MajorFunction == IRP_MJ_WRITE ||
        (irp->MajorFunction == IRP_MJ_DIRECTORY_CONTROL)) Stream_Write_UINT8(irp->output, 0);
}
static uint32_t process_create(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 32, &p)) return STATUS_INVALID_PARAMETER;
    if (get32(p + 12) & ~(FILE_ATTRIBUTE_NORMAL | FILE_ATTRIBUTE_ARCHIVE | FILE_ATTRIBUTE_DIRECTORY)) return STATUS_NOT_SUPPORTED;
    const uint32_t length = get32(p + 28);
    if (length > Stream_GetRemainingLength(irp->input) - 32) return STATUS_INVALID_PARAMETER;
    char path[RDP_WS_MAX_PATH + 1];
    uint32_t status = wire_path(p + 32, length, 0, path);
    if (status) return status;
    DriveHandle* holder = NULL;
    for (size_t i = 0; i < RDP_WS_MAX_HANDLES; ++i) if (!d->handles[i].id) { holder = &d->handles[i]; break; }
    if (!holder) return STATUS_INSUFFICIENT_RESOURCES;
    uint32_t handle = 0, information = 0;
    status = rdp_ws_open(d->workspace, d->owner, path, get32(p), get32(p + 16),
        get32(p + 20), get32(p + 24), d->mode == 1, get64(p + 4), &handle, &information);
    if (status) return status;
    if (!handle || !output_room(irp, 5)) {
        if (handle) (void)rdp_ws_close(d->workspace, d->owner, handle);
        return STATUS_INSUFFICIENT_RESOURCES;
    }
    holder->id = handle;
    Stream_Write_UINT32(irp->output, handle);
    Stream_Write_UINT8(irp->output, information);
    return STATUS_SUCCESS;
}
static uint32_t process_read(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 32, &p)) return STATUS_INVALID_PARAMETER;
    const uint32_t length = get32(p);
    if (length > RDP_WS_MAX_IO || get64(p + 4) > INT64_MAX) return STATUS_INVALID_PARAMETER;
    if (!output_room(irp, (size_t)length + 4)) return STATUS_INSUFFICIENT_RESOURCES;
    const size_t position = Stream_GetPosition(irp->output);
    Stream_Seek_UINT32(irp->output);
    uint32_t actual = 0;
    uint32_t status = rdp_ws_read(d->workspace, d->owner, irp->FileId, get64(p + 4),
        Stream_Pointer(irp->output), length, &actual);
    if (status) { (void)Stream_SetPosition(irp->output, position); return status; }
    if (actual > length) return STATUS_INTERNAL_ERROR;
    (void)Stream_SetPosition(irp->output, position);
    Stream_Write_UINT32(irp->output, actual); Stream_Seek(irp->output, actual);
    d->read_bytes += actual;
    return STATUS_SUCCESS;
}
static uint32_t process_write(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 32, &p)) return STATUS_INVALID_PARAMETER;
    const uint32_t length = get32(p);
    if (length > RDP_WS_MAX_IO || length > Stream_GetRemainingLength(irp->input) - 32 ||
        get64(p + 4) > INT64_MAX) return STATUS_INVALID_PARAMETER;
    uint32_t actual = 0;
    uint32_t status = rdp_ws_write(d->workspace, d->owner, irp->FileId, get64(p + 4), p + 32, length, &actual);
    if (status) return status;
    if (actual > length || !output_room(irp, 5)) return STATUS_INTERNAL_ERROR;
    Stream_Write_UINT32(irp->output, actual); Stream_Write_UINT8(irp->output, 0);
    d->written_bytes += actual;
    return STATUS_SUCCESS;
}
static uint32_t process_query(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 4, &p)) return STATUS_INVALID_PARAMETER;
    RdpWorkspaceEntry e;
    uint32_t status = rdp_ws_stat(d->workspace, d->owner, irp->FileId, &e);
    if (status) return status;
    if (!output_room(irp, 48)) return STATUS_INSUFFICIENT_RESOURCES;
    switch (get32(p)) {
        case FileBasicInformation:
            Stream_Write_UINT32(irp->output, 36);
            write_file_times(irp->output, &e);
            Stream_Write_UINT32(irp->output, attributes(&e) | (d->mode == 1 ? FILE_ATTRIBUTE_READONLY : 0));
            break;
        case FileStandardInformation:
            Stream_Write_UINT32(irp->output, 22);
            Stream_Write_UINT64(irp->output, e.size); Stream_Write_UINT64(irp->output, e.size);
            Stream_Write_UINT32(irp->output, 1); Stream_Write_UINT8(irp->output, e.delete_pending);
            Stream_Write_UINT8(irp->output, e.directory); break;
        case FileAttributeTagInformation:
            Stream_Write_UINT32(irp->output, 8);
            Stream_Write_UINT32(irp->output, attributes(&e) | (d->mode == 1 ? FILE_ATTRIBUTE_READONLY : 0));
            Stream_Write_UINT32(irp->output, 0); break;
        default: return STATUS_NOT_SUPPORTED;
    }
    return STATUS_SUCCESS;
}
static uint32_t process_set(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 32, &p)) return STATUS_INVALID_PARAMETER;
    const uint32_t type = get32(p), length = get32(p + 4);
    if (length > Stream_GetRemainingLength(irp->input) - 32) return STATUS_INVALID_PARAMETER;
    const BYTE* data = p + 32;
    uint32_t status = STATUS_NOT_SUPPORTED;
    switch (type) {
        case FileEndOfFileInformation:
        case FileAllocationInformation:
            if (length != 8) return STATUS_INVALID_PARAMETER;
            /* Shrinking allocation truncates EOF by NT semantics. Growing
             * allocation requires durable reservation accounting we do not
             * implement; never return fake successful preallocation. */
            if (type == FileAllocationInformation) {
                RdpWorkspaceEntry e;
                status = rdp_ws_stat(d->workspace, d->owner, irp->FileId, &e);
                if (!status && get64(data) < e.size) status = rdp_ws_truncate(d->workspace, d->owner, irp->FileId, get64(data));
                else if (!status && get64(data) > e.size) status = STATUS_NOT_SUPPORTED;
            } else status = rdp_ws_truncate(d->workspace, d->owner, irp->FileId, get64(data));
            break;
        case FileBasicInformation: {
            if (length != 36) return STATUS_INVALID_PARAMETER;
            /* This filesystem has no settable birth/change time; do not claim
             * to have persisted NT metadata that Android cannot represent. */
            if ((get64(data) && get64(data) != UINT64_MAX) ||
                (get64(data + 24) && get64(data + 24) != UINT64_MAX)) return STATUS_NOT_SUPPORTED;
            const uint64_t at = get64(data + 8), mt = get64(data + 16);
            if ((at && at != UINT64_MAX && at < DRIVE_FILETIME_EPOCH) ||
                (mt && mt != UINT64_MAX && mt < DRIVE_FILETIME_EPOCH)) return STATUS_INVALID_PARAMETER;
            const int64_t access = !at || at == UINT64_MAX ? -1 : (int64_t)((at - DRIVE_FILETIME_EPOCH) / 10000);
            const int64_t modified = !mt || mt == UINT64_MAX ? -1 : (int64_t)((mt - DRIVE_FILETIME_EPOCH) / 10000);
            status = rdp_ws_set_basic(d->workspace, d->owner, irp->FileId, get32(data + 32), access, modified);
            break;
        }
        case FileDispositionInformation:
            if (length != 1 || data[0] > 1) return STATUS_INVALID_PARAMETER;
            status = rdp_ws_set_delete(d->workspace, d->owner, irp->FileId, data[0] != 0); break;
        case FileRenameInformation: {
            if (length < 6 || data[0] > 1 || data[1] != 0) return STATUS_INVALID_PARAMETER;
            const uint32_t path_length = get32(data + 2);
            if (path_length != length - 6) return STATUS_INVALID_PARAMETER;
            char path[RDP_WS_MAX_PATH + 1];
            status = wire_path(data + 6, path_length, 0, path);
            if (!status) status = rdp_ws_rename(d->workspace, d->owner, irp->FileId, path, data[0] != 0);
            break;
        }
        default: break;
    }
    if (!status) { if (!output_room(irp, 4)) return STATUS_INSUFFICIENT_RESOURCES; Stream_Write_UINT32(irp->output, length); }
    return status;
}

static uint32_t process_volume(RdpDrive* d, IRP* irp) {
    const BYTE* p;
    if (!input_bytes(irp, 4, &p)) return STATUS_INVALID_PARAMETER;
    if (!output_room(irp, 128)) return STATUS_INSUFFICIENT_RESOURCES;
    RdpWorkspaceSpace space;
    uint32_t status = rdp_ws_space(d->workspace, &space);
    if (status) return status;
    const uint64_t total = RDP_WS_MAX_BYTES / 4096, available = space.available_bytes / 4096;
    switch (get32(p)) {
        case FileFsVolumeInformation: {
            const WCHAR label[] = {'R','d','p','S','y','n','c'};
            Stream_Write_UINT32(irp->output, 17 + sizeof(label));
            Stream_Write_UINT64(irp->output, 0); Stream_Write_UINT32(irp->output, 0x52535043);
            Stream_Write_UINT32(irp->output, sizeof(label)); Stream_Write_UINT8(irp->output, 0);
            Stream_Write(irp->output, label, sizeof(label)); break;
        }
        case FileFsSizeInformation:
        case FileFsFullSizeInformation:
            Stream_Write_UINT32(irp->output, get32(p) == FileFsSizeInformation ? 24 : 32);
            Stream_Write_UINT64(irp->output, total); Stream_Write_UINT64(irp->output, available);
            if (get32(p) == FileFsFullSizeInformation) Stream_Write_UINT64(irp->output, available);
            Stream_Write_UINT32(irp->output, 8); Stream_Write_UINT32(irp->output, 512); break;
        case FileFsDeviceInformation:
            Stream_Write_UINT32(irp->output, 8); Stream_Write_UINT32(irp->output, FILE_DEVICE_DISK);
            Stream_Write_UINT32(irp->output, FILE_REMOTE_DEVICE | (d->mode == 1 ? FILE_READ_ONLY_DEVICE : 0)); break;
        case FileFsAttributeInformation: {
            const WCHAR type[] = {'R','d','p','S','y','n','c'};
            Stream_Write_UINT32(irp->output, 12 + sizeof(type));
            Stream_Write_UINT32(irp->output, FILE_CASE_PRESERVED_NAMES | FILE_UNICODE_ON_DISK |
                (d->mode == 1 ? FILE_READ_ONLY_VOLUME : 0));
            Stream_Write_UINT32(irp->output, RDP_WS_MAX_NAME);
            Stream_Write_UINT32(irp->output, sizeof(type)); Stream_Write(irp->output, type, sizeof(type)); break;
        }
        default: return STATUS_NOT_SUPPORTED;
    }
    return STATUS_SUCCESS;
}
static uint32_t process_directory(RdpDrive* d, IRP* irp) {
    if (irp->MinorFunction != IRP_MN_QUERY_DIRECTORY) return STATUS_NOT_SUPPORTED;
    const BYTE* p;
    if (!input_bytes(irp, 32, &p)) return STATUS_INVALID_PARAMETER;
    uint32_t type = get32(p), path_length = get32(p + 5);
    if (p[4] > 1 || path_length > Stream_GetRemainingLength(irp->input) - 32) return STATUS_INVALID_PARAMETER;
    if (type != FileDirectoryInformation && type != FileFullDirectoryInformation &&
        type != FileBothDirectoryInformation && type != FileNamesInformation) return STATUS_NOT_SUPPORTED;
    DriveHandle* h = find_handle(d, irp->FileId);
    if (!h) return STATUS_INVALID_HANDLE;
    if (p[4]) {
        char path[RDP_WS_MAX_PATH + 1];
        uint32_t status = wire_path(p + 32, path_length, 1, path);
        if (status) return status;
        char* slash = strrchr(path, '/');
        const char* pattern = slash ? slash + 1 : path;
        if (slash || (path_length > 2 && get16(p + 32) == '\\')) {
            if (slash) *slash = 0;
            bool matches = false;
            status = rdp_ws_handle_path_matches(d->workspace, d->owner, irp->FileId, slash ? path : "", &matches);
            if (status) return status;
            if (!matches) return STATUS_OBJECT_PATH_NOT_FOUND;
        }
        if (!*pattern || strlen(pattern) > RDP_WS_MAX_NAME) return STATUS_OBJECT_NAME_INVALID;
        if (!d->pattern_key(d->pattern_context, pattern, h->pattern, sizeof(h->pattern))) return STATUS_OBJECT_NAME_INVALID;
        h->enumerating = 1;
    } else if (!h->enumerating) return STATUS_INVALID_PARAMETER;
    RdpWorkspaceEntry e;
    uint32_t status;
    for (size_t tries = 0; tries <= RDP_WS_MAX_ENTRIES; ++tries) {
        status = rdp_ws_next(d->workspace, d->owner, irp->FileId, p[4] && tries == 0, &e);
        if (status) return status;
        bool matches = false;
        status = rdp_ws_pattern_matches(d->workspace, h->pattern, e.name, &matches);
        if (status) return status;
        if (matches) break;
        if (tries == RDP_WS_MAX_ENTRIES) return STATUS_INSUFFICIENT_RESOURCES;
    }
    size_t units = 0;
    WCHAR* name = rdpsync_utf8_to_utf16(e.name, strlen(e.name), &units);
    if (!name || units > RDP_WS_MAX_NAME) { free(name); return STATUS_OBJECT_NAME_INVALID; }
    size_t base = type == FileNamesInformation ? 12 : type == FileBothDirectoryInformation ? 93 : type == FileFullDirectoryInformation ? 68 : 64;
    const size_t bytes = units * 2;
    if (!output_room(irp, 4 + base + bytes)) { free(name); return STATUS_INSUFFICIENT_RESOURCES; }
    Stream_Write_UINT32(irp->output, base + bytes);
    Stream_Write_UINT32(irp->output, 0); Stream_Write_UINT32(irp->output, 0);
    if (type != FileNamesInformation) {
        write_file_times(irp->output, &e);
        Stream_Write_UINT64(irp->output, e.size); Stream_Write_UINT64(irp->output, e.size);
        Stream_Write_UINT32(irp->output, attributes(&e) | (d->mode == 1 ? FILE_ATTRIBUTE_READONLY : 0));
    }
    Stream_Write_UINT32(irp->output, bytes);
    if (type == FileFullDirectoryInformation || type == FileBothDirectoryInformation) Stream_Write_UINT32(irp->output, 0);
    if (type == FileBothDirectoryInformation) { Stream_Write_UINT8(irp->output, 0); Stream_Zero(irp->output, 24); }
    Stream_Write(irp->output, name, bytes); free(name);
    return STATUS_SUCCESS;
}

static uint32_t process_irp(RdpDrive* d, IRP* irp) {
    if (d->revoked || d->state < 0) return STATUS_DEVICE_NOT_READY;
    if (d->state < 3) return STATUS_DEVICE_NOT_READY;
    if (irp->MajorFunction != IRP_MJ_CREATE && irp->MajorFunction != IRP_MJ_QUERY_VOLUME_INFORMATION &&
        !find_handle(d, irp->FileId)) return STATUS_INVALID_HANDLE;
    if (d->mode == 1 && (irp->MajorFunction == IRP_MJ_WRITE || irp->MajorFunction == IRP_MJ_SET_INFORMATION ||
        irp->MajorFunction == IRP_MJ_SET_VOLUME_INFORMATION)) return STATUS_MEDIA_WRITE_PROTECTED;
    switch (irp->MajorFunction) {
        case IRP_MJ_CREATE: return process_create(d, irp);
        case IRP_MJ_READ: return process_read(d, irp);
        case IRP_MJ_WRITE: return process_write(d, irp);
        case IRP_MJ_QUERY_INFORMATION: return process_query(d, irp);
        case IRP_MJ_QUERY_VOLUME_INFORMATION: return process_volume(d, irp);
        case IRP_MJ_SET_INFORMATION: return process_set(d, irp);
        case IRP_MJ_DIRECTORY_CONTROL: return process_directory(d, irp);
        case IRP_MJ_CLOSE: {
            uint32_t status = rdp_ws_close(d->workspace, d->owner, irp->FileId);
            DriveHandle* h = find_handle(d, irp->FileId); if (h) memset(h, 0, sizeof(*h));
            if (!status) Stream_Write_UINT32(irp->output, 0);
            return status;
        }
        case 0x07: /* IRP_MJ_FLUSH_BUFFERS */
            return rdp_ws_flush(d->workspace, d->owner, irp->FileId);
        default: return STATUS_NOT_SUPPORTED;
    }
}

static UINT drive_irp(DEVICE* device, IRP* irp) {
    RdpDrive* d = ((DriveDevice*)device)->owner;
    pthread_mutex_lock(&d->mutex);
    DriveSlot* slot = find_slot(d, irp->CompletionId);
    if (!slot || slot->irp || d->device != (DriveDevice*)device) {
        pthread_mutex_unlock(&d->mutex); irp->Discard(irp); return ERROR_INVALID_DATA;
    }
    if (slot->epoch != d->initialized_epoch) {
        pthread_mutex_unlock(&d->mutex); irp->Discard(irp); return ERROR_INVALID_STATE;
    }
    /* This callback is on the same upstream worker after process_init clears
     * its private clearing flag. Caps/reply observed by the owner are NOT such
     * a barrier: they may still be queued ahead of that worker. */
    d->ready_epoch = slot->epoch;
    irp->IoStatus = process_irp(d, irp);
    const size_t trace_index = (size_t)(d->irp_observed++ % 16);
    d->irp_trace[trace_index].major = irp->MajorFunction;
    d->irp_trace[trace_index].minor = irp->MinorFunction;
    d->irp_trace[trace_index].file_id = irp->FileId;
    d->irp_trace[trace_index].status = irp->IoStatus;
    const BYTE* trace_input = Stream_ConstPointer(irp->input);
    const size_t trace_remaining = Stream_GetRemainingLength(irp->input);
    const bool metadata_arg = irp->MajorFunction == IRP_MJ_CREATE ||
        irp->MajorFunction == IRP_MJ_READ || irp->MajorFunction == IRP_MJ_WRITE ||
        irp->MajorFunction == IRP_MJ_QUERY_INFORMATION || irp->MajorFunction == IRP_MJ_QUERY_VOLUME_INFORMATION ||
        irp->MajorFunction == IRP_MJ_SET_INFORMATION ||
        (irp->MajorFunction == IRP_MJ_DIRECTORY_CONTROL && irp->MinorFunction == IRP_MN_QUERY_DIRECTORY);
    d->irp_trace[trace_index].arg0 = metadata_arg && trace_remaining >= 4 ? get32(trace_input) : 0;
    d->irp_trace[trace_index].arg1 = irp->MajorFunction == IRP_MJ_CREATE && trace_remaining >= 28 ? get32(trace_input + 24) : 0;
    if (irp->IoStatus != STATUS_SUCCESS) empty_response(irp);
    else if (!d->revoked && d->state == 3) d->state = 4;
    slot->irp = irp;
    if (d->wake) d->wake(d->wake_context);
    pthread_mutex_unlock(&d->mutex);
    return CHANNEL_RC_OK;
}
static void discard_slots(RdpDrive* d) {
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) {
        IRP* irp = d->slots[i].irp;
        release_slot(d, &d->slots[i]);
        if (irp) irp->Discard(irp);
    }
}
static UINT drive_init(DEVICE* device) {
    RdpDrive* d = ((DriveDevice*)device)->owner;
    pthread_mutex_lock(&d->mutex);
    ++d->initialized_epoch;
    /* Owner ingress may already have reserved requests after this announce.
     * Preserve those future requests, discarding only the previous epoch. */
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) {
        DriveSlot* slot = &d->slots[i];
        if (slot->reserved && slot->epoch < d->initialized_epoch) {
            IRP* irp = slot->irp; release_slot(d, slot);
            if (irp) irp->Discard(irp);
        }
    }
    rdp_ws_close_owner(d->workspace, d->owner);
    memset(d->handles, 0, sizeof(d->handles));
    pthread_mutex_unlock(&d->mutex);
    return CHANNEL_RC_OK;
}
static UINT drive_free(DEVICE* device) {
    DriveDevice* dev = (DriveDevice*)device;
    RdpDrive* d = dev->owner;
    pthread_mutex_lock(&d->mutex);
    discard_slots(d);
    rdp_ws_close_owner(d->workspace, d->owner);
    memset(d->handles, 0, sizeof(d->handles)); d->device = NULL; d->device_id = 0;
    pthread_mutex_unlock(&d->mutex);
    Stream_Free(device->data, TRUE); free(dev);
    return CHANNEL_RC_OK;
}
static UINT VCAPITYPE drive_entry(PDEVICE_SERVICE_ENTRY_POINTS ep) {
    if (!ep || !ep->rdpcontext || !ep->device || !ep->RegisterDevice ||
        ep->device->Type != RDPDR_DTYP_FILESYSTEM || !ep->device->Name ||
        strcmp(ep->device->Name, RDPSYNC_DRIVE_NAME)) return ERROR_INVALID_PARAMETER;
    RDPDR_DRIVE* settings = (RDPDR_DRIVE*)ep->device;
    if (!settings->Path || strcmp(settings->Path, RDPSYNC_DRIVE_PATH) || settings->automount) return ERROR_ACCESS_DENIED;
    RdpDrive* d = ((RdpSyncContext*)ep->rdpcontext)->drive;
    if (!d || !d->mode || !d->workspace) return ERROR_ACCESS_DENIED;
    DriveDevice* dev = calloc(1, sizeof(*dev));
    if (!dev) return CHANNEL_RC_NO_MEMORY;
    dev->owner = d; dev->device.type = RDPDR_DTYP_FILESYSTEM;
    dev->device.IRPRequest = drive_irp; dev->device.Init = drive_init; dev->device.Free = drive_free;
    dev->device.data = Stream_New(NULL, sizeof(RDPSYNC_DRIVE_NAME));
    if (!dev->device.data) { free(dev); return CHANNEL_RC_NO_MEMORY; }
    Stream_Write(dev->device.data, RDPSYNC_DRIVE_NAME, sizeof(RDPSYNC_DRIVE_NAME));
    dev->device.name = (char*)Stream_Buffer(dev->device.data);
    pthread_mutex_lock(&d->mutex);
    if (d->device) { pthread_mutex_unlock(&d->mutex); Stream_Free(dev->device.data, TRUE); free(dev); return ERROR_INVALID_STATE; }
    UINT result = ep->RegisterDevice(ep->devman, &dev->device);
    if (result == CHANNEL_RC_OK) { d->device = dev; d->device_id = dev->device.id; ep->device->Id = dev->device.id; }
    pthread_mutex_unlock(&d->mutex);
    if (result != CHANNEL_RC_OK) { Stream_Free(dev->device.data, TRUE); free(dev); }
    return result;
}
static PVIRTUALCHANNELENTRY drive_provider(LPCSTR name, LPCSTR subsystem, LPCSTR type, DWORD flags) {
    if (name && !strcmp(name, "drive")) {
        if (subsystem || !type || strcmp(type, "DeviceServiceEntry") || flags) return NULL;
        union { PDEVICE_SERVICE_ENTRY device; PVIRTUALCHANNELENTRY channel; } pointer = { .device = drive_entry };
        return pointer.channel;
    }
    return freerdp_channels_load_static_addin_entry(name, subsystem, type, flags);
}
void rdpsync_drive_install_provider(void) { (void)freerdp_register_addin_provider(drive_provider, 0); }
RdpDrive* rdpsync_drive_new(RdpWorkspace* workspace, uint64_t owner, int mode,
    RdpWorkspaceNameKey pattern_key, void* pattern_context) {
    if (!workspace || !owner || !pattern_key || mode < 1 || mode > 2) return NULL;
    RdpDrive* d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    pthread_mutex_init(&d->mutex, NULL); d->workspace = workspace; d->owner = owner;
    d->pattern_key = pattern_key; d->pattern_context = pattern_context;
    d->mode = mode; d->state = 1; d->control_remaining = DRIVE_CONTROL_BYTES;
    d->error_remaining = DRIVE_ERROR_RESERVE; d->transport_pending = -1;
    return d;
}
void rdpsync_drive_set_wakeup(RdpDrive* d, void (*wake)(void*), void* context) {
    if (!d) return;
    d->wake = wake; d->wake_context = context;
}
void rdpsync_drive_free(RdpDrive* d) {
    if (!d) return;
    /* FreeRDP context/device must have been freed first. */
    pthread_mutex_lock(&d->mutex); discard_slots(d); rdp_ws_close_owner(d->workspace, d->owner);
    pthread_mutex_unlock(&d->mutex); pthread_mutex_destroy(&d->mutex); free(d);
}
BOOL rdpsync_drive_configure(RdpDrive* d, rdpContext* context) {
    if (!context) return FALSE;
    if (!d) return freerdp_settings_get_uint32(context->settings, FreeRDP_DeviceCount) == 0;
    if (freerdp_settings_get_uint32(context->settings, FreeRDP_DeviceCount)) return FALSE;
    const char* args[] = {RDPSYNC_DRIVE_NAME, RDPSYNC_DRIVE_PATH};
    RDPDR_DRIVE* drive = (RDPDR_DRIVE*)freerdp_device_new(RDPDR_DTYP_FILESYSTEM, 2, args);
    if (!drive) return FALSE;
    if (!drive->Path || !freerdp_device_collection_add(context->settings, &drive->device)) {
        freerdp_device_free(&drive->device); return FALSE;
    }
    d->context = context; ((RdpSyncContext*)context)->drive = d;
    return freerdp_settings_set_bool(context->settings, FreeRDP_DeviceRedirection, TRUE) &&
        freerdp_settings_set_string(context->settings, FreeRDP_ClientHostname, "Android") &&
        freerdp_settings_set_string(context->settings, FreeRDP_ComputerName, "Android");
}
BOOL rdpsync_drive_settings_valid(RdpDrive* d, const rdpSettings* settings) {
    const uint32_t count = freerdp_settings_get_uint32(settings, FreeRDP_DeviceCount);
    if (!d) return count == 0;
    if (count != 1 || !freerdp_settings_get_bool(settings, FreeRDP_DeviceRedirection) ||
        freerdp_settings_get_bool(settings, FreeRDP_RedirectDrives) ||
        freerdp_settings_get_bool(settings, FreeRDP_RedirectHomeDrive)) return FALSE;
    const RDPDR_DRIVE* drive = freerdp_settings_get_pointer_array(settings, FreeRDP_DeviceArray, 0);
    return drive && drive->device.Type == RDPDR_DTYP_FILESYSTEM && drive->device.Name &&
        !strcmp(drive->device.Name, RDPSYNC_DRIVE_NAME) && drive->Path &&
        !strcmp(drive->Path, RDPSYNC_DRIVE_PATH) && !drive->automount;
}

static int observe_control(RdpDrive* d, const BYTE* p, size_t length) {
    if (length < 4 || get16(p) != RDPDR_CTYP_CORE) return 0;
    switch (get16(p + 2)) {
        case PAKID_CORE_SERVER_ANNOUNCE:
            if (length != 12) return 0;
            if (d->announced_epoch == UINT64_MAX) return 0;
            ++d->announced_epoch;
            d->server_drive_capability = 0;
            if (!d->revoked) d->state = 1;
            return 1;
        case PAKID_CORE_CLIENTID_CONFIRM: return length == 12;
        case PAKID_CORE_USER_LOGGEDON: return length == 4;
        case PAKID_CORE_DEVICE_REPLY:
            if (length != 12 || !d->device || !d->device_id || get32(p + 4) != d->device_id) return 0;
            d->reply_status = get32(p + 8);
            if (d->reply_status) {
                /* Upstream will unregister/free the device asynchronously.
                 * Retire its ingress identity before forwarding this reply;
                 * otherwise queued unknown-device requests could elicit
                 * upstream errors outside the custom completion gate. */
                d->revoked = 1; d->state = -2; d->device_id = 0;
                rdp_ws_close_owner(d->workspace, d->owner);
                memset(d->handles, 0, sizeof(d->handles));
                for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (d->slots[i].irp) {
                    d->slots[i].irp->IoStatus = STATUS_DEVICE_NOT_READY;
                    empty_response(d->slots[i].irp);
                }
            } else if (!d->revoked) d->state = d->server_drive_capability ? 3 : -1;
            return 1;
        case PAKID_CORE_SERVER_CAPABILITY: {
            if (length < 8 || get16(p + 4) > 64) return 0;
            size_t pos = 8; int drive = 0;
            for (size_t i = 0; i < get16(p + 4); ++i) {
                if (pos > length || length - pos < 8) return 0;
                const uint16_t size = get16(p + pos + 2);
                if (size < 8 || size > length - pos) return 0;
                if (get16(p + pos) == CAP_DRIVE_TYPE) {
                    if (size != 8 || (get32(p + pos + 4) != 1 && get32(p + pos + 4) != 2)) return 0;
                    drive = 1;
                }
                pos += size;
            }
            if (pos != length) return 0;
            d->server_drive_capability = drive;
            if (!d->revoked) d->state = drive ? 2 : -1;
            return 1;
        }
        default: return 0;
    }
}
BOOL rdpsync_drive_ingress(RdpDrive* d, const BYTE* data, size_t size, UINT32 flags, size_t total) {
    if (!d) return TRUE;
    if (flags & (CHANNEL_FLAG_SUSPEND | CHANNEL_FLAG_RESUME)) return TRUE;
    if (!data || !size || total < 4 || total > RDPSYNC_DRIVE_WIRE_MAX || size > total) return FALSE;
    pthread_mutex_lock(&d->mutex);
    int okay = 0;
    if (flags & CHANNEL_FLAG_FIRST) {
        if (d->ingress.active || d->pending_count >= RDPSYNC_DRIVE_PENDING ||
            total > RDPSYNC_DRIVE_INPUT_BYTES - d->pending_bytes) goto out;
        d->ingress.active = 1; d->ingress.total = total; d->ingress.received = 0;
        d->ingress.prefix_length = 0; d->ingress.slot = -1;
    }
    if (!d->ingress.active || total != d->ingress.total || size > total - d->ingress.received) goto out;
    size_t copy = sizeof(d->ingress.prefix) - d->ingress.prefix_length;
    if (copy > size) copy = size;
    memcpy(d->ingress.prefix + d->ingress.prefix_length, data, copy); d->ingress.prefix_length += copy;
    d->ingress.received += size;
    if (d->ingress.prefix_length >= 4 && get16(d->ingress.prefix) != RDPDR_CTYP_CORE) goto out;
    if (d->ingress.prefix_length >= 24 && get16(d->ingress.prefix + 2) == PAKID_CORE_DEVICE_IOREQUEST && d->ingress.slot < 0) {
        const BYTE* p = d->ingress.prefix;
        size_t response_charge = 2048;
        if (get32(p + 16) == IRP_MJ_READ) {
            if (total < 56) goto out;
            if (d->ingress.prefix_length < 28) goto fragment;
            if (get32(p + 24) > RDP_WS_MAX_IO) goto out;
            response_charge += get32(p + 24);
        }
        if (response_charge > RDPSYNC_DRIVE_RESPONSE_BYTES - d->pending_response_bytes) goto out;
        if (!d->device || get32(p + 4) != d->device_id || !d->device_id ||
            (!d->revoked && d->state < 3) || find_slot(d, get32(p + 12))) goto out;
        for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (!d->slots[i].reserved) {
            d->slots[i] = (DriveSlot){.reserved = 1, .completion = get32(p + 12), .epoch = d->announced_epoch, .input_bytes = total,
                .response_bytes = response_charge, .admitted_seconds = drive_seconds()};
            d->ingress.slot = (int)i; ++d->pending_count; d->pending_bytes += total;
            d->pending_response_bytes += response_charge;
            if (d->pending_response_bytes > d->peak_response_bytes) d->peak_response_bytes = d->pending_response_bytes;
            break;
        }
        if (d->ingress.slot < 0) goto out;
    }
fragment:
    if (flags & CHANNEL_FLAG_LAST) {
        if (d->ingress.received != total || d->ingress.prefix_length < 4) goto out;
        if (get16(d->ingress.prefix + 2) == PAKID_CORE_DEVICE_IOREQUEST) {
            if (d->ingress.slot < 0) goto out;
        } else {
            if (total > d->ingress.prefix_length || total > d->control_remaining || !observe_control(d, d->ingress.prefix, total)) goto out;
            d->control_remaining -= total;
        }
        d->ingress.active = 0;
    } else if (d->ingress.received == total) goto out;
    okay = 1;
out:
    if (!okay) d->state = -5;
    pthread_mutex_unlock(&d->mutex); return okay;
}
BOOL rdpsync_drive_pump(RdpDrive* d, freerdp* instance) {
    if (!d) return TRUE;
    /* This lifetime is held by the protocol owner. The source probe separately
     * locks the transport write state; no broker/provider runs under that lock. */
    const INT64 pending = rdpsync_freerdp_pending_bytes(instance->context);
    pthread_mutex_lock(&d->mutex);
    d->transport_pending = pending;
    if (pending >= 0 && pending > d->peak_transport_pending) d->peak_transport_pending = pending;
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) {
        if (d->slots[i].reserved && !d->slots[i].irp &&
            drive_seconds() - d->slots[i].admitted_seconds > 30) {
            /* Upstream rejected the channel state without invoking the device,
             * or a provider stopped making progress. Never replenish a leaked
             * ingress reservation and admit an unbounded replacement queue. */
            d->state = -5; pthread_mutex_unlock(&d->mutex); return FALSE;
        }
    }
    if (pending == -2 && !d->revoked) {
        d->revoked = 1; d->state = -4; rdp_ws_close_owner(d->workspace, d->owner);
        for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (d->slots[i].irp) {
            d->slots[i].irp->IoStatus = STATUS_NOT_SUPPORTED; empty_response(d->slots[i].irp);
        }
    }
    if (d->state == -2 || pending == -1 || pending > RDPSYNC_DRIVE_TRANSPORT_HIGH) { pthread_mutex_unlock(&d->mutex); return TRUE; }
    /* An ingress announce prevents all old Complete calls before the upstream
     * worker can set clearing. Only a new-epoch IRP callback proves clearing
     * has ended. This also handles caps/reply observed ahead of that worker. */
    if (d->announced_epoch != d->initialized_epoch || d->ready_epoch != d->announced_epoch) {
        pthread_mutex_unlock(&d->mutex); return TRUE;
    }
    DriveSlot* slot = NULL;
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (d->slots[i].irp) { slot = &d->slots[i]; break; }
    if (!slot) { pthread_mutex_unlock(&d->mutex); return TRUE; }
    IRP* irp = slot->irp;
    if (pending < 0) {
        const size_t charge = Stream_GetPosition(irp->output) + 256;
        if (charge > d->error_remaining) { pthread_mutex_unlock(&d->mutex); return FALSE; }
        d->error_remaining -= charge;
    }
    /* Complete consumes the IRP even if it reports failure. Publish retirement
     * before that once-only transition; Device.Free/Init share this mutex. */
    release_slot(d, slot); ++d->completed;
    UINT result = irp->Complete(irp);
    /* Ready work must not wait for a new network packet or the 100ms owner
     * timeout. One extra wake at the high watermark is harmless: the next
     * pump observes the actual queue and does not re-arm until progress. */
    if (d->wake) for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) {
        if (d->slots[i].irp) { d->wake(d->wake_context); break; }
    }
    pthread_mutex_unlock(&d->mutex);
    if (result != CHANNEL_RC_OK) return FALSE;
    /* Complete only enqueues. Immediately pass that queue through the real
     * channel manager, then re-check actual pending ciphertext next iteration. */
    return freerdp_channels_check_fds(instance->context->channels, instance);
}
void rdpsync_drive_revoke(RdpDrive* d) {
    if (!d) return;
    pthread_mutex_lock(&d->mutex); d->revoked = 1; d->state = -3;
    rdp_ws_close_owner(d->workspace, d->owner); memset(d->handles, 0, sizeof(d->handles));
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (d->slots[i].irp) {
        d->slots[i].irp->IoStatus = STATUS_CANCELLED; empty_response(d->slots[i].irp);
    }
    pthread_mutex_unlock(&d->mutex);
}
void rdpsync_drive_state(RdpDrive* d, int64_t state[13]) {
    memset(state, 0, sizeof(int64_t) * 13); state[0] = 1;
    if (!d) return;
    pthread_mutex_lock(&d->mutex);
    state[1] = d->owner; state[2] = d->mode; state[3] = d->state; state[4] = d->device_id;
    state[5] = d->reply_status; state[6] = d->read_bytes; state[7] = d->written_bytes;
    state[8] = d->pending_count; state[9] = d->pending_bytes; state[10] = d->transport_pending;
    state[11] = d->completed; state[12] = d->peak_transport_pending;
    pthread_mutex_unlock(&d->mutex);
}

void rdpsync_drive_diagnostics(RdpDrive* d, char* output, size_t capacity) {
    if (!output || !capacity) return;
    output[0] = 0; if (!d) return;
    pthread_mutex_lock(&d->mutex);
    size_t ready = 0;
    for (size_t i = 0; i < RDPSYNC_DRIVE_PENDING; ++i) if (d->slots[i].irp) ++ready;
    snprintf(output, capacity,
        "shared-space-pending-response-bytes=%zu\nshared-space-peak-response-bytes=%zu\n"
        "shared-space-ready-responses=%zu\nshared-space-control-input-remaining=%zu\n"
        "shared-space-error-output-remaining=%zu\n",
        d->pending_response_bytes, d->peak_response_bytes, ready, d->control_remaining, d->error_remaining);
    /* Fixed numeric protocol metadata only: never paths, names or file bytes. */
    const uint64_t first = d->irp_observed > 16 ? d->irp_observed - 16 : 0;
    for (uint64_t i = first; i < d->irp_observed; ++i) {
        const size_t used = strlen(output), index = (size_t)(i % 16);
        if (used >= capacity - 1) break;
        snprintf(output + used, capacity - used,
            "shared-space-irp=%llu,%u,%u,%u,0x%08x,0x%08x,0x%08x\n",
            (unsigned long long)i, d->irp_trace[index].major, d->irp_trace[index].minor,
            d->irp_trace[index].file_id, d->irp_trace[index].status,
            d->irp_trace[index].arg0, d->irp_trace[index].arg1);
    }
    pthread_mutex_unlock(&d->mutex);
}
