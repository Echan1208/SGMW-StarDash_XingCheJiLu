package com.sgmwstarlight.stardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
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
            return sideMirror(quadrant, cx, cy, f, k1, k2, r, outward, psi, aIn, aOut, horizon, 0, 0, 0, w, h);
        }

        /**
         * As above, with the user's adjustment from the custom view page: {@code panRight} turns the view
         * toward the screen's right (degrees, the image plane turns with it so nothing gets stretched),
         * {@code shiftUp} moves the window up (degrees, shift lens, so verticals stay vertical) and
         * {@code rotCw} turns the picture clockwise around its centre (degrees), for cars whose 360
         * calibration differs from the one the defaults were picked on.
         */
        static ViewMap sideMirror(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                                  int outward, double psi, double aIn, double aOut, double horizon,
                                  double panRight, double shiftUp, double rotCw, int w, int h) {
            // Screen right is the inner side on the left view (mirror image) and the outer side on the right view.
            Rays rays = sideRays(outward, psi, aIn, aOut, horizon, panRight, shiftUp, rotCw, w, h);
            return project(quadrant, cx, cy, f, k1, k2, r, rays.fwd, rays.uAxis, rays.down, rays.u0, rays.du,
                    rays.v0, rays.dv, rotCw, w, h);
        }

        /** The rays of a {@link #sideMirror} view (same arguments), for measuring where things stand. */
        static Rays sideRays(int outward, double psi, double aIn, double aOut, double horizon,
                             double panRight, double shiftUp, double rotCw, int w, int h) {
            psi += outward * panRight;
            aIn += outward * panRight;
            aOut += outward * panRight;
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
            horizon += Math.tan(Math.toRadians(shiftUp)) / (span * h / w);
            double v0 = -horizon * span * h / w + 0.5 * step;
            return new Rays(outward, fwd, out, down, u0, du, v0, step, rotCw, w, h);
        }

        private static ViewMap project(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                                       double[] fwd, double[] uAxis, double[] down,
                                       double u0, double du, double v0, double dv, int w, int h) {
            return project(quadrant, cx, cy, f, k1, k2, r, fwd, uAxis, down, u0, du, v0, dv, 0, w, h);
        }

        /**
         * Output pixel (x, y) looks along fwd + (u0 + x' du) uAxis + (v0 + y' dv) down, where (x', y') is
         * (x, y) turned about the image centre so the picture appears rotated clockwise by {@code rotCw} degrees.
         */
        private static ViewMap project(int quadrant, double cx, double cy, double f, double k1, double k2, double[][] r,
                                       double[] fwd, double[] uAxis, double[] down,
                                       double u0, double du, double v0, double dv, double rotCw, int w, int h) {
            double cosR = Math.cos(Math.toRadians(rotCw)), sinR = Math.sin(Math.toRadians(rotCw));
            double mx = (w - 1) / 2.0, my = (h - 1) / 2.0;
            ByteBuffer idxBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            ByteBuffer frac = ByteBuffer.allocateDirect(w * h * 2);
            IntBuffer idx = idxBuf.asIntBuffer();
            int qx = (quadrant % 2) * 1280, qy = (quadrant / 2) * 960;
            double maxTheta = Math.toRadians(100);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double xr = mx + cosR * (x - mx) + sinR * (y - my), yr = my - sinR * (x - mx) + cosR * (y - my);
                    double u = u0 + xr * du, v = v0 + yr * dv;
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

        static double[] cross(double[] a, double[] b) {
            return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        static double[] normalize(double[] a) {
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
    /** Side-rear warning; runs only while a left or right view is up. */
    final BlindSpot blindSpot;

    MirrorController(Context context, Config config, VehicleState vehicle, Recorder recorder) {
        this.context = context;
        this.config = config;
        this.vehicle = vehicle;
        this.recorder = recorder;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.blindSpot = new BlindSpot(context, config, vehicle, recorder, this);
    }

    /** Whether the view for {@code side} is on screen (any thread). */
    boolean showing(Side side) {
        return views[side.ordinal()] != null;
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

    /** Exit warning: driver, passenger, rear left, rear right door signals and the view side for each. */
    private static final int[] DOOR_UNLOCKED = {VehicleState.DRV_UNLOCKED, VehicleState.PAS_UNLOCKED,
            VehicleState.RL_UNLOCKED, VehicleState.RR_UNLOCKED};
    private static final int[] DOOR_AJAR = {VehicleState.DRV_AJAR, VehicleState.PAS_AJAR, VehicleState.RL_AJAR,
            VehicleState.RR_AJAR};
    private static final Side[] DOOR_SIDE = {Side.LEFT, Side.RIGHT, Side.LEFT, Side.RIGHT};
    private static final long DOOR_SHOW_MS = 5000;
    private final boolean[] doorUnlocked = new boolean[4], doorAjar = new boolean[4];
    private boolean doorsSeen;
    /** Until when the exit warning holds each side's view up (0 = not holding). */
    private final long[] doorUntil = new long[Side.values().length];

    /**
     * Unlocks seen since {@link #pendingSince}: they are collected for a moment and then judged together,
     * because the four door signals of one unlock may arrive a tick apart.
     */
    private long pendingSince;
    private boolean pendingDriver, pendingGroup;
    private static final long UNLOCK_WINDOW_MS = 300;

    /**
     * Exit warning ("开门杀"): at standstill, the first pull of an inner door handle unlocks without opening.
     * Show the view on that side for 5 s so the side-rear check runs before the door opens; take it down as
     * soon as the door actually opens, or after the 5 s.
     * What unlocks together tells what happened (all checked on the car):
     * - driver handle: only the driver door → left view;
     * - passenger handle or either rear handle: the same three doors (passenger, rear left, rear right), so
     *   which one was pulled cannot be told apart. With someone in the passenger seat it is taken as the
     *   passenger getting out → right view only; with the seat empty it must be a rear passenger → both;
     * - the unlock button or key: all four at once, including the driver door → no warning (the user
     *   unlocking on purpose, not someone about to open a door).
     */
    private void doors(long now) {
        boolean stopped = vehicle.known(VehicleState.SPEED) && vehicle.speed() < 0.5f;
        for (int i = 0; i < 4; i++) {
            boolean unlocked = vehicle.on(DOOR_UNLOCKED[i]), ajar = vehicle.on(DOOR_AJAR[i]);
            Side side = DOOR_SIDE[i];
            if (doorsSeen && unlocked && !doorUnlocked[i] && !ajar && stopped && config.doorWarn()) {
                if (pendingSince == 0) pendingSince = now;
                if (i == 0) pendingDriver = true;
                else pendingGroup = true;
            }
            if (doorsSeen && ajar && !doorAjar[i] && doorUntil[side.ordinal()] > 0) {
                Log.i(TAG, "door " + i + " opened: exit warning " + side + " ends");
                doorUntil[side.ordinal()] = 0;
                previewUntil[side.ordinal()] = 0;
                hide(side);
            }
            doorUnlocked[i] = unlocked;
            doorAjar[i] = ajar;
        }
        doorsSeen = true;
        if (pendingSince > 0 && now - pendingSince >= UNLOCK_WINDOW_MS) {
            if (pendingDriver && pendingGroup) {
                Log.i(TAG, "all four doors unlocked together (unlock button or key): no exit warning");
            } else if (pendingDriver) {
                Log.i(TAG, "driver door unlocked at standstill: exit warning LEFT");
                exitWarning(Side.LEFT, now);
            } else {
                boolean passenger = vehicle.on(VehicleState.PAS_SEATED);
                Log.i(TAG, "passenger/rear doors unlocked at standstill, passenger seat "
                        + (passenger ? "taken: exit warning RIGHT" : "empty: exit warning LEFT + RIGHT"));
                exitWarning(Side.RIGHT, now);
                if (!passenger) exitWarning(Side.LEFT, now);
            }
            pendingSince = 0;
            pendingDriver = pendingGroup = false;
        }
        for (Side side : new Side[]{Side.LEFT, Side.RIGHT}) {
            long until = doorUntil[side.ordinal()];
            if (until == 0 || now < until) continue;
            doorUntil[side.ordinal()] = 0;
            boolean signal = config.ruleTurn() && (side == Side.LEFT ? vehicle.leftTurn() : vehicle.rightTurn());
            if (!signal) ruleHide(side);
        }
    }

    private void exitWarning(Side side, long now) {
        doorUntil[side.ordinal()] = now + DOOR_SHOW_MS;
        preview(side, DOOR_SHOW_MS);
    }

    private void evaluate() {
        if (!config.mirror() || !vehicle.known(VehicleState.GEAR)) {
            for (Side s : Side.values()) ruleHide(s);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        doors(now);
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

    /** Window scale for size 0 small, 1 medium, 2 large. */
    static final float[] SIZE_SCALES = {0.75f, 1f, 1.3f};

    static int windowWidth(Side side, float scale) {
        return Math.round((side == Side.REAR ? 640 : 480) * scale) & ~1;
    }

    static int windowHeight(Side side, float scale) {
        return Math.round(360 * scale) & ~1;
    }

    static int sizeOf(Config config, Side side) {
        int s = side == Side.REAR ? config.mirrorSize() : config.sideSize(side.name());
        return Math.max(0, Math.min(2, s));
    }

    /** Where a side view sits when the user has not moved it: left edge beside the side bar, or the right edge. */
    static int[] defaultPos(Side side, int winW) {
        return new int[]{side == Side.LEFT ? LEFT_X : SCREEN_W - EDGE - winW, TOP_Y};
    }

    /** Top-left of a side view of this size: the saved spot, kept fully on screen, or the default. */
    static int[] sidePos(Config config, Side side, int winW, int winH) {
        int[] p = config.sidePos(side.name());
        if (p[0] < 0 || p[1] < 0) return defaultPos(side, winW);
        return new int[]{Math.max(0, Math.min(SCREEN_W - winW, p[0])), Math.max(0, Math.min(SCREEN_H - winH, p[1]))};
    }

    private ViewMap mapFor(Side side, float scale) {
        int w = Math.round(windowWidth(side, scale) * RENDER_SCALE) & ~1;
        int h = Math.round(windowHeight(side, scale) * RENDER_SCALE) & ~1;
        if (side == Side.REAR) { // flat view straight back from the rear camera, mirrored like an interior mirror
            return ViewMap.fisheye(1, 669.451, 479.000, 340.667, 0.0576043, -0.0070888, REAR_CAMERA_AXES,
                    1, 0, 5, 100, w, h, true);
        }
        float[] t = config.sideTune(side.name());
        return sideMap(side, t[0], t[1], t[2], w, h);
    }

    /**
     * Left or right door-mirror view: level shift-lens views where straight back sits about 15% in from
     * the inner edge (so the body takes about that much of the width), plus the user's adjustment.
     * The framing depends only on the aspect ratio, not on w and h themselves.
     */
    static ViewMap sideMap(Side side, double panRight, double shiftUp, double rotCw, int w, int h) {
        if (side == Side.LEFT) {
            return ViewMap.sideMirror(2, 623.536, 472.016, 328.326, 0.0921645, -0.0136198, LEFT_CAMERA_AXES,
                    -1, SIDE_PSI, SIDE_IN, SIDE_OUT, SIDE_HORIZON, panRight, shiftUp, rotCw, w, h);
        }
        return ViewMap.sideMirror(3, 687.566, 467.631, 335.879, 0.0594832, -0.0073995, RIGHT_CAMERA_AXES,
                1, SIDE_PSI, SIDE_IN, SIDE_OUT, SIDE_HORIZON, panRight, shiftUp, rotCw, w, h);
    }

    /** The rays behind a side view of this size, matching {@link #sideMap} with the same adjustment. */
    static Rays sideRays(Side side, double panRight, double shiftUp, double rotCw, int w, int h) {
        return ViewMap.sideRays(side == Side.LEFT ? -1 : 1, SIDE_PSI, SIDE_IN, SIDE_OUT, SIDE_HORIZON,
                panRight, shiftUp, rotCw, w, h);
    }

    /**
     * The viewing ray of every pixel of a view, in vehicle coordinates (X right, Y up, Z front, from the
     * camera), so a pixel can be put on the ground: the side cameras sit about 1 m above it (measured; the
     * floor line of a wall 3.8 m behind the cameras comes out at 3.76-3.91 m).
     */
    static final class Rays {
        final int outward;
        final double[] fwd, uAxis, down;
        final double u0, du, v0, dv;
        private final double cosR, sinR, mx, my;

        Rays(int outward, double[] fwd, double[] uAxis, double[] down, double u0, double du, double v0, double dv,
             double rotCw, int w, int h) {
            this.outward = outward;
            this.fwd = fwd;
            this.uAxis = uAxis;
            this.down = down;
            this.u0 = u0;
            this.du = du;
            this.v0 = v0;
            this.dv = dv;
            cosR = Math.cos(Math.toRadians(rotCw));
            sinR = Math.sin(Math.toRadians(rotCw));
            mx = (w - 1) / 2.0;
            my = (h - 1) / 2.0;
        }

        /**
         * Where pixel (x, y) meets the ground {@code height} m below the camera: {metres behind the camera,
         * metres out from it to the side}, or null when the pixel is at or above the horizon.
         */
        double[] ground(double x, double y, double height) {
            double xr = mx + cosR * (x - mx) + sinR * (y - my), yr = my - sinR * (x - mx) + cosR * (y - my);
            double u = u0 + xr * du, v = v0 + yr * dv;
            double rx = fwd[0] + u * uAxis[0] + v * down[0];
            double ry = fwd[1] + u * uAxis[1] + v * down[1];
            double rz = fwd[2] + u * uAxis[2] + v * down[2];
            if (ry > -1e-4) return null;
            double t = height / -ry;
            return new double[]{-t * rz, outward * t * rx};
        }
    }

    /** Side window aspect (width / height), shared with the custom view page so its preview matches. */
    static final int SIDE_ASPECT_W = 480, SIDE_ASPECT_H = 360;

    private void show(Side side) {
        if (views[side.ordinal()] != null) return;
        float scale = SIZE_SCALES[sizeOf(config, side)];
        ViewMap map = mapFor(side, scale);
        int winW = windowWidth(side, scale), winH = windowHeight(side, scale);
        MirrorView v = new MirrorView(context, map, side == Side.LEFT ? "左后方" : side == Side.RIGHT ? "右后方" : "后方", winW,
                side, blindSpot);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(winW, winH,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (side == Side.REAR) {
            lp.x = 0; // placeRear() sets it
            lp.y = TOP_Y;
        } else {
            int[] p = sidePos(config, side, winW, winH);
            lp.x = p[0];
            lp.y = p[1];
        }
        lp.setTitle("StarDashMirror");
        v.setOnClickListener(x -> hide(side));
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay failed (grant SYSTEM_ALERT_WINDOW?): " + e);
            return;
        }
        views[side.ordinal()] = v;
        if (side != Side.REAR) blindSpot.wake();
        Log.i(TAG, "mirror show " + side);
        placeRear();
        ensureRendering();
    }

    static final int SCREEN_W = 1920, SCREEN_H = 1080, LEFT_X = 104, EDGE = 16, GAP = 16, TOP_Y = 96;

    /** After the custom view page saves a new size or spot: shows the side view there for a few seconds. */
    void reshow(Side side, long ms) {
        hide(side);
        preview(side, ms);
    }

    /**
     * The rear view sits centred, but moves beside a side view that is showing at the same time
     * (turning while braking hard) so the two never overlap.
     */
    private void placeRear() {
        MirrorView rear = views[Side.REAR.ordinal()];
        if (rear == null) return;
        int w = rear.winW;
        int x = (SCREEN_W + 88 - w) / 2;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) rear.getLayoutParams();
        if (lp == null) return;
        // Side views can be moved anywhere; step aside only from one that shares the rear view's rows.
        for (Side s : new Side[]{Side.LEFT, Side.RIGHT}) {
            MirrorView v = views[s.ordinal()];
            WindowManager.LayoutParams o = v == null ? null : (WindowManager.LayoutParams) v.getLayoutParams();
            if (o == null || o.y >= lp.y + lp.height || o.y + o.height <= lp.y) continue;
            boolean onLeft = o.x + o.width / 2 < SCREEN_W / 2;
            if (onLeft) x = Math.max(x, o.x + o.width + GAP);
            else x = Math.min(x, o.x - GAP - w);
        }
        x = Math.max(0, Math.min(SCREEN_W - w, x));
        if (lp.x == x) return;
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
        private final Side side;
        private final BlindSpot bsd;
        private final Paint glow = new Paint();
        private Shader edge;
        private int edgesW, edgesH;

        MirrorView(Context c, ViewMap map, String label, int winW, Side side, BlindSpot bsd) {
            super(c);
            this.winW = winW;
            this.side = side;
            this.bsd = bsd;
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
            BlindSpot.State st = bsd == null ? BlindSpot.State.NONE : bsd.state(side);
            if (st.alert) drawWarning(canvas, st);
        }

        /**
         * The side-rear warning: only the view's outer edge (left edge of the left view, right edge of the
         * right view) glows red, fading inward and pulsing about three times a second.
         */
        private void drawWarning(Canvas canvas, BlindSpot.State st) {
            int w = getWidth(), h = getHeight();
            int band = Math.max(28, w / 6);
            boolean leftEdge = side == Side.LEFT;
            if (edge == null || edgesW != w || edgesH != h) {
                int red = Color.argb(255, 255, 30, 20), none = Color.argb(0, 255, 30, 20);
                edge = leftEdge ? new LinearGradient(0, 0, band, 0, red, none, Shader.TileMode.CLAMP)
                        : new LinearGradient(w, 0, w - band, 0, red, none, Shader.TileMode.CLAMP);
                edgesW = w;
                edgesH = h;
            }
            double phase = (SystemClock.elapsedRealtime() % 330) / 330.0;
            glow.setAlpha((int) (90 + 165 * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase))));
            glow.setShader(edge);
            if (leftEdge) canvas.drawRect(0, 0, band, h, glow);
            else canvas.drawRect(w - band, 0, w, h, glow);
        }
    }
}
