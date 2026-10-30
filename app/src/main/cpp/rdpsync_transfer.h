#ifndef RDPSYNC_TRANSFER_H
#define RDPSYNC_TRANSFER_H
#include <stddef.h>
#include <stdint.h>
#include <freerdp/client/cliprdr.h>
#define RDPSYNC_TRANSFER_FILES 64
#define RDPSYNC_TRANSFER_NAME 240
#define RDPSYNC_TRANSFER_FILE_BYTES (128ULL * 1024 * 1024)
#define RDPSYNC_TRANSFER_BATCH_BYTES (256ULL * 1024 * 1024)
#define RDPSYNC_TRANSFER_SEND_BYTES (32ULL * 1024 * 1024)
#define RDPSYNC_TRANSFER_CONTROL_BYTES (1024ULL * 1024)
#define RDPSYNC_TRANSFER_MAX_MESSAGES 8192U
#define RDPSYNC_TRANSFER_CHUNK 65536
#define RDPSYNC_TRANSFER_PEER_RANGE (8U * 1024 * 1024)
#define RDPSYNC_TRANSFER_MESSAGE_MAX (RDPSYNC_TRANSFER_CHUNK + 1024U)
/* Negative results are stable JNI/storage error codes; no endpoint/file text. */
enum { TF_DISCONNECTED=-1, TF_UNAVAILABLE=-2, TF_BUSY=-3, TF_STALE=-4,
       TF_INVALID=-5, TF_IO=-6, TF_CANCELLED=-7, TF_TIMEOUT=-8, TF_QUOTA=-9 };
typedef struct { uint16_t name[RDPSYNC_TRANSFER_NAME + 1]; uint64_t size; } RdpTransferFile;
typedef struct RdpTransfer RdpTransfer;
typedef struct { size_t total, received; uint32_t messages; int active; } RdpTransferIngress;
int rdpsync_transfer_ingress(RdpTransferIngress* state, size_t size, uint32_t flags, size_t total);
int rdpsync_transfer_admit_incoming(RdpTransfer* transfer, const uint8_t* first, size_t size, size_t total);
/* Local staging only: runtime_cache is the immutable Application-initialized
 * directory, never a JNI path argument. Returns an owned CLOEXEC readonly fd. */
int rdpsync_transfer_open_staged(const char* runtime_cache, const char* directory_id,
    const char* file_id, uint64_t expected_size);
/* Name checks here are structural, not Unicode normalization. Kotlin applies
 * NFC/full Unicode case-fold validation before any staged file allocation. */
int rdpsync_transfer_name_valid(const uint16_t* name, size_t length);
int rdpsync_transfer_parse_manifest(const uint8_t* data, size_t length,
    RdpTransferFile files[RDPSYNC_TRANSFER_FILES], size_t* count, uint64_t* total);
size_t rdpsync_transfer_write_manifest(const RdpTransferFile* files, size_t count, uint8_t* out, size_t size);
RdpTransfer* rdpsync_transfer_new(uint64_t generation, int enabled);
void rdpsync_transfer_free(RdpTransfer* transfer); /* after channel worker joined */
void rdpsync_transfer_attach(RdpTransfer* transfer, CliprdrClientContext* context);
void rdpsync_transfer_detach(RdpTransfer* transfer, CliprdrClientContext* context);
/* Owner-loop pump sends commands queued by JNI; callbacks are mutex protected. */
UINT rdpsync_transfer_pump(RdpTransfer* transfer);
void rdpsync_transfer_state(RdpTransfer* transfer, int64_t values[10]);
int64_t rdpsync_transfer_offer(RdpTransfer* transfer, uint64_t generation,
    const RdpTransferFile* files, const int* fds, size_t count);
int rdpsync_transfer_retained(RdpTransfer* transfer, uint64_t token);
int rdpsync_transfer_cancel(RdpTransfer* transfer, uint64_t token);
int64_t rdpsync_transfer_request_list(RdpTransfer* transfer, uint64_t generation);
int rdpsync_transfer_manifest(RdpTransfer* transfer, uint64_t token,
    RdpTransferFile files[RDPSYNC_TRANSFER_FILES], size_t* count);
int rdpsync_transfer_request_chunk(RdpTransfer* transfer, uint64_t token, size_t index,
    uint64_t offset, uint32_t length);
/* Returns length, 0 pending, or negative error. Exactly one take per request. */
int rdpsync_transfer_take_chunk(RdpTransfer* transfer, uint64_t token, uint8_t* out, size_t capacity);
int rdpsync_transfer_finish(RdpTransfer* transfer, uint64_t token);
#endif
