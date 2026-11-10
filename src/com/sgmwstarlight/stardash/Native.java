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
}
