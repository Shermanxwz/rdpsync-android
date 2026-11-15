#ifndef RDPSYNC_UBUNTU_IME_H
#define RDPSYNC_UBUNTU_IME_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#if defined(__ANDROID__)
#include <dlfcn.h>
#endif

#define RDPSYNC_TEXT_OFFER_MAX_UTF8  16384U
#define RDPSYNC_TEXT_OFFER_MAX_UTF16 32772U
#define RDPSYNC_FMT_UTF8_STRING      0xC002U
#define RDPSYNC_FMT_TEXT_PLAIN_UTF8  0xC003U

enum {
    RDPSYNC_LINUX_TEXT_AUTO = 0,
    RDPSYNC_LINUX_TEXT_GUI_PASTE = 1,
    RDPSYNC_LINUX_TEXT_TERM_PASTE = 2,
    RDPSYNC_LINUX_TEXT_IBUS_HEX = 3,
    RDPSYNC_LINUX_TEXT_UNICODE = 4,
    RDPSYNC_LINUX_TEXT_UNIVERSAL_PASTE = 5
};

/* Strict RFC-3629 UTF-8 decoder. Returns 1 on codepoint, 0 at end, -1 on invalid byte. */
static inline int rdpsync_utf8_next(const uint8_t* s, size_t len, size_t* idx, uint32_t* out_cp) {
    if (*idx >= len) return 0;
    const uint8_t b0 = s[*idx];
    if (b0 < 0x80U) {
        *out_cp = b0;
        (*idx) += 1;
        return 1;
    }
    if ((b0 & 0xE0U) == 0xC0U) {
        if (*idx + 1 >= len) return -1;
        const uint8_t b1 = s[*idx + 1];
        if ((b1 & 0xC0U) != 0x80U) return -1;
        const uint32_t cp = ((uint32_t)(b0 & 0x1FU) << 6) | (uint32_t)(b1 & 0x3FU);
        if (cp < 0x80U) return -1;
        *out_cp = cp;
        (*idx) += 2;
        return 1;
    }
    if ((b0 & 0xF0U) == 0xE0U) {
        if (*idx + 2 >= len) return -1;
        const uint8_t b1 = s[*idx + 1], b2 = s[*idx + 2];
        if ((b1 & 0xC0U) != 0x80U || (b2 & 0xC0U) != 0x80U) return -1;
        const uint32_t cp = ((uint32_t)(b0 & 0x0FU) << 12) |
                            ((uint32_t)(b1 & 0x3FU) << 6) |
                            (uint32_t)(b2 & 0x3FU);
        if (cp < 0x800U || (cp >= 0xD800U && cp <= 0xDFFFU)) return -1;
        *out_cp = cp;
        (*idx) += 3;
        return 1;
    }
    if ((b0 & 0xF8U) == 0xF0U) {
        if (*idx + 3 >= len) return -1;
        const uint8_t b1 = s[*idx + 1], b2 = s[*idx + 2], b3 = s[*idx + 3];
        if ((b1 & 0xC0U) != 0x80U || (b2 & 0xC0U) != 0x80U || (b3 & 0xC0U) != 0x80U) return -1;
        const uint32_t cp = ((uint32_t)(b0 & 0x07U) << 18) |
                            ((uint32_t)(b1 & 0x3FU) << 12) |
                            ((uint32_t)(b2 & 0x3FU) << 6) |
                            (uint32_t)(b3 & 0x3FU);
        if (cp < 0x10000U || cp > 0x10FFFFU) return -1;
        *out_cp = cp;
        (*idx) += 4;
        return 1;
    }
    return -1;
}

/* Encodes UTF-8 into RDP CF_UNICODETEXT (UTF-16LE with CRLF normalization + double-NUL). */
static inline size_t rdpsync_encode_cf_unicodetext(const uint8_t* utf8, size_t utf8_len,
                                                   uint8_t* out_utf16le, size_t cap) {
    if (!utf8 || !out_utf16le || cap < 2 || utf8_len == 0 || utf8_len > RDPSYNC_TEXT_OFFER_MAX_UTF8) {
        return 0;
    }
    size_t idx = 0, out = 0;
    uint32_t prev = 0;
    while (idx < utf8_len) {
        uint32_t cp = 0;
        if (rdpsync_utf8_next(utf8, utf8_len, &idx, &cp) <= 0) return 0;
        if (cp == '\n' && prev != '\r') {
            if (out + 4 > cap - 2) return 0;
            out_utf16le[out++] = '\r';
            out_utf16le[out++] = 0;
        }
        if (cp <= 0xFFFFU) {
            if (out + 2 > cap - 2) return 0;
            out_utf16le[out++] = (uint8_t)(cp & 0xFFU);
            out_utf16le[out++] = (uint8_t)((cp >> 8) & 0xFFU);
        } else {
            if (out + 4 > cap - 2) return 0;
            const uint32_t v = cp - 0x10000U;
            const uint16_t hi = (uint16_t)(0xD800U + (v >> 10));
            const uint16_t lo = (uint16_t)(0xDC00U + (v & 0x3FFU));
            out_utf16le[out++] = (uint8_t)(hi & 0xFFU);
            out_utf16le[out++] = (uint8_t)(hi >> 8);
            out_utf16le[out++] = (uint8_t)(lo & 0xFFU);
            out_utf16le[out++] = (uint8_t)(lo >> 8);
        }
        prev = cp;
    }
    out_utf16le[out++] = 0;
    out_utf16le[out++] = 0;
    return out;
}

static inline uint8_t rdpsync_hex_nibble_scancode(unsigned nibble) {
    static const uint8_t map[16] = {
        0x0B, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21
    };
    return map[nibble & 0xFU];
}

/* ARM64 NEON / 64-bit unrolled opaque-RGBA row copy for end_paint hot path. */
static inline void rdpsync_copy_opaque_rgba_fast(uint32_t* restrict dst,
                                                 const uint8_t* restrict src,
                                                 size_t count) {
    size_t i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    const uint32x4_t alpha_vec = vdupq_n_u32(0xFF000000U);
    for (; i + 4 <= count; i += 4) {
        uint32x4_t px;
        memcpy(&px, src + i * 4, sizeof(px));
        px = vorrq_u32(px, alpha_vec);
        vst1q_u32(dst + i, px);
    }
#endif
    const uint64_t alpha64 = 0xFF000000FF000000ULL;
    for (; i + 2 <= count; i += 2) {
        uint64_t pair;
        memcpy(&pair, src + i * 4, sizeof(pair));
        pair |= alpha64;
        memcpy(dst + i, &pair, sizeof(pair));
    }
    for (; i < count; ++i) {
        uint32_t px;
        memcpy(&px, src + i * 4, sizeof(px));
        dst[i] = px | 0xFF000000U;
    }
}

/* Safe runtime probe for Android hardware H.264 MediaCodec without host build dependency. */
static inline int rdpsync_probe_android_hw_avc(void) {
#if defined(__ANDROID__)
    static int cached = -1;
    if (cached >= 0) return cached;
    void* lib = dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        cached = 0;
        return 0;
    }
    typedef void* (*create_fn)(const char*);
    typedef int (*delete_fn)(void*);
    create_fn create_dec = NULL;
    delete_fn delete_dec = NULL;
    void* sym_create = dlsym(lib, "AMediaCodec_createDecoderByType");
    void* sym_delete = dlsym(lib, "AMediaCodec_delete");
    memcpy(&create_dec, &sym_create, sizeof(create_dec));
    memcpy(&delete_dec, &sym_delete, sizeof(delete_dec));
    int ok = 0;
    if (create_dec && delete_dec) {
        void* codec = create_dec("video/avc");
        if (codec) {
            ok = 1;
            delete_dec(codec);
        }
    }
    dlclose(lib);
    cached = ok;
    return ok;
#else
    return 0;
#endif
}

#endif /* RDPSYNC_UBUNTU_IME_H */
