/* Separate translation unit: the optimized caller must see the real upstream
 * malloc/noalias declaration, not see through this initialized owner cycle. */
#include <stdlib.h>
#include <freerdp/client.h>
rdpContext* freerdp_client_context_new(const RDP_CLIENT_ENTRY_POINTS* entry) {
    freerdp* instance = calloc(1, sizeof(freerdp));
    rdpContext* context = calloc(1, entry->ContextSize);
    if (!instance || !context) { free(instance); free(context); return NULL; }
    instance->context = context;
    context->instance = instance;
    if (entry->ClientNew && !entry->ClientNew(instance, context)) {
        free(instance); free(context); return NULL;
    }
    return context;
}
void freerdp_client_context_free(rdpContext* context) {
    if (context) { free(context->instance); free(context); }
}
