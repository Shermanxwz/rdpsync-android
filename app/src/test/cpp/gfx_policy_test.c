#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include "../../main/cpp/rdpsync_gfx_policy.h"

int main(void) {
    RdpGfxBudget budget = {0};
    assert(rdpsync_gfx_reserve(&budget, 7, 17, 31) == 0);
    assert(budget.bytes == (32 * 4 + 16) * 32);
    const uint64_t original = budget.bytes;
    assert(rdpsync_gfx_reserve(&budget, 7, 100, 100) < 0 && budget.bytes == original);
    rdpsync_gfx_release(&budget, 999);
    assert(budget.bytes == original);
    rdpsync_gfx_release(&budget, 7);
    assert(!budget.bytes && rdpsync_gfx_slot(&budget, 7) < 0);
    assert(rdpsync_gfx_reserve(&budget, 7, 200, 200) >= 0);
    rdpsync_gfx_release(&budget, 7);
    assert(rdpsync_gfx_reserve(&budget, 1, 0, 100) < 0);
    assert(rdpsync_gfx_reserve(&budget, 1, UINT_MAX, 100) < 0);
    assert(rdpsync_gfx_reserve(&budget, 1, 4097, 100) < 0);
    assert(rdpsync_gfx_reserve(&budget, 1, 4096, 4096) < 0);
    assert(!budget.bytes);
    assert(rdpsync_gfx_reserve(&budget, 1, 4000, 2000) >= 0);
    assert(rdpsync_gfx_reserve(&budget, 2, 4000, 2000) >= 0);
    const uint64_t two_surfaces = budget.bytes;
    assert(rdpsync_gfx_reserve(&budget, 3, 4000, 2000) < 0 && budget.bytes == two_surfaces);
    rdpsync_gfx_release(&budget, 1);
    assert(rdpsync_gfx_reserve(&budget, 3, 4000, 2000) >= 0);
    budget = (RdpGfxBudget){0};
    for (unsigned i = 0; i < RDPSYNC_GFX_MAX_SURFACES; ++i)
        assert(rdpsync_gfx_reserve(&budget, i, 1, 1) >= 0);
    assert(rdpsync_gfx_reserve(&budget, 99, 1, 1) < 0);
    assert(rdpsync_gfx_output_fits(1280, 720, 0, 0, 1280, 720));
    assert(rdpsync_gfx_output_fits(1280, 720, 1279, 719, 1, 1));
    assert(!rdpsync_gfx_output_fits(1280, 720, UINT_MAX, 0, 10, 10));
    assert(!rdpsync_gfx_output_fits(1280, 720, 1279, 719, 2, 2));
    RdpGfxCacheBudget cache = {0};
    assert(rdpsync_gfx_cache_cost(&cache, 0, 1, 1) == 0);
    assert(rdpsync_gfx_cache_cost(&cache, 4097, 1, 1) == 0);
    assert(rdpsync_gfx_cache_cost(&cache, 4096, 17, 3) == 80 * 3);
    cache.slots[0] = cache.bytes = RDPSYNC_GFX_MAX_CACHE_BYTES;
    assert(!rdpsync_gfx_cache_cost(&cache, 1, 1, 1)); /* replacement peak counts old entry */
    rdpsync_gfx_cache_release(&cache, 1);
    assert(!cache.bytes && rdpsync_gfx_cache_cost(&cache, 1, 4096, 2048) == 32ULL * 1024 * 1024);
    puts("GFX aligned allocation, aggregate budget, ID lifecycle and mapping policy: PASS");
}
