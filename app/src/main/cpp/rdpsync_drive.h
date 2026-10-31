#ifndef RDPSYNC_DRIVE_H
#define RDPSYNC_DRIVE_H
#include <stdint.h>
#include <stddef.h>
#include <freerdp/freerdp.h>
#include "rdpsync_workspace.h"

#define RDPSYNC_DRIVE_NAME "RdpSync"
#define RDPSYNC_DRIVE_PATH "rdpsync:private-workspace-v1"
#define RDPSYNC_DRIVE_PENDING 32U
#define RDPSYNC_DRIVE_INPUT_BYTES (4U * 1024U * 1024U)
#define RDPSYNC_DRIVE_RESPONSE_BYTES (8U * 1024U * 1024U)
#define RDPSYNC_DRIVE_WIRE_MAX (RDP_WS_MAX_IO + 64U)
#define RDPSYNC_DRIVE_TRANSPORT_HIGH (256U * 1024U)

typedef struct RdpDrive RdpDrive;
RdpDrive* rdpsync_drive_new(RdpWorkspace* workspace, uint64_t owner, int mode,
    RdpWorkspaceNameKey pattern_key, void* pattern_context);
void rdpsync_drive_free(RdpDrive* drive);
/* Set before connect. Callback only signals the existing owner wake event. */
void rdpsync_drive_set_wakeup(RdpDrive* drive, void (*wake)(void*), void* context);
BOOL rdpsync_drive_configure(RdpDrive* drive, rdpContext* context);
BOOL rdpsync_drive_settings_valid(RdpDrive* drive, const rdpSettings* settings);
void rdpsync_drive_install_provider(void);
BOOL rdpsync_drive_ingress(RdpDrive* drive, const BYTE* data, size_t size, UINT32 flags, size_t total);
/* Called only by the protocol owner, never a channel worker or JNI thread. */
BOOL rdpsync_drive_pump(RdpDrive* drive, freerdp* instance);
void rdpsync_drive_revoke(RdpDrive* drive);
void rdpsync_drive_state(RdpDrive* drive, int64_t state[13]);
/* Numeric bounded diagnostics only, never file names/paths/contents. */
void rdpsync_drive_diagnostics(RdpDrive* drive, char* output, size_t capacity);
/* The exact pinned, read-only upstream addition. Negative != an empty queue. */
INT64 rdpsync_freerdp_pending_bytes(rdpContext* context);
/* Owner only: one bounded nonblocking ciphertext drain; -3 is fatal. */
INT64 rdpsync_freerdp_drain_pending(rdpContext* context);

#endif
