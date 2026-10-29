#ifndef RDPSYNC_CONTEXT_H
#define RDPSYNC_CONTEXT_H
#include <freerdp/client.h>

typedef struct RdpSession RdpSession;
typedef struct { rdpClientContext common; RdpSession* session; } RdpSyncContext;

/* Shared production factory, also compiled against a separate-TU allocator in
 * the optimized regression. Do not replace instance->context with ctx below. */
static inline freerdp* rdpsync_create_instance(RdpSession* session, pRdpClientNew client_new) {
    RDP_CLIENT_ENTRY_POINTS entry = {0};
    entry.Version = RDP_CLIENT_INTERFACE_VERSION;
    entry.Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
    entry.ContextSize = sizeof(RdpSyncContext);
    entry.ClientNew = client_new;
    rdpContext* ctx = freerdp_client_context_new(&entry);
    if (!ctx) return NULL;
    freerdp* instance = ctx->instance;
    /* The upstream allocator carries a malloc/noalias annotation. Binding via
     * its returned ctx and then returning only ctx->instance lets Clang -O2
     * eliminate the tail store as dead (the actual instance<->context cycle is
     * invisible to that annotation). Write through the escaping instance's
     * context instead. Android's real runtime gate checks this exact binding. */
    ((RdpSyncContext*)instance->context)->session = session;
    return instance;
}
#endif
