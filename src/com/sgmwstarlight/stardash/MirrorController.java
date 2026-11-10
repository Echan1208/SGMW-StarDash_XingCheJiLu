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
 * Floating camera views driven by six rules:
 * left/right turn signal shows that side, high-speed braking shows the rear, and each hides again.
 * Views are cut straight from the fisheye images (no lens correction); the crop table can later be
 * replaced by a proper look-up table without touching the rest.
 */
final class MirrorController {
    private static final String TAG = "StarDash";
    private static final long SIDE_HIDE_DELAY_MS = 2000;

    enum Side { LEFT, RIGHT, REAR }

    /** Output pixel -> source macropixel offset (bit 1 picks the second luma), -1 for black. */
    static final class ViewMap {
        final int w, h;
        final ByteBuffer map;

        ViewMap(int w, int h, ByteBuffer map) {
            this.w = w;
            this.h = h;
            this.map = map;
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

    /** For the settings page: show a view for a few seconds to check its framing. */
    void preview(Side side) {
        show(side);
        main.postDelayed(() -> hide(side), 5000);
    }

    private void evaluate() {
        if (!config.mirror() || !vehicle.known(VehicleState.GEAR)) {
            for (Side s : Side.values()) hide(s);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (config.ruleTurn()) {
            boolean left = vehicle.leftTurn(), right = vehicle.rightTurn();
            if (left && !right) {
                show(Side.LEFT);
                hide(Side.RIGHT);
                sideOffSinceMs = -1;
            } else if (right && !left) {
                show(Side.RIGHT);
                hide(Side.LEFT);
                sideOffSinceMs = -1;
            } else {
                // Both off (or hazard lights): hide after a short delay.
                if (sideOffSinceMs < 0) sideOffSinceMs = now;
                if (now - sideOffSinceMs >= SIDE_HIDE_DELAY_MS || (left && right)) {
                    hide(Side.LEFT);
                    hide(Side.RIGHT);
                }
            }
        }
        if (config.ruleBrake()) {
            float speed = vehicle.speed();
            boolean brake = vehicle.brake();
            if (brake && speed > config.brakeShowKmh()) show(Side.REAR);
            else if (views[Side.REAR.ordinal()] != null && (!brake || speed < config.brakeHideKmh())) hide(Side.REAR);
        }
    }

    private ViewMap mapFor(Side side, float scale) {
        int w = Math.round((side == Side.REAR ? 640 : 480) * scale) & ~1;
        int h = Math.round((side == Side.REAR ? 360 : 360) * scale) & ~1;
        switch (side) {
            case LEFT: // left camera: the rear of the car is on the image's left
                return ViewMap.crop(2, 0, 300, 860, 945, w, h, false);
            case RIGHT: // right camera: the rear of the car is on the image's right
                return ViewMap.crop(3, 420, 300, 1280, 945, w, h, false);
            default: // rear camera centre, mirrored like an interior mirror
                return ViewMap.crop(1, 140, 60, 1140, 622, w, h, true);
        }
    }

    private void show(Side side) {
        if (views[side.ordinal()] != null) return;
        float scale = new float[]{0.75f, 1f, 1.3f}[Math.max(0, Math.min(2, config.mirrorSize()))];
        ViewMap map = mapFor(side, scale);
        MirrorView v = new MirrorView(context, map, side == Side.LEFT ? "左后方" : side == Side.RIGHT ? "右后方" : "后方");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(map.w, map.h,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = 96;
        lp.x = side == Side.LEFT ? 104 : side == Side.RIGHT ? 1920 - 16 - map.w : (1920 + 88 - map.w) / 2;
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
        ensureRendering();
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
    }

    boolean anyVisible() {
        for (MirrorView v : views) if (v != null) return true;
        return false;
    }

    private synchronized void ensureRendering() {
        if (rendering) return;
        rendering = true;
        renderThread = new Thread(() -> {
            while (true) {
                MirrorView[] snapshot = views.clone();
                boolean any = false;
                ByteBuffer frame = recorder.latestFrame;
                for (MirrorView v : snapshot) {
                    if (v == null) continue;
                    any = true;
                    if (frame != null) v.render(frame);
                }
                if (!any) break;
                SystemClock.sleep(66); // about 15 frames a second
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

        MirrorView(Context c, ViewMap map, String label) {
            super(c);
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

        void render(ByteBuffer frame) {
            int target = ready == 0 ? 1 : 0;
            rgba.rewind();
            Native.remap(frame.duplicate(), map.map, map.w * map.h, rgba);
            rgba.rewind();
            bitmaps[target].copyPixelsFromBuffer(rgba);
            ready = target;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int i = ready;
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), 14, 14, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            if (i >= 0) canvas.drawBitmap(bitmaps[i], null, new Rect(0, 0, getWidth(), getHeight()), paint);
            else canvas.drawColor(Color.BLACK);
            canvas.drawText(label, 14, 30, text);
        }
    }
}
