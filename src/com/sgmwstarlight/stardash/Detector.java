package com.sgmwstarlight.stardash;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NanoDet-Plus-m 320 (COCO, ncnn, CPU only) for the side-rear warning. The native side decodes the
 * boxes; non-maximum suppression is done here. Loaded on first use; if the library or model cannot be
 * loaded the warning simply stays off.
 */
final class Detector {
    private static final String TAG = "StarDash";
    private static final int MAX = 200;

    /** person, bicycle, car, motorcycle, bus, truck (COCO ids) in Chinese, for the log and labels. */
    static String name(int cls) {
        switch (cls) {
            case 0: return "行人";
            case 1: return "自行车";
            case 2: return "汽车";
            case 3: return "摩托/电动车";
            case 5: return "大客车";
            case 7: return "货车";
            default: return "目标";
        }
    }

    static final class Box {
        final float x1, y1, x2, y2, score;
        final int cls;

        Box(float x1, float y1, float x2, float y2, float score, int cls) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.score = score;
            this.cls = cls;
        }
    }

    private static boolean loaded, failed;
    /** ncnn reads the weights in place, so this buffer lives as long as the process. */
    private static ByteBuffer param, bin;
    private static final ByteBuffer out = ByteBuffer.allocateDirect(MAX * 6 * 4).order(ByteOrder.nativeOrder());

    private Detector() {
    }

    private static native int load(ByteBuffer param, ByteBuffer bin, int threads, int flags);

    private static native int run(ByteBuffer rgba, int w, int h, ByteBuffer out, int maxBoxes, float thr);

    /** Loads the library and model once; false if that is not possible on this head unit. */
    static synchronized boolean ready(Context c) {
        if (loaded) return true;
        if (failed) return false;
        try {
            // ncnn's OpenMP aborted the whole process while setting up CPU affinity for a second calling
            // thread. ncnn binds its own threads, so OpenMP's affinity is not needed.
            try {
                android.system.Os.setenv("KMP_AFFINITY", "disabled", true);
            } catch (Exception e) {
                Log.w(TAG, "setenv failed: " + e);
            }
            System.loadLibrary("ncnn");
            System.loadLibrary("bsd");
            byte[] p = read(c, "nanodet.param");
            param = ByteBuffer.allocateDirect(p.length + 1);
            param.put(p).put((byte) 0).rewind();
            byte[] b = read(c, "nanodet.bin");
            bin = ByteBuffer.allocateDirect(b.length);
            bin.put(b).rewind();
            // Debug: setprop debug.stardash.bsd "<threads> <flags>" (flags bit 0 = fp16 storage).
            String[] dbg = getprop("debug.stardash.bsd").trim().split("\\s+");
            // One thread: two were barely faster on this CPU (about 150 against 190 ms) and cost a whole core more.
            int threads = 1, flags = 0;
            try {
                if (dbg.length >= 1 && !dbg[0].isEmpty()) threads = Integer.parseInt(dbg[0]);
                if (dbg.length >= 2) flags = Integer.parseInt(dbg[1]);
            } catch (NumberFormatException ignored) {
            }
            int r = load(param, bin, threads, flags);
            if (r != 0) throw new IllegalStateException("ncnn load " + r);
            loaded = true;
            Log.i(TAG, "detector loaded (NanoDet-Plus-m 320, " + (b.length >> 10) + " KB, threads " + threads
                    + ", flags " + flags + ")");
        } catch (Throwable t) {
            failed = true;
            Log.w(TAG, "detector unavailable: " + t);
        }
        return loaded;
    }

    /** Boxes in the image's pixels, best first, after per-class non-maximum suppression. */
    static List<Box> detect(ByteBuffer rgba, int w, int h, float thr) {
        List<Box> all = new ArrayList<>();
        if (!loaded) return all;
        int n;
        synchronized (out) {
            n = run(rgba, w, h, out, MAX, thr);
            FloatBuffer f = out.asFloatBuffer();
            for (int i = 0; i < n; i++) {
                all.add(new Box(f.get(i * 6), f.get(i * 6 + 1), f.get(i * 6 + 2), f.get(i * 6 + 3), f.get(i * 6 + 4),
                        (int) f.get(i * 6 + 5)));
            }
        }
        Collections.sort(all, (a, b) -> Float.compare(b.score, a.score));
        List<Box> keep = new ArrayList<>();
        for (Box b : all) {
            boolean dup = false;
            for (Box k : keep) {
                if (k.cls == b.cls && iou(k, b) > 0.5f) {
                    dup = true;
                    break;
                }
            }
            if (!dup) keep.add(b);
        }
        return keep;
    }

    private static float iou(Box a, Box b) {
        float ix = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1));
        float iy = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
        float inter = ix * iy;
        float u = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter;
        return u <= 0 ? 0 : inter / u;
    }

    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line;
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] read(Context c, String asset) throws IOException {
        try (InputStream in = c.getAssets().open(asset)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toByteArray();
        }
    }
}
