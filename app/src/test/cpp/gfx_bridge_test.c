/* Production guard/lifecycle functions with deterministic upstream delegates.
 * Actual linked GDI decoding/cache/presentation is additionally exercised by
 * nativeCheckRuntime on Android; these mocks target error/rollback paths. */
#define _GNU_SOURCE
#include <assert.h>
#include "../../main/cpp/freerdp_bridge.c"
static int init_fail, uninit_calls, codec_frees, reset_calls, create_calls, map_calls, command_calls;
static UINT delegate_result;
static UINT reset_delegate(RdpgfxClientContext* g, const RDPGFX_RESET_GRAPHICS_PDU* p) { (void)g; (void)p; ++reset_calls; return delegate_result; }
static UINT create_delegate(RdpgfxClientContext* g, const RDPGFX_CREATE_SURFACE_PDU* p) { (void)g; (void)p; ++create_calls; return delegate_result; }
static UINT delete_delegate(RdpgfxClientContext* g, const RDPGFX_DELETE_SURFACE_PDU* p) { (void)g; (void)p; return delegate_result; }
static UINT map_delegate(RdpgfxClientContext* g, const RDPGFX_MAP_SURFACE_TO_OUTPUT_PDU* p) { (void)g; (void)p; ++map_calls; return delegate_result; }
static UINT scaled_delegate(RdpgfxClientContext* g, const RDPGFX_MAP_SURFACE_TO_SCALED_OUTPUT_PDU* p) { (void)g; (void)p; ++map_calls; return delegate_result; }
static UINT command_delegate(RdpgfxClientContext* g, const RDPGFX_SURFACE_COMMAND* p) { (void)g; (void)p; ++command_calls; return delegate_result; }
static UINT evict_delegate(RdpgfxClientContext* g, const RDPGFX_EVICT_CACHE_ENTRY_PDU* p) { (void)g; (void)p; return delegate_result; }
static UINT cache_delegate(RdpgfxClientContext* g, const RDPGFX_SURFACE_TO_CACHE_PDU* p) {
    if (delegate_result) return delegate_result;
    const RDPGFX_EVICT_CACHE_ENTRY_PDU evict = {p->cacheSlot};
    return g->EvictCacheEntry(g, &evict);
}
BOOL gdi_graphics_pipeline_init(rdpGdi* gdi, RdpgfxClientContext* gfx) {
    gdi->gfx = gfx; gfx->custom = gdi; gfx->codecs = (rdpCodecs*)1;
    gfx->ResetGraphics = reset_delegate; gfx->CreateSurface = create_delegate;
    gfx->DeleteSurface = delete_delegate; gfx->MapSurfaceToOutput = map_delegate;
    gfx->MapSurfaceToScaledOutput = scaled_delegate; gfx->SurfaceCommand = command_delegate;
    gfx->SurfaceToCache = cache_delegate; gfx->EvictCacheEntry = evict_delegate;
    return !init_fail;
}
void freerdp_client_codecs_free(rdpCodecs* codecs) { if (codecs) ++codec_frees; }
void gdi_graphics_pipeline_uninit(rdpGdi* gdi, RdpgfxClientContext* gfx) {
    ++uninit_calls; freerdp_client_codecs_free(gfx->codecs); gfx->codecs = NULL;
    gfx->custom = NULL; gdi->gfx = NULL;
}
int main(void) {
    RdpSession s = {0}; RdpSyncContext c = {0}; freerdp instance = {0};
    rdpGdi gdi = {.width = 1280, .height = 720}; RdpgfxClientContext gfx = {0};
    pthread_mutex_init(&s.mutex, NULL); s.instance = &instance;
    instance.context = &c.common.context; c.session = &s; c.common.context.gdi = &gdi;
    gdi.context = &c.common.context;
    init_fail = 1;
    assert(!gfx_attach(&s, &gfx) && !s.gfx_ready && !gdi.gfx && !gfx.codecs);
    BOOL advertise = TRUE, ack = TRUE;
    assert(gfx.OnOpen(&gfx, &advertise, &ack) != CHANNEL_RC_OK && !advertise && !ack);
    gfx_detach(&s, &gfx); gfx_detach(&s, &gfx);
    assert(!uninit_calls && codec_frees == 1 && !s.gfx && !gfx.custom);
    init_fail = 0;
    assert(gfx_attach(&s, &gfx) && s.gfx_ready && !gfx_attach(&s, &gfx));
    RDPGFX_RESET_GRAPHICS_PDU reset = {.width = 4096, .height = 4096};
    assert(gfx.ResetGraphics(&gfx, &reset) != CHANNEL_RC_OK && !reset_calls);
    reset.width = 1280; reset.height = 720; reset.monitorCount = 2;
    assert(gfx.ResetGraphics(&gfx, &reset) != CHANNEL_RC_OK && !reset_calls);
    reset.monitorCount = 0;
    assert(gfx.ResetGraphics(&gfx, &reset) == CHANNEL_RC_OK && reset_calls == 1);
    RDPGFX_CREATE_SURFACE_PDU create = {.surfaceId = 1, .width = 1280, .height = 720, .pixelFormat = GFX_PIXEL_FORMAT_XRGB_8888};
    delegate_result = ERROR_NOT_ENOUGH_MEMORY;
    assert(gfx.CreateSurface(&gfx, &create) == delegate_result && !s.gfx_budget.bytes);
    delegate_result = CHANNEL_RC_OK;
    assert(gfx.CreateSurface(&gfx, &create) == CHANNEL_RC_OK && s.gfx_budget.bytes);
    assert(gfx.CreateSurface(&gfx, &create) != CHANNEL_RC_OK && create_calls == 2);
    RDPGFX_MAP_SURFACE_TO_OUTPUT_PDU map = {.surfaceId = 1, .outputOriginX = 1};
    assert(gfx.MapSurfaceToOutput(&gfx, &map) != CHANNEL_RC_OK && !map_calls);
    map.outputOriginX = 0; assert(gfx.MapSurfaceToOutput(&gfx, &map) == CHANNEL_RC_OK && map_calls == 1);
    RDPGFX_SURFACE_COMMAND cmd = {.surfaceId = 1, .codecId = RDPGFX_CODECID_AVC420};
    assert(gfx.SurfaceCommand(&gfx, &cmd) != CHANNEL_RC_OK && !command_calls && !s.gfx_commands);
    cmd.codecId = RDPGFX_CODECID_CAPROGRESSIVE; delegate_result = ERROR_INVALID_DATA;
    assert(gfx.SurfaceCommand(&gfx, &cmd) != CHANNEL_RC_OK && !s.gfx_commands);
    delegate_result = CHANNEL_RC_OK;
    assert(gfx.SurfaceCommand(&gfx, &cmd) == CHANNEL_RC_OK && s.gfx_commands == 1 && s.gfx_last_codec == 9);
    RDPGFX_SURFACE_TO_CACHE_PDU cache = {.surfaceId = 1, .cacheSlot = 0, .rectSrc = {0, 0, 17, 3}};
    assert(gfx.SurfaceToCache(&gfx, &cache) != CHANNEL_RC_OK && !s.gfx_cache_budget.bytes);
    cache.cacheSlot = 4096;
    assert(gfx.SurfaceToCache(&gfx, &cache) == CHANNEL_RC_OK && s.gfx_cache_budget.bytes == 240);
    cache.rectSrc.right = 32;
    assert(gfx.SurfaceToCache(&gfx, &cache) == CHANNEL_RC_OK && s.gfx_cache_budget.bytes == 384);
    delegate_result = ERROR_NOT_ENOUGH_MEMORY;
    assert(gfx.SurfaceToCache(&gfx, &cache) != CHANNEL_RC_OK && s.gfx_cache_budget.bytes == 384);
    delegate_result = CHANNEL_RC_OK;
    RDPGFX_EVICT_CACHE_ENTRY_PDU evict = {.cacheSlot = 4096};
    assert(gfx.EvictCacheEntry(&gfx, &evict) == CHANNEL_RC_OK && !s.gfx_cache_budget.bytes);
    RDPGFX_DELETE_SURFACE_PDU remove = {.surfaceId = 1};
    delegate_result = ERROR_INVALID_DATA;
    assert(gfx.DeleteSurface(&gfx, &remove) != CHANNEL_RC_OK && s.gfx_budget.bytes);
    delegate_result = CHANNEL_RC_OK;
    assert(gfx.DeleteSurface(&gfx, &remove) == CHANNEL_RC_OK && !s.gfx_budget.bytes);
    assert(gfx.CreateSurface(&gfx, &create) == CHANNEL_RC_OK); /* reused ID after deletion */
    assert(gfx.DeleteSurface(&gfx, &remove) == CHANNEL_RC_OK);
    gfx_detach(&s, &gfx); gfx_detach(&s, &gfx);
    assert(uninit_calls == 1 && codec_frees == 2 && !s.gfx && !s.gfx_ready);
    pthread_mutex_destroy(&s.mutex);
    puts("Production GFX guard, codec accounting, cache replacement and partial-init lifecycle: PASS");
}
