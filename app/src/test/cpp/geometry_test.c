#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include "../../main/cpp/rdpsync_geometry.h"
int main(void) {
    assert(rdpsync_valid_size(1280, 720));
    assert(rdpsync_valid_size(720, 1280));
    assert(rdpsync_valid_size(4096, 2048));
    assert(!rdpsync_valid_size(1281, 720));
    assert(rdpsync_valid_frame_size(1365, 767));
    assert(rdpsync_valid_frame_size(199, 100));
    assert(!rdpsync_valid_frame_size(0, 100));
    assert(!rdpsync_valid_size(0, 720));
    assert(!rdpsync_valid_size(4096, 4096));
    assert(!rdpsync_valid_size(INT_MAX, INT_MAX));
    RdpDirtyRect dirty = {0};
    rdpsync_union_rect(&dirty, (RdpDirtyRect){0, 0, 10, 10});
    rdpsync_union_rect(&dirty, (RdpDirtyRect){90, 90, 10, 10});
    assert(dirty.x == 0 && dirty.y == 0 && dirty.width == 100 && dirty.height == 100);
    rdpsync_union_rect(&dirty, (RdpDirtyRect){50, 50, 0, 0});
    assert(dirty.width == 100);
    dirty = (RdpDirtyRect){-5, -7, 15, 17};
    assert(rdpsync_clip_rect(&dirty, 100, 100));
    assert(dirty.x == 0 && dirty.y == 0 && dirty.width == 10 && dirty.height == 10);
    dirty = (RdpDirtyRect){90, 90, INT_MAX, INT_MAX};
    assert(rdpsync_clip_rect(&dirty, 100, 100));
    assert(dirty.width == 10 && dirty.height == 10);
    dirty = (RdpDirtyRect){200, 200, 4, 4};
    assert(!rdpsync_clip_rect(&dirty, 100, 100));
    assert(rdpsync_rgba_to_argb(0xff0000ffU) == 0xffff0000U); /* red */
    assert(rdpsync_rgba_to_argb(0xff00ff00U) == 0xff00ff00U); /* green */
    assert(rdpsync_rgba_to_argb(0xffff0000U) == 0xff0000ffU); /* blue */
    puts("native geometry: passed bounds, damage union/clipping, color conversion");
}
