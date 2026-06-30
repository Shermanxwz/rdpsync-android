/**
 * Minimal freerdp_client_context_new implementation.
 * This replaces the one from libfreerdp-client3.so which can't be built on NDK.
 * Properly delegates to ClientNew/ClientFree callbacks from entry points.
 */
#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/addin.h>
#include <freerdp/channels/channels.h>

int RdpClientEntry(RDP_CLIENT_ENTRY_POINTS* p) {
    // This gets overwritten by the official bridge's RdpClientEntry
    // because both define the same symbol. The linker will pick one.
    // To avoid this conflict, the official bridge defines it as "static".
    return -1; // Should not be called
}

rdpContext* freerdp_client_context_new(const RDP_CLIENT_ENTRY_POINTS* pEntryPoints) {
    freerdp* instance = NULL;
    rdpContext* context = NULL;

    if (!pEntryPoints) return NULL;

    if (pEntryPoints->GlobalInit)
        pEntryPoints->GlobalInit();

    instance = freerdp_new();
    if (!instance) return NULL;

    instance->ContextSize = pEntryPoints->ContextSize ? pEntryPoints->ContextSize : sizeof(rdpContext);
    instance->ContextNew = pEntryPoints->ClientNew;
    instance->ContextFree = pEntryPoints->ClientFree;

    if (!freerdp_context_new(instance)) {
        freerdp_free(instance);
        return NULL;
    }

    context = instance->context;
    context->instance = instance;

    return context;
}

void freerdp_client_context_free(rdpContext* context) {
    if (!context) return;
    freerdp* instance = context->instance;
    if (instance) {
        if (instance->pClientEntryPoints) {
            RDP_CLIENT_ENTRY_POINTS* eps = instance->pClientEntryPoints;
            if (eps->GlobalUninit)
                eps->GlobalUninit();
        }
        freerdp_context_free(instance);
        freerdp_free(instance);
    }
}
