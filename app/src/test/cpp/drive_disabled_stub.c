#define _GNU_SOURCE
/* Existing bridge fixtures exercise sharing OFF. Keep their original mocking
 * boundary explicit; the real device/broker/stream codec has a separate fixture.
 * An accidental enabled-drive path in an old fixture is an assertion failure. */
#include <assert.h>
#include <string.h>
#include "../../main/cpp/rdpsync_drive.h"
void rdpsync_drive_install_provider(void) { assert(!"Unexpected enabled drive in disabled-profile fixture"); }
BOOL rdpsync_drive_settings_valid(RdpDrive* d, const rdpSettings* settings) {
    assert(!d); return freerdp_settings_get_uint32(settings, FreeRDP_DeviceCount) == 0;
}
BOOL rdpsync_drive_ingress(RdpDrive* d, const BYTE* p, size_t n, UINT32 f, size_t t) {
    (void)p; (void)n; (void)f; (void)t; assert(!d); return TRUE;
}
BOOL rdpsync_drive_pump(RdpDrive* d, freerdp* instance) { (void)instance; assert(!d); return TRUE; }
void rdpsync_drive_free(RdpDrive* d) { assert(!d); }

void rdpsync_drive_set_wakeup(RdpDrive* d, void (*wake)(void*), void* context) { (void)wake; (void)context; assert(!d); }

void rdpsync_drive_diagnostics(RdpDrive* d, char* output, size_t capacity) { assert(!d); if (output && capacity) output[0] = 0; }
