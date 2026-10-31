#ifndef RDPSYNC_UTF_H
#define RDPSYNC_UTF_H
/* Strict Unicode scalar codec for bounded native names. No modified UTF-8,
 * replacement characters for invalid input, locale dependence, or JNI locals.
 * NFC/case policy remains the broker's separate pure Java callback. */
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
static inline char* rdpsync_utf16_to_utf8(const uint16_t* in, size_t units, size_t* bytes) {
    if (!in || units > (SIZE_MAX - 1) / 3) return NULL;
    char* out = malloc(units * 3 + 1); if (!out) return NULL;
    size_t pos = 0;
    for (size_t i = 0; i < units; ++i) {
        uint32_t ch = in[i];
        if (!ch || (ch >= 0xdc00 && ch <= 0xdfff)) goto invalid;
        if (ch >= 0xd800 && ch <= 0xdbff) {
            if (++i >= units || in[i] < 0xdc00 || in[i] > 0xdfff) goto invalid;
            ch = 0x10000 + ((ch - 0xd800) << 10) + (in[i] - 0xdc00);
        }
        if (ch < 0x80) out[pos++] = (char)ch;
        else if (ch < 0x800) { out[pos++] = (char)(0xc0 | (ch >> 6)); out[pos++] = (char)(0x80 | (ch & 63)); }
        else if (ch < 0x10000) { out[pos++] = (char)(0xe0 | (ch >> 12)); out[pos++] = (char)(0x80 | ((ch >> 6) & 63)); out[pos++] = (char)(0x80 | (ch & 63)); }
        else { out[pos++] = (char)(0xf0 | (ch >> 18)); out[pos++] = (char)(0x80 | ((ch >> 12) & 63)); out[pos++] = (char)(0x80 | ((ch >> 6) & 63)); out[pos++] = (char)(0x80 | (ch & 63)); }
    }
    out[pos] = 0; if (bytes) *bytes = pos; return out;
invalid: free(out); return NULL;
}
static inline uint16_t* rdpsync_utf8_to_utf16(const char* in, size_t bytes, size_t* units) {
    if (!in || bytes > SIZE_MAX / sizeof(uint16_t) - 1) return NULL;
    uint16_t* out = malloc((bytes + 1) * sizeof(uint16_t)); if (!out) return NULL;
    size_t pos = 0;
    for (size_t i = 0; i < bytes;) {
        uint32_t ch = (unsigned char)in[i++], minimum = 0; size_t rest = 0;
        if (!ch) goto invalid;
        if (ch < 0x80) { out[pos++] = (uint16_t)ch; continue; }
        if (ch >= 0xc2 && ch <= 0xdf) { ch &= 31; rest = 1; minimum = 0x80; }
        else if (ch >= 0xe0 && ch <= 0xef) { ch &= 15; rest = 2; minimum = 0x800; }
        else if (ch >= 0xf0 && ch <= 0xf4) { ch &= 7; rest = 3; minimum = 0x10000; }
        else goto invalid;
        if (rest > bytes - i) goto invalid;
        while (rest--) { unsigned char next = (unsigned char)in[i++]; if ((next & 0xc0) != 0x80) goto invalid; ch = (ch << 6) | (next & 63); }
        if (ch < minimum || ch > 0x10ffff || (ch >= 0xd800 && ch <= 0xdfff)) goto invalid;
        if (ch < 0x10000) out[pos++] = (uint16_t)ch;
        else { ch -= 0x10000; out[pos++] = (uint16_t)(0xd800 | (ch >> 10)); out[pos++] = (uint16_t)(0xdc00 | (ch & 1023)); }
    }
    out[pos] = 0; if (units) *units = pos; return out;
invalid: free(out); return NULL;
}
#endif
