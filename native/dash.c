/* Pixel work for StarDash. Source is the shared AVM mosaic: 2560x1920 UYVY, row stride 5120.
 * No libc dependency. JNI slot 230 is GetDirectBufferAddress in the JNI ABI. */
#include <arm_neon.h>

typedef const void *const *EnvTable;
typedef EnvTable *Env;
typedef void *(*DirectAddr)(Env, void *);
#define ADDR(env, buf) ((unsigned char *)((DirectAddr)(*(env))[230])((env), (buf)))

enum { SRC_STRIDE = 5120, OUT_W = 1280, CAM_ROWS = 960 };

static inline unsigned char clamp8(int v) { return (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v); }

/* Mosaic to NV12 at 1280x960 (2:1 both ways), then the info bar's rows below it. */
void Java_com_sgmwstarlight_stardash_Native_frame(Env env, void *cls, void *srcBuf, void *yBuf, int yStride,
                                                  void *uvBuf, int uvStride, void *barBuf, int barRows) {
    const unsigned char *src = ADDR(env, srcBuf);
    unsigned char *yOut = ADDR(env, yBuf), *uvOut = ADDR(env, uvBuf);
    for (int row = 0; row < CAM_ROWS; row++) {
        const unsigned char *s = src + (long)row * 2 * SRC_STRIDE;
        unsigned char *y = yOut + (long)row * yStride;
        int chroma = (row & 1) == 0;
        unsigned char *uv = uvOut + (long)(row / 2) * uvStride;
        for (int i = 0; i < 80; i++) {
            uint8x16x4_t p = vld4q_u8(s);
            vst1q_u8(y, p.val[1]);
            if (chroma) {
                uint8x8x2_t c;
                c.val[0] = vmovn_u16(vreinterpretq_u16_u8(p.val[0]));
                c.val[1] = vmovn_u16(vreinterpretq_u16_u8(p.val[2]));
                vst2_u8(uv, c);
                uv += 16;
            }
            s += 64;
            y += 16;
        }
    }
    if (barBuf == 0 || barRows <= 0) return;
    /* Bar layout: barRows rows of Y (1280 each), then barRows/2 rows of interleaved UV. */
    const unsigned char *bar = ADDR(env, barBuf);
    for (int r = 0; r < barRows; r++) {
        const unsigned char *b = bar + (long)r * OUT_W;
        unsigned char *y = yOut + (long)(CAM_ROWS + r) * yStride;
        for (int i = 0; i < OUT_W; i += 16) vst1q_u8(y + i, vld1q_u8(b + i));
    }
    const unsigned char *barUv = bar + (long)barRows * OUT_W;
    for (int r = 0; r < barRows / 2; r++) {
        const unsigned char *b = barUv + (long)r * OUT_W;
        unsigned char *uv = uvOut + (long)(CAM_ROWS / 2 + r) * uvStride;
        for (int i = 0; i < OUT_W; i += 16) vst1q_u8(uv + i, vld1q_u8(b + i));
    }
}

/* RGBA bytes (Bitmap.copyPixelsToBuffer order) to the bar's NV12 layout, BT.601 limited range. */
void Java_com_sgmwstarlight_stardash_Native_rgbaToNv12(Env env, void *cls, void *rgbaBuf, void *outBuf, int w, int h) {
    const unsigned char *rgba = ADDR(env, rgbaBuf);
    unsigned char *out = ADDR(env, outBuf);
    unsigned char *uvOut = out + (long)w * h;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const unsigned char *p = rgba + ((long)y * w + x) * 4;
            out[(long)y * w + x] = clamp8(((66 * p[0] + 129 * p[1] + 25 * p[2] + 128) >> 8) + 16);
        }
    }
    for (int y = 0; y < h; y += 2) {
        unsigned char *uv = uvOut + (long)(y / 2) * w;
        for (int x = 0; x < w; x += 2) {
            const unsigned char *p = rgba + ((long)y * w + x) * 4;
            int r = p[0], g = p[1], b = p[2];
            uv[x] = clamp8(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            uv[x + 1] = clamp8(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
    }
}

/* 160x120 sample grid (each sample the mean of two luma pairs), grouped into 20x15 blocks of 8x8
 * samples. A block counts as moving only when at least `blockMin` of its 64 samples changed by more
 * than `noise`, so scattered changes (rain, flicker) are ignored while a person or car is not.
 * Returns movingBlocks | (meanLuma << 16). */
int Java_com_sgmwstarlight_stardash_Native_motion(Env env, void *cls, void *srcBuf, void *prevBuf, int noise, int blockMin) {
    const unsigned char *src = ADDR(env, srcBuf);
    unsigned char *prev = ADDR(env, prevBuf);
    unsigned char counts[300];
    for (int i = 0; i < 300; i++) counts[i] = 0;
    long sum = 0;
    for (int r = 0; r < 120; r++) {
        const unsigned char *s0 = src + (long)(r * 16 + 8) * SRC_STRIDE;
        const unsigned char *s1 = s0 + SRC_STRIDE;
        unsigned char *rowCounts = counts + (r / 8) * 20;
        for (int c = 0; c < 160; c++) {
            int o = c * 32;
            int v = (s0[o + 1] + s0[o + 3] + s1[o + 1] + s1[o + 3] + 2) >> 2;
            int d = v - *prev;
            if (d < 0) d = -d;
            if (d > noise) rowCounts[c / 8]++;
            sum += v;
            *prev++ = (unsigned char)v;
        }
    }
    int moving = 0;
    for (int i = 0; i < 300; i++) if (counts[i] >= blockMin) moving++;
    return moving | (int)((sum / 19200) << 16);
}

static inline void yuvToRgba(int y, int u, int v, unsigned char *o) {
    int c = (y - 16) * 298, d = u - 128, e = v - 128;
    o[0] = clamp8((c + 409 * e + 128) >> 8);
    o[1] = clamp8((c - 100 * d - 208 * e + 128) >> 8);
    o[2] = clamp8((c + 516 * d + 128) >> 8);
    o[3] = 255;
}

/* Whole mosaic, every `step` pixels, into RGBA for a preview bitmap. */
void Java_com_sgmwstarlight_stardash_Native_preview(Env env, void *cls, void *srcBuf, void *outBuf, int step) {
    const unsigned char *src = ADDR(env, srcBuf);
    unsigned char *out = ADDR(env, outBuf);
    int w = 2560 / step, h = 1920 / step;
    for (int y = 0; y < h; y++) {
        const unsigned char *s = src + (long)y * step * SRC_STRIDE;
        for (int x = 0; x < w; x++) {
            const unsigned char *m = s + ((x * step) & ~1) * 2;
            yuvToRgba(m[1], m[0], m[2], out);
            out += 4;
        }
    }
}

/* Look-up remap for floating views. Entry: macropixel byte offset, bit 1 selects the second luma; -1 is black. */
void Java_com_sgmwstarlight_stardash_Native_remap(Env env, void *cls, void *srcBuf, void *mapBuf, int count, void *outBuf) {
    const unsigned char *src = ADDR(env, srcBuf);
    const int *map = (const int *)ADDR(env, mapBuf);
    unsigned char *out = ADDR(env, outBuf);
    for (int i = 0; i < count; i++, out += 4) {
        int e = map[i];
        if (e < 0) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 255;
            continue;
        }
        const unsigned char *m = src + (e & ~3);
        yuvToRgba(m[(e & 2) ? 3 : 1], m[0], m[2], out);
    }
}

/* Bilinear look-up remap for undistorted floating views. idx: source pixel index (y*2560+x) of the
 * top-left neighbour, or -1 for black. frac: two bytes per output pixel, x and y fractions in 1/256. */
void Java_com_sgmwstarlight_stardash_Native_remapBilinear(Env env, void *cls, void *srcBuf, void *idxBuf, void *fracBuf,
                                                          int count, void *outBuf) {
    const unsigned char *src = ADDR(env, srcBuf);
    const int *idx = (const int *)ADDR(env, idxBuf);
    const unsigned char *frac = ADDR(env, fracBuf);
    unsigned char *out = ADDR(env, outBuf);
    for (int i = 0; i < count; i++, out += 4, frac += 2) {
        int p = idx[i];
        if (p < 0) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 255;
            continue;
        }
        int x = p % 2560, y = p / 2560;
        const unsigned char *row0 = src + (long)y * SRC_STRIDE, *row1 = row0 + SRC_STRIDE;
        int ax = frac[0], ay = frac[1];
        /* In UYVY every pixel's luma sits at byte 2*x+1. */
        int y00 = row0[2 * x + 1], y01 = row0[2 * x + 3], y10 = row1[2 * x + 1], y11 = row1[2 * x + 3];
        int top = y00 * (256 - ax) + y01 * ax, bottom = y10 * (256 - ax) + y11 * ax;
        int luma = (top * (256 - ay) + bottom * ay + 32768) >> 16;
        const unsigned char *m = row0 + (x & ~1) * 2;
        yuvToRgba(luma, m[0], m[2], out);
    }
}

/* Same as remapBilinear, but first copies the source rectangle the view needs (x0,y0,bw,bh in
 * mosaic pixels) into ordinary cached memory with sequential reads. The shared 360 buffer is
 * uncached, so scattered reads from it are slow while one sequential copy is cheap.
 * idx here is relative to the rectangle: (y-y0)*bw + (x-x0). */
void Java_com_sgmwstarlight_stardash_Native_remapBilinearRegion(Env env, void *cls, void *srcBuf, void *idxBuf,
                                                                void *fracBuf, int count, void *outBuf, int x0,
                                                                int y0, int bw, int bh, void *cacheBuf) {
    const unsigned char *src = ADDR(env, srcBuf);
    const int *idx = (const int *)ADDR(env, idxBuf);
    const unsigned char *frac = ADDR(env, fracBuf);
    unsigned char *out = ADDR(env, outBuf);
    unsigned char *cache = ADDR(env, cacheBuf);
    int rowBytes = bw * 2;
    for (int r = 0; r < bh; r++) {
        const unsigned char *s = src + (long)(y0 + r) * SRC_STRIDE + x0 * 2;
        unsigned char *d = cache + (long)r * rowBytes;
        int i = 0;
        for (; i + 64 <= rowBytes; i += 64) {
            uint8x16x4_t v = vld1q_u8_x4(s + i);
            vst1q_u8_x4(d + i, v);
        }
        for (; i < rowBytes; i++) d[i] = s[i];
    }
    for (int i = 0; i < count; i++, out += 4, frac += 2) {
        int p = idx[i];
        if (p < 0) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 255;
            continue;
        }
        int x = p % bw, y = p / bw;
        const unsigned char *row0 = cache + (long)y * rowBytes, *row1 = row0 + rowBytes;
        int ax = frac[0], ay = frac[1];
        int y00 = row0[2 * x + 1], y01 = row0[2 * x + 3], y10 = row1[2 * x + 1], y11 = row1[2 * x + 3];
        int top = y00 * (256 - ax) + y01 * ax, bottom = y10 * (256 - ax) + y11 * ax;
        int luma = (top * (256 - ay) + bottom * ay + 32768) >> 16;
        /* chroma pair belongs to the even pixel of the absolute x (x0 is kept even) */
        const unsigned char *m = row0 + (x & ~1) * 2;
        yuvToRgba(luma, m[0], m[2], out);
    }
}

/* Fastest variant: idx holds the byte offset of the top-left neighbour inside the copied block
 * ((y-y0)*rowBytes + (x-x0)*2) with bit 0 set when x is odd (so the chroma pair starts 2 bytes earlier).
 * No divisions per pixel. */
static inline unsigned long ticks(void) {
    unsigned long t;
    __asm__ volatile("mrs %0, cntvct_el0" : "=r"(t));
    return t;
}

/* Returns the time spent copying the block, in timer ticks (19.2 MHz on this SoC). */
int Java_com_sgmwstarlight_stardash_Native_remapOffsets(Env env, void *cls, void *srcBuf, void *idxBuf, void *fracBuf,
                                                         int count, void *outBuf, int x0, int y0, int bw, int bh,
                                                         void *cacheBuf) {
    const unsigned char *src = ADDR(env, srcBuf);
    const int *idx = (const int *)ADDR(env, idxBuf);
    const unsigned char *frac = ADDR(env, fracBuf);
    unsigned char *out = ADDR(env, outBuf);
    unsigned char *cache = ADDR(env, cacheBuf);
    int rowBytes = bw * 2;
    unsigned long c0 = ticks();
    for (int r = 0; r < bh; r++) {
        const unsigned char *s = src + (long)(y0 + r) * SRC_STRIDE + x0 * 2;
        unsigned char *d = cache + (long)r * rowBytes;
        int i = 0;
        for (; i + 64 <= rowBytes; i += 64) vst1q_u8_x4(d + i, vld1q_u8_x4(s + i));
        for (; i < rowBytes; i++) d[i] = s[i];
    }
    unsigned long c1 = ticks();
    for (int i = 0; i < count; i++, out += 4, frac += 2) {
        int e = idx[i];
        if (e < 0) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 255;
            continue;
        }
        const unsigned char *p = cache + (e & ~1);
        int ax = frac[0], ay = frac[1];
        int top = p[1] * (256 - ax) + p[3] * ax;
        int bottom = p[rowBytes + 1] * (256 - ax) + p[rowBytes + 3] * ax;
        int luma = (top * (256 - ay) + bottom * ay + 32768) >> 16;
        const unsigned char *m = p - ((e & 1) << 1);
        yuvToRgba(luma, m[0], m[2], out);
    }
    return (int)(c1 - c0);
}
