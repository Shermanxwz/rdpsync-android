#ifndef RDPSYNC_GEOMETRY_H
#define RDPSYNC_GEOMETRY_H
#include <stdint.h>
#include <stddef.h>

/* Each 32-bit snapshot is bounded to 32 MiB (snapshot + Android bitmap: 64 MiB,
 * plus FreeRDP/GPU buffers). Display Control permits 8192, but mobile clients
 * need a smaller safe ceiling. */
#define RDPSYNC_MAX_DIMENSION 4096
#define RDPSYNC_MAX_PIXELS (8U * 1024U * 1024U)
typedef struct { int x, y, width, height; } RdpDirtyRect;

static inline int rdpsync_valid_frame_size(int width, int height) {
    return width > 0 && height > 0 && width <= RDPSYNC_MAX_DIMENSION &&
        height <= RDPSYNC_MAX_DIMENSION &&
        (uint64_t)width * (uint64_t)height <= RDPSYNC_MAX_PIXELS;
}
static inline int rdpsync_valid_size(int width, int height) {
    /* Only MS-RDPEDISP requests require even width and minimum 200. Legacy
     * servers can return a safe odd-width desktop; do not disconnect those. */
    return width >= 200 && height >= 200 && !(width & 1) && rdpsync_valid_frame_size(width, height);
}
static inline int rdpsync_clip_rect(RdpDirtyRect* rect, int width, int height) {
    int64_t right = (int64_t)rect->x + rect->width;
    int64_t bottom = (int64_t)rect->y + rect->height;
    if (rect->x < 0) rect->x = 0;
    if (rect->y < 0) rect->y = 0;
    if (right > width) right = width;
    if (bottom > height) bottom = height;
    if (right <= rect->x || bottom <= rect->y) { rect->width = rect->height = 0; return 0; }
    rect->width = (int)(right - rect->x);
    rect->height = (int)(bottom - rect->y);
    return 1;
}
static inline void rdpsync_union_rect(RdpDirtyRect* accumulated, RdpDirtyRect next) {
    if (next.width <= 0 || next.height <= 0) return;
    if (accumulated->width <= 0 || accumulated->height <= 0) { *accumulated = next; return; }
    int right = accumulated->x + accumulated->width;
    int bottom = accumulated->y + accumulated->height;
    if (next.x + next.width > right) right = next.x + next.width;
    if (next.y + next.height > bottom) bottom = next.y + next.height;
    if (next.x < accumulated->x) accumulated->x = next.x;
    if (next.y < accumulated->y) accumulated->y = next.y;
    accumulated->width = right - accumulated->x;
    accumulated->height = bottom - accumulated->y;
}
static inline uint32_t rdpsync_rgba_to_argb(uint32_t rgba) {
    return (rgba & 0xff00ff00U) | ((rgba & 0x00ff0000U) >> 16) | ((rgba & 0x000000ffU) << 16);
}
#endif
