package com.sgmwstarlight.stardash;

import java.nio.ByteBuffer;

/** Pixel kernels in libstardash.so. All buffers must be direct. */
final class Native {
    static {
        System.loadLibrary("stardash");
    }

    private Native() {
    }

    /** Mosaic to NV12 1280x960, then copies {@code barRows} rows of bar (Y block then UV block) below it. */
    static native void frame(ByteBuffer src, ByteBuffer y, int yStride, ByteBuffer uv, int uvStride, ByteBuffer bar, int barRows);

    static native void rgbaToNv12(ByteBuffer rgba, ByteBuffer out, int w, int h);

    /** Returns the number of moving blocks (of 300) in the low 16 bits and mean luma above them. */
    static native int motion(ByteBuffer src, ByteBuffer prev, int noise, int blockMin);

    static native void preview(ByteBuffer src, ByteBuffer rgbaOut, int step);

    static native void remap(ByteBuffer src, ByteBuffer map, int count, ByteBuffer rgbaOut);

    /** idx: top-left source pixel (y*2560+x) or -1; frac: x and y fractions in 1/256 per output pixel. */
    static native void remapBilinear(ByteBuffer src, ByteBuffer idx, ByteBuffer frac, int count, ByteBuffer rgbaOut);

    /** remapBilinear over a source rectangle copied to {@code cache} first; idx is relative to the rectangle. */
    static native void remapBilinearRegion(ByteBuffer src, ByteBuffer idx, ByteBuffer frac, int count, ByteBuffer rgbaOut,
                                           int x0, int y0, int bw, int bh, ByteBuffer cache);

    /** Like remapBilinearRegion, with idx as byte offsets into the copied block (bit 0 = odd x). */
    static native int remapOffsets(ByteBuffer src, ByteBuffer idx, ByteBuffer frac, int count, ByteBuffer rgbaOut,
                                    int x0, int y0, int bw, int bh, ByteBuffer cache);
}
