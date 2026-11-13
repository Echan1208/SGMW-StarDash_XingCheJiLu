package com.sgmwstarlight.stardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Floating camera views driven by six rules: left/right turn signal shows that side, high-speed
 * braking shows the rear, and each hides again. Each view is a flat (lens-corrected) image computed
 * from one fisheye camera with a camera model fitted to the factory 360 calibration.
 */
final class MirrorController {
    private static final String TAG = "StarDash";
    private static final long SIDE_HIDE_DELAY_MS = 2000;

    enum Side { LEFT, RIGHT, REAR }

    /** Rear camera axes (columns x, y, z) in vehicle coordinates, from the camera model fit (3.08 px rms). */
    private static final double[][] REAR_CAMERA_AXES = {
            {-0.9974060, -0.0004314, -0.0719803},
            {0.0193278, -0.9648644, -0.2620366},
            {0.0693382, 0.2627481, -0.9623698}};

    /** Right camera axes (columns x, y, z) in vehicle coordinates, from the camera model fit (1.30 px rms). */
    private static final double[][] RIGHT_CAMERA_AXES = {
            {-0.0157295, -0.6555369, 0.7549993},
            {0.0765473, -0.7536662, -0.6527846},
            {-0.9969419, -0.0475251, -0.0620344}};

    /** Left camera axes (columns x, y, z) in vehicle coordinates, from the camera model fit. */
    private static final double[][] LEFT_CAMERA_AXES = {
            {-0.0360074, 0.7087773, -0.7045127},
            {-0.0161377, -0.7052904, -0.7087348},
            {0.9992212, 0.0141505, -0.0368337}};

    /** Side view framing (degrees, horizon as a fraction of the height), chosen offline. */
    private static final double SIDE_PSI = 10, SIDE_IN = -12, SIDE_OUT = 58, SIDE_HORIZON = 0.30;

    /** Output pixel -> source macropixel offset (bit 1 picks the second luma), -1 for black. */
    static final class ViewMap {
        final int w, h;
        final ByteBuffer map;
        /** Set for bilinear maps: {@link #map} then holds source pixel indexes, this the fractions. */
        final ByteBuffer frac;
        /** Source rectangle (mosaic pixels) for region maps; {@link #map} indexes are relative to it. */
        int rx, ry, rw, rh;
        ByteBuffer cache;

        ViewMap(int w, int h, ByteBuffer map) {
            this(w, h, map, null);
        }

        ViewMap(int w, int h, ByteBuffer map, ByteBuffer frac) {
            this.w = w;
            this.h = h;
            this.map = map;
            this.frac = frac;
        }

        /**
         * A flat (pinhole) view from one fisheye camera. Camera model fitted to the OEM
         * 360 system's image-to-ground table (1310 points, 0.41 px rms): theta_d = theta(1+k1 theta^2+k2 theta^4),
         * r = f theta_d, centre (cx, cy) in the camera's 1280x960 image. {@code r} holds the camera's x, y, z
         * axes as columns, in vehicle coordinates (X right, Y up, Z front). The virtual camera faces the
         * car's rear, turned outward by {@code yaw} and down by {@code pitch}; {@code outward} is -1 on the left.
         */
        static ViewMap fisheye(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                               int outward, double yaw, double pitch, double hfov, int w, int h) {
            return fisheye(quadrant, cx, cy, f, k1, k2, r, outward, yaw, pitch, hfov, w, h, false);
        }

        /** As above; {@code mirror} flips left and right like an interior mirror. */
        static ViewMap fisheye(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                               int outward, double yaw, double pitch, double hfov, int w, int h, boolean mirror) {
            double yw = Math.toRadians(yaw), pt = Math.toRadians(pitch);
            double[] fwd = {outward * Math.sin(yw) * Math.cos(pt), -Math.sin(pt), -Math.cos(yw) * Math.cos(pt)};
            double[] right = normalize(cross(new double[]{0, 1, 0}, fwd));
            double[] down = cross(right, fwd);
            double fx = (w / 2.0) / Math.tan(Math.toRadians(hfov) / 2);
            double u0 = mirror ? (w / 2.0 - 0.5) / fx : (0.5 - w / 2.0) / fx;
            return project(quadrant, cx, cy, f, k1, k2, r, fwd, right, down,
                    u0, (mirror ? -1 : 1) / fx, (0.5 - h / 2.0) / fx, 1 / fx, w, h);
        }

        /**
         * Door-mirror view from a side camera through a shift lens: the image plane stays vertical and faces
         * the car's rear turned outward by {@code psi}, and the window is shifted instead of the camera being
         * tilted, so upright things stay upright and lines across the road stay level. It covers
         * {@code aIn} (inward, negative) to {@code aOut} degrees from straight back, with the horizon at
         * {@code horizon} of the height from the top. The car body ends up on the inner edge, as in a door mirror.
         */
        static ViewMap sideMirror(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                                  int outward, double psi, double aIn, double aOut, double horizon, int w, int h) {
            double p = Math.toRadians(psi);
            double[] fwd = {outward * Math.sin(p), 0, -Math.cos(p)};
            double[] right = normalize(cross(new double[]{0, 1, 0}, fwd));
            double[] down = cross(right, fwd);
            double[] out = {-outward * right[0], -outward * right[1], -outward * right[2]};
            double uIn = Math.tan(Math.toRadians(aIn - psi)), uOut = Math.tan(Math.toRadians(aOut - psi));
            double span = uOut - uIn, step = span / w;
            // Left view runs outer edge to inner edge, right view inner to outer.
            double u0 = outward < 0 ? uOut - 0.5 * step : uIn + 0.5 * step;
            double du = outward < 0 ? -step : step;
            double v0 = -horizon * span * h / w + 0.5 * step;
            return project(quadrant, cx, cy, f, k1, k2, r, fwd, out, down, u0, du, v0, step, w, h);
        }

        /** Output pixel (x, y) looks along fwd + (u0 + x du) uAxis + (v0 + y dv) down. */
        private static ViewMap project(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                                       double[] fwd, double[] uAxis, double[] down,
                                       double u0, double du, double v0, double dv, int w, int h) {
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            ByteBuffer frac = ByteBuffer.allocateDirect(w * h * 2);
            IntBuffer idx = idxBuf.asIntBuffer();
            int qx = (quadrant % 2) * 1280, qy = (quadrant / 2) * 960;
            double maxTheta = Math.toRadians(100);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double u = u0 + x * du, v = v0 + y * dv;
                    double[] ray = {fwd[0] + u * uAxis[0] + v * down[0], fwd[1] + u * uAxis[1] + v * down[1],
                            fwd[2] + u * uAxis[2] + v * down[2]};
                    // World ray -> camera coordinates (columns of r are the camera axes).
                    double cxr = ray[0] * r[0][0] + ray[1] * r[1][0] + ray[2] * r[2][0];
                    double cyr = ray[0] * r[0][1] + ray[1] * r[1][1] + ray[2] * r[2][1];
                    double czr = ray[0] * r[0][2] + ray[1] * r[1][2] + ray[2] * r[2][2];
                    double rho = Math.hypot(cxr, cyr);
                    double theta = Math.atan2(rho, czr);
                    double td = theta * (1 + k1 * theta * theta + k2 * theta * theta * theta * theta);
                    double rr = f * td;
                    double px = cx + (rho < 1e-9 ? 0 : rr * cxr / rho);
                    double py = cy + (rho < 1e-9 ? 0 : rr * cyr / rho);
                    if (theta > maxTheta || px < 0 || py < 0 || px >= 1279 || py >= 959) {
                        idx.put(-1);
                        frac.put((byte) 0).put((byte) 0);
                        continue;
                    }
                    int ix = (int) px, iy = (int) py;
                    idx.put((qy + iy) * 2560 + qx + ix);
                    frac.put((byte) (int) ((px - ix) * 255)).put((byte) (int) ((py - iy) * 255));
                }
            }
            frac.rewind();
            // Find the source rectangle the view reads (plus the bilinear neighbour), then make the
            // indexes relative to it so the native side can copy just that block once per frame.
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int i = 0; i < w * h; i++) {
                int p = idx.get(i);
                if (p < 0) continue;
                int px = p % 2560, py = p / 2560;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px + 1);
                minY = Math.min(minY, py);
                maxY = Math.max(maxY, py + 1);
            }
            ViewMap vm = new ViewMap(w, h, idxBuf, frac);
            if (maxX < 0) return vm;
            minX &= ~1; // keep chroma pairs aligned
            // maxX/maxY already include the +1 neighbour, so this never reads past the mosaic's edge.
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            for (int i = 0; i < w * h; i++) {
                int p = idx.get(i);
                if (p < 0) continue;
                int sx = p % 2560 - minX, sy = p / 2560 - minY;
                idx.put(i, sy * bw * 2 + sx * 2 + (sx & 1)); // byte offset, bit 0 = odd pixel
            }
            Log.i(TAG, "view block " + bw + "x" + bh + " from (" + minX + "," + minY + ")");
            vm.rx = minX;
            vm.ry = minY;
            vm.rw = bw;
            vm.rh = bh;
            vm.cache = ByteBuffer.allocateDirect(bw * bh * 2);
            return vm;
        }

        private static double[] cross(double[] a, double[] b) {
            return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        private static double[] normalize(double[] a) {
            double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            return new double[]{a[0] / n, a[1] / n, a[2] / n};
        }

        /** Crop of one camera quadrant (0 front, 1 rear, 2 left, 3 right), optional horizontal flip. */
        static ViewMap crop(int quadrant, float x0, float y0, float x1, float y1, int w, int h, boolean flip) {
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            IntBuffer ib = buf.asIntBuffer();
            int qx = (quadrant % 2) * 1280, qy = (quadrant / 2) * 960;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float u = (flip ? (w - 1 - x) : x) + 0.5f, v = y + 0.5f;
                    int sx = qx + (int) (x0 + u * (x1 - x0) / w);
                    int sy = qy + (int) (y0 + v * (y1 - y0) / h);
                    int offset = (sy * 2560 + (sx & ~1)) * 2;
                    ib.put(offset | ((sx & 1) << 1));
                }
            }
            return new ViewMap(w, h, buf);
        }
    }

    private final Context context;
    private final Config config;
    private final VehicleState vehicle;
    private final Recorder recorder;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MirrorView[] views = new MirrorView[3];
    private long sideOffSinceMs = -1;
    private volatile boolean rendering;
    private Thread renderThread;

    MirrorController(Context context, Config config, VehicleState vehicle, Recorder recorder) {
        this.context = context;
        this.config = config;
        this.vehicle = vehicle;
        this.recorder = recorder;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            evaluate();
            main.postDelayed(this, 100);
        }
    };

    void start() {
        main.post(tick);
    }

    void stop() {
        main.removeCallbacks(tick);
        for (Side s : Side.values()) hide(s);
    }

    /** Until when (elapsedRealtime) a settings-page preview keeps each view up despite the rules. */
    private final long[] previewUntil = new long[Side.values().length];

    /** For the settings page: show a view for a few seconds to check its framing. */
    void preview(Side side) {
        preview(side, 5000);
    }

    /** Shows a view for {@code ms} (also used from adb to measure the cost of a view). */
    void preview(Side side, long ms) {
        previewUntil[side.ordinal()] = SystemClock.elapsedRealtime() + ms;
        show(side);
        // After the preview the rules decide again (they hide it unless its signal is on).
    }

    /** Hide requested by a rule; a running preview wins. */
    private void ruleHide(Side side) {
        if (SystemClock.elapsedRealtime() < previewUntil[side.ordinal()]) return;
        hide(side);
    }

    private void evaluate() {
        if (!config.mirror() || !vehicle.known(VehicleState.GEAR)) {
            for (Side s : Side.values()) ruleHide(s);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (config.ruleTurn()) {
            boolean left = vehicle.leftTurn(), right = vehicle.rightTurn();
            if (left && !right) {
                show(Side.LEFT);
                ruleHide(Side.RIGHT);
                sideOffSinceMs = -1;
            } else if (right && !left) {
                show(Side.RIGHT);
                ruleHide(Side.LEFT);
                sideOffSinceMs = -1;
            } else {
                // Both off (or hazard lights): hide after a short delay.
                if (sideOffSinceMs < 0) sideOffSinceMs = now;
                if (now - sideOffSinceMs >= SIDE_HIDE_DELAY_MS || (left && right)) {
                    ruleHide(Side.LEFT);
                    ruleHide(Side.RIGHT);
                }
            }
        }
        if (config.ruleBrake()) {
            float speed = vehicle.speed();
            boolean brake = vehicle.brake();
            if (brake && speed > config.brakeShowKmh()) show(Side.REAR);
            else if (views[Side.REAR.ordinal()] != null && (!brake || speed < config.brakeHideKmh())) ruleHide(Side.REAR);
        }
    }

    /** The flat image is computed at this fraction of the window size and scaled up by the GPU (saves ~40 % CPU). */
    private static final float RENDER_SCALE = 0.75f;

    private static int windowWidth(Side side, float scale) {
        return Math.round((side == Side.REAR ? 640 : 480) * scale) & ~1;
    }

    private static int windowHeight(Side side, float scale) {
        return Math.round(360 * scale) & ~1;
    }

    private ViewMap mapFor(Side side, float scale) {
        int w = Math.round(windowWidth(side, scale) * RENDER_SCALE) & ~1;
        int h = Math.round(windowHeight(side, scale) * RENDER_SCALE) & ~1;
        switch (side) {
            // Side views: level shift-lens door-mirror views; straight back sits about 15% in from the inner
            // edge, so the body takes about that much of the width.
            case LEFT:
                return ViewMap.sideMirror(2, 623.536, 472.016, 328.326, 0.0921645, -0.0136198, LEFT_CAMERA_AXES,
                        -1, SIDE_PSI, SIDE_IN, SIDE_OUT, SIDE_HORIZON, w, h);
            case RIGHT:
                return ViewMap.sideMirror(3, 687.566, 467.631, 335.879, 0.0594832, -0.0073995, RIGHT_CAMERA_AXES,
                        1, SIDE_PSI, SIDE_IN, SIDE_OUT, SIDE_HORIZON, w, h);
            default: // flat view straight back from the rear camera, mirrored like an interior mirror
                return ViewMap.fisheye(1, 669.451, 479.000, 340.667, 0.0576043, -0.0070888, REAR_CAMERA_AXES,
                        1, 0, 5, 100, w, h, true);
        }
    }

    private void show(Side side) {
        if (views[side.ordinal()] != null) return;
        float scale = new float[]{0.75f, 1f, 1.3f}[Math.max(0, Math.min(2, config.mirrorSize()))];
        ViewMap map = mapFor(side, scale);
        int winW = windowWidth(side, scale), winH = windowHeight(side, scale);
        MirrorView v = new MirrorView(context, map, side == Side.LEFT ? "左后方" : side == Side.RIGHT ? "右后方" : "后方", winW);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(winW, winH,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = 96;
        lp.x = side == Side.LEFT ? LEFT_X : side == Side.RIGHT ? SCREEN_W - EDGE - winW : 0;
        lp.setTitle("StarDashMirror");
        v.setOnClickListener(x -> hide(side));
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay failed (grant SYSTEM_ALERT_WINDOW?): " + e);
            return;
        }
        views[side.ordinal()] = v;
        Log.i(TAG, "mirror show " + side);
        placeRear();
        ensureRendering();
    }

    private static final int SCREEN_W = 1920, LEFT_X = 104, EDGE = 16, GAP = 16;

    /**
     * The rear view sits centred, but moves beside a side view that is showing at the same time
     * (turning while braking hard) so the two never overlap.
     */
    private void placeRear() {
        MirrorView rear = views[Side.REAR.ordinal()];
        if (rear == null) return;
        int w = rear.winW;
        int x = (SCREEN_W + 88 - w) / 2;
        MirrorView left = views[Side.LEFT.ordinal()], right = views[Side.RIGHT.ordinal()];
        if (left != null) x = Math.max(x, LEFT_X + left.winW + GAP);
        if (right != null) x = Math.min(x, SCREEN_W - EDGE - right.winW - GAP - w);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) rear.getLayoutParams();
        if (lp == null || lp.x == x) return;
        lp.x = x;
        try {
            wm.updateViewLayout(rear, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private void hide(Side side) {
        MirrorView v = views[side.ordinal()];
        if (v == null) return;
        views[side.ordinal()] = null;
        Log.i(TAG, "mirror hide " + side);
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
        }
        placeRear();
    }

    boolean anyVisible() {
        for (MirrorView v : views) if (v != null) return true;
        return false;
    }

    private synchronized void ensureRendering() {
        if (rendering) return;
        rendering = true;
        renderThread = new Thread(() -> {
            long lastSeq = -1, statStart = SystemClock.elapsedRealtime(), busyNs = 0;
            int drawn = 0;
            while (true) {
                // Redraw once per new 360 frame (25 a second), never faster.
                long seq = recorder.frameSeq;
                if (seq == lastSeq) {
                    SystemClock.sleep(3);
                    if (!anyVisible()) break;
                    continue;
                }
                lastSeq = seq;
                MirrorView[] snapshot = views.clone();
                boolean any = false;
                ByteBuffer frame = recorder.latestFrame;
                long t0 = System.nanoTime();
                for (MirrorView v : snapshot) {
                    if (v == null) continue;
                    any = true;
                    if (frame != null) v.render(frame);
                }
                busyNs += System.nanoTime() - t0;
                if (!any) break;
                drawn++;
                long now = SystemClock.elapsedRealtime();
                if (now - statStart >= 5000) {
                    Log.i(TAG, String.format(java.util.Locale.US, "mirror render %.1f fps, %.2f ms per frame",
                            drawn * 1000f / (now - statStart), busyNs / 1e6 / Math.max(1, drawn)));
                    statStart = now;
                    busyNs = 0;
                    drawn = 0;
                }
            }
            synchronized (MirrorController.this) {
                rendering = false;
            }
        }, "Mirror");
        renderThread.start();
    }

    /** Draws the latest remapped frame with rounded corners and a small label. */
    private static final class MirrorView extends View {
        private final ViewMap map;
        private final String label;
        private final ByteBuffer rgba;
        private final Bitmap[] bitmaps = new Bitmap[2];
        private volatile int ready = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();

        /** Window width on screen; the bitmap is smaller and is scaled up when drawn. */
        final int winW;

        MirrorView(Context c, ViewMap map, String label, int winW) {
            super(c);
            this.winW = winW;
            this.map = map;
            this.label = label;
            rgba = ByteBuffer.allocateDirect(map.w * map.h * 4);
            bitmaps[0] = Bitmap.createBitmap(map.w, map.h, Bitmap.Config.ARGB_8888);
            bitmaps[1] = Bitmap.createBitmap(map.w, map.h, Bitmap.Config.ARGB_8888);
            text.setColor(Color.WHITE);
            text.setTextSize(20);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setShadowLayer(4, 0, 1, Color.BLACK);
        }

        long nativeNs, copyNs, invalidateNs, blockNs, renders;

        void render(ByteBuffer frame) {
            int target = ready == 0 ? 1 : 0;
            long t0 = System.nanoTime();
            rgba.rewind();
            if (map.cache != null) {
                blockNs += Native.remapOffsets(frame.duplicate(), map.map, map.frac, map.w * map.h, rgba,
                        map.rx, map.ry, map.rw, map.rh, map.cache) * 1_000_000_000L / 19_200_000L;
            } else if (map.frac != null) Native.remapBilinear(frame.duplicate(), map.map, map.frac, map.w * map.h, rgba);
            else Native.remap(frame.duplicate(), map.map, map.w * map.h, rgba);
            long t1 = System.nanoTime();
            rgba.rewind();
            bitmaps[target].copyPixelsFromBuffer(rgba);
            long t2 = System.nanoTime();
            ready = target;
            postInvalidate();
            long t3 = System.nanoTime();
            nativeNs += t1 - t0;
            copyNs += t2 - t1;
            invalidateNs += t3 - t2;
            if (++renders % 125 == 0) {
                Log.i(TAG, String.format(java.util.Locale.US, "mirror %s native %.2f ms (block copy %.2f), bitmap copy %.2f ms, invalidate %.2f ms",
                        label, nativeNs / 1e6 / 125, blockNs / 1e6 / 125, copyNs / 1e6 / 125, invalidateNs / 1e6 / 125));
                nativeNs = copyNs = invalidateNs = blockNs = 0;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int i = ready;
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), 14, 14, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            if (i >= 0) canvas.drawBitmap(bitmaps[i], null, new Rect(0, 0, getWidth(), getHeight()), paint);
            else canvas.drawColor(Color.BLACK);
            // Side labels sit over the dark blind corner next to the car: left view top-right, others top-left.
            float x = "左后方".equals(label) ? getWidth() - 14 - text.measureText(label) : 14;
            canvas.drawText(label, x, 30, text);
        }
    }
}
