/* Compile at -O2 with Clang, without LTO, separately from the fake allocator.
 * This invokes the IDENTICAL production factory; manually initialized callback
 * fixtures cannot catch an optimizer-eliminated session binding store. */
#include <stdio.h>
#ifndef RDPSYNC_CONTEXT_HEADER
#define RDPSYNC_CONTEXT_HEADER "../../main/cpp/rdpsync_context.h"
#endif
#include RDPSYNC_CONTEXT_HEADER
struct RdpSession { int marker; };
static BOOL client_new(freerdp* instance, rdpContext* context) {
    return instance->context == context;
}
int main(void) {
    for (int n = 0; n < 100; ++n) {
        RdpSession session = {n};
        freerdp* instance = rdpsync_create_instance(&session, client_new);
        if (!instance) return 2;
        const int bound = ((RdpSyncContext*)instance->context)->session == &session;
        freerdp_client_context_free(instance->context);
        if (!bound) {
            fputs("optimized production context binding FAILED\n", stderr);
            return 1;
        }
    }
    puts("optimized production context binding: passed Clang -O2 with separate malloc-annotated allocator");
    return 0;
}
