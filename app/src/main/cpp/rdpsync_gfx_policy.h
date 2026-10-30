#ifndef RDPSYNC_GFX_POLICY_H
#define RDPSYNC_GFX_POLICY_H
#include "rdpsync_geometry.h"

/* Bounds the additional GFX pixel surfaces, not every upstream codec/cache
 * allocation. GNOME's single-monitor software path uses a small number of
 * surfaces; retain room for replacement/staging without unbounded live IDs. */
#define RDPSYNC_GFX_MAX_SURFACES 16
#define RDPSYNC_GFX_MAX_SURFACE_BYTES (64ULL * 1024ULL * 1024ULL)
#define RDPSYNC_GFX_CACHE_SLOTS 4096
#define RDPSYNC_GFX_MAX_CACHE_BYTES (32ULL * 1024ULL * 1024ULL)
typedef struct {
    uint16_t id;
    uint32_t width, height;
    uint64_t bytes;
} RdpGfxSurfaceSlot;
typedef struct {
    RdpGfxSurfaceSlot slots[RDPSYNC_GFX_MAX_SURFACES];
    uint64_t bytes;
} RdpGfxBudget;
typedef struct {
    uint64_t slots[RDPSYNC_GFX_CACHE_SLOTS], bytes;
} RdpGfxCacheBudget;

static inline uint64_t rdpsync_gfx_cache_cost(const RdpGfxCacheBudget* budget,
    uint32_t slot, uint32_t width, uint32_t height) {
    if (slot == 0 || slot > RDPSYNC_GFX_CACHE_SLOTS || !rdpsync_valid_frame_size((int)width, (int)height)) return 0;
    const uint64_t bytes = (((uint64_t)width * 4 + 15) & ~15ULL) * height;
    /* Upstream allocates a replacement before evicting its old slot, so count
     * BOTH allocations until the delegated operation completes. */
    return bytes <= RDPSYNC_GFX_MAX_CACHE_BYTES - budget->bytes ? bytes : 0;
}
static inline void rdpsync_gfx_cache_release(RdpGfxCacheBudget* budget, uint32_t slot) {
    if (slot == 0 || slot > RDPSYNC_GFX_CACHE_SLOTS) return;
    budget->bytes -= budget->slots[slot - 1]; budget->slots[slot - 1] = 0;
}

static inline int rdpsync_gfx_slot(const RdpGfxBudget* budget, uint16_t id) {
    for (int i = 0; i < RDPSYNC_GFX_MAX_SURFACES; ++i)
        if (budget->slots[i].bytes && budget->slots[i].id == id) return i;
    return -1;
}
static inline int rdpsync_gfx_reserve(RdpGfxBudget* budget, uint16_t id, uint32_t width, uint32_t height) {
    if (!rdpsync_valid_frame_size((int)width, (int)height) || rdpsync_gfx_slot(budget, id) >= 0) return -1;
    /* Match pinned gdi_CreateSurface's 16-pixel alignment and SIMD row pad. */
    const uint64_t aligned_width = ((uint64_t)width + 15) & ~15ULL;
    const uint64_t aligned_height = ((uint64_t)height + 15) & ~15ULL;
    const uint64_t bytes = (aligned_width * 4 + 16) * aligned_height;
    if (bytes > RDPSYNC_GFX_MAX_SURFACE_BYTES - budget->bytes) return -1;
    for (int i = 0; i < RDPSYNC_GFX_MAX_SURFACES; ++i) {
        if (!budget->slots[i].bytes) {
            budget->slots[i] = (RdpGfxSurfaceSlot){id, width, height, bytes};
            budget->bytes += bytes;
            return i;
        }
    }
    return -1;
}
static inline void rdpsync_gfx_release(RdpGfxBudget* budget, uint16_t id) {
    int slot = rdpsync_gfx_slot(budget, id);
    if (slot < 0) return;
    budget->bytes -= budget->slots[slot].bytes;
    budget->slots[slot] = (RdpGfxSurfaceSlot){0};
}
static inline int rdpsync_gfx_output_fits(uint32_t desktop_width, uint32_t desktop_height,
    uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    return rdpsync_valid_frame_size((int)width, (int)height) && x < desktop_width && y < desktop_height &&
        (uint64_t)x + width <= desktop_width && (uint64_t)y + height <= desktop_height;
}
#endif
