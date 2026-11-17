package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Custom floating view page: live preview of the left or right door-mirror view with on-picture arrows
 * and rotate buttons. Each car's 360 calibration differs a little, so the default framing may sit off
 * on someone else's car; this lets them pan, shift and level it. Changes are saved as they are made and
 * apply the next time the floating view pops up.
 */
public final class MirrorTuneActivity extends Activity {
    private static final float PAN_STEP = 1f, UP_STEP = 1f, ROT_STEP = 0.5f;
    private static final float PAN_MAX = 30f, UP_MAX = 20f, ROT_MAX = 15f;
    /** Preview render size; the framing depends only on the aspect, so it matches the floating view. */
    private static final int W = MirrorController.SIDE_ASPECT_W, H = MirrorController.SIDE_ASPECT_H;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService builder = Executors.newSingleThreadExecutor();
    private Config config;
    private MirrorController.Side side = MirrorController.Side.LEFT;
    private float pan, up, rot;

    private PreviewView preview;
    private TextView title, values, switchBtn;
    private volatile MirrorController.ViewMap map;
    /** Bumped on every change so an older, slower map build never replaces a newer one. */
    private volatile int wanted;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        config = new Config(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 20, 32, 20);

        // Top bar: back, title, current adjustment.
        FrameLayout top = new FrameLayout(this);
        PlayerActivity.IconView back = new PlayerActivity.IconView(this, PlayerActivity.IconView.BACK);
        back.setBackground(Ui.round(Ui.CARD2, 30));
        back.setOnClickListener(v -> finish());
        top.addView(back, new FrameLayout.LayoutParams(60, 60, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        title = Ui.text(this, "", 24, Ui.TEXT, true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tlp.leftMargin = 84;
        top.addView(title, tlp);
        values = Ui.text(this, "", 17, Ui.SUB, false);
        top.addView(values, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        root.addView(top, Ui.lp(PREVIEW_W, -2));

        // Preview with the semi-transparent controls on top of it.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.round(Color.BLACK, 18));
        frame.setClipToOutline(true);
        preview = new PreviewView(this);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(arrowPad(), corner(Gravity.BOTTOM | Gravity.LEFT));
        frame.addView(rotateButtons(), corner(Gravity.BOTTOM | Gravity.RIGHT));
        root.addView(frame, Ui.margins(Ui.lp(PREVIEW_W, PREVIEW_H), 0, 16, 0, 0));

        TextView hint = Ui.text(this, "方向键移动视角，旋转键把地面调水平；调整会自动保存，下次弹出悬浮画面时生效", 15, Ui.SUB, false);
        root.addView(hint, Ui.margins(Ui.lp(-2, -2), 0, 12, 0, 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        switchBtn = Ui.button(this, "", Ui.CARD2, Ui.TEXT);
        switchBtn.setOnClickListener(v -> {
            setSide(side == MirrorController.Side.LEFT ? MirrorController.Side.RIGHT : MirrorController.Side.LEFT);
        });
        TextView reset = Ui.button(this, "恢复默认", Ui.CARD2, Ui.AMBER);
        reset.setOnClickListener(v -> {
            pan = up = rot = 0;
            changed();
        });
        bottom.addView(switchBtn, Ui.margins(Ui.lp(300, -2), 0, 0, 20, 0));
        bottom.addView(reset, Ui.lp(300, -2));
        root.addView(bottom, Ui.margins(Ui.lp(-2, -2), 0, 16, 0, 0));

        setContentView(root);
        setSide(MirrorController.Side.LEFT);
    }

    private static final int PREVIEW_W = 880, PREVIEW_H = PREVIEW_W * H / W;

    private static FrameLayout.LayoutParams corner(int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
        p.setMargins(20, 20, 20, 20);
        return p;
    }

    private View arrowPad() {
        // A 3 x 3 grid with the four arrows on the edges.
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        int[][] layout = {{-1, TuneIcon.UP, -1}, {TuneIcon.LEFT, -1, TuneIcon.RIGHT}, {-1, TuneIcon.DOWN, -1}};
        for (int[] row : layout) {
            LinearLayout r = new LinearLayout(this);
            for (int kind : row) {
                View cell = kind < 0 ? new View(this) : button(kind);
                r.addView(cell, Ui.margins(Ui.lp(BTN, BTN), 3, 3, 3, 3));
            }
            pad.addView(r);
        }
        return pad;
    }

    private View rotateButtons() {
        LinearLayout r = new LinearLayout(this);
        r.addView(button(TuneIcon.CCW), Ui.margins(Ui.lp(BTN, BTN), 3, 3, 3, 3));
        r.addView(button(TuneIcon.CW), Ui.margins(Ui.lp(BTN, BTN), 3, 3, 3, 3));
        return r;
    }

    private static final int BTN = 76;

    /** A round, semi-transparent button that repeats while held. */
    private View button(int kind) {
        TuneIcon b = new TuneIcon(this, kind);
        b.setBackground(Ui.round(Color.argb(120, 0, 0, 0), BTN / 2f));
        Runnable step = () -> apply(kind);
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            step.run();
            handler.postDelayed(repeat[0], 120);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackground(Ui.round(Color.argb(190, 255, 255, 255), BTN / 2f));
                    ((TuneIcon) v).setDark(true);
                    step.run();
                    handler.postDelayed(repeat[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackground(Ui.round(Color.argb(120, 0, 0, 0), BTN / 2f));
                    ((TuneIcon) v).setDark(false);
                    handler.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void apply(int kind) {
        switch (kind) {
            case TuneIcon.LEFT: pan = clamp(pan - PAN_STEP, PAN_MAX); break;
            case TuneIcon.RIGHT: pan = clamp(pan + PAN_STEP, PAN_MAX); break;
            case TuneIcon.UP: up = clamp(up + UP_STEP, UP_MAX); break;
            case TuneIcon.DOWN: up = clamp(up - UP_STEP, UP_MAX); break;
            case TuneIcon.CW: rot = clamp(rot + ROT_STEP, ROT_MAX); break;
            default: rot = clamp(rot - ROT_STEP, ROT_MAX); break;
        }
        changed();
    }

    private static float clamp(float v, float max) {
        return Math.max(-max, Math.min(max, Math.round(v * 2) / 2f));
    }

    private void setSide(MirrorController.Side s) {
        side = s;
        float[] t = config.sideTune(s.name());
        pan = t[0];
        up = t[1];
        rot = t[2];
        boolean left = s == MirrorController.Side.LEFT;
        title.setText("自定义悬浮画面 · " + (left ? "左后方" : "右后方"));
        switchBtn.setText(left ? "切换至右画面" : "切换至左画面");
        preview.label = left ? "左后方" : "右后方";
        map = null;
        preview.clear();
        rebuild();
    }

    /** Saves the adjustment and rebuilds the preview map in the background. */
    private void changed() {
        config.putSideTune(side.name(), pan, up, rot);
        rebuild();
    }

    private void rebuild() {
        values.setText(describe());
        int id = ++wanted;
        MirrorController.Side s = side;
        float p = pan, u = up, r = rot;
        builder.execute(() -> {
            if (id != wanted) return; // a newer change is already queued
            MirrorController.ViewMap m = MirrorController.sideMap(s, p, u, r, W, H);
            if (id == wanted) map = m;
        });
    }

    private String describe() {
        String h = pan == 0 ? "左右 0°" : (pan > 0 ? "向右 " : "向左 ") + fmt(Math.abs(pan)) + "°";
        String v = up == 0 ? "上下 0°" : (up > 0 ? "向上 " : "向下 ") + fmt(Math.abs(up)) + "°";
        String r = rot == 0 ? "旋转 0°" : (rot > 0 ? "顺时针 " : "逆时针 ") + fmt(Math.abs(rot)) + "°";
        return h + "   " + v + "   " + r;
    }

    private static String fmt(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        new Thread(this::renderLoop, "MirrorTune").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        builder.shutdownNow();
        super.onDestroy();
    }

    /** Remaps each new 360 frame into the preview, like the floating view does. */
    private void renderLoop() {
        ByteBuffer rgba = ByteBuffer.allocateDirect(W * H * 4);
        long lastSeq = -1;
        while (running) {
            RecorderService svc = RecorderService.instance;
            Recorder rec = svc == null ? null : svc.recorder;
            MirrorController.ViewMap m = map;
            ByteBuffer frame = rec == null ? null : rec.latestFrame;
            long seq = rec == null ? -1 : rec.frameSeq;
            boolean fresh = rec != null && SystemClock.elapsedRealtime() - rec.latestFrameAt < 2000;
            preview.waiting = !fresh;
            if (m == null || frame == null || seq == lastSeq || m.cache == null) {
                if (!fresh) preview.postInvalidate();
                SystemClock.sleep(5);
                continue;
            }
            lastSeq = seq;
            rgba.rewind();
            Native.remapOffsets(frame.duplicate(), m.map, m.frac, m.w * m.h, rgba, m.rx, m.ry, m.rw, m.rh, m.cache);
            rgba.rewind();
            preview.show(rgba);
        }
    }

    /** Live picture with a level grid and the side label in the same corner as the floating view. */
    private static final class PreviewView extends View {
        private final Bitmap[] bitmaps = {Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)};
        private volatile int ready = -1;
        volatile boolean waiting;
        String label = "";
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect dst = new Rect();

        PreviewView(Context c) {
            super(c);
            grid.setColor(Color.argb(90, 255, 255, 255));
            grid.setStrokeWidth(1.5f);
            text.setColor(Color.WHITE);
            text.setTextSize(26);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setShadowLayer(4, 0, 1, Color.BLACK);
        }

        void clear() {
            ready = -1;
            invalidate();
        }

        void show(ByteBuffer rgba) {
            int target = ready == 0 ? 1 : 0;
            bitmaps[target].copyPixelsFromBuffer(rgba);
            ready = target;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int i = ready;
            if (i >= 0) {
                dst.set(0, 0, w, h);
                c.drawBitmap(bitmaps[i], null, dst, paint);
            }
            // Thirds grid to judge whether the ground and posts are level.
            for (int k = 1; k < 3; k++) {
                c.drawLine(0, h * k / 3f, w, h * k / 3f, grid);
                c.drawLine(w * k / 3f, 0, w * k / 3f, h, grid);
            }
            float x = "左后方".equals(label) ? w - 20 - text.measureText(label) : 20;
            c.drawText(label, x, 42, text);
            if (waiting) {
                Paint p = new Paint(text);
                p.setTextSize(24);
                String s = "等待 360 画面…";
                c.drawText(s, (w - p.measureText(s)) / 2, h / 2f, p);
            }
        }
    }

    /** Arrow and rotate glyphs, drawn so they stay crisp at any size. */
    private static final class TuneIcon extends View {
        static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, CW = 4, CCW = 5;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();

        TuneIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setDark(false);
        }

        void setDark(boolean dark) {
            paint.setColor(dark ? Ui.BG : Color.WHITE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.22f;
            path.reset();
            if (kind <= RIGHT) {
                // Filled triangle pointing the arrow's way.
                paint.setStyle(Paint.Style.FILL);
                float[][] pts;
                switch (kind) {
                    case UP: pts = new float[][]{{0, -1}, {-1, 0.6f}, {1, 0.6f}}; break;
                    case DOWN: pts = new float[][]{{0, 1}, {-1, -0.6f}, {1, -0.6f}}; break;
                    case LEFT: pts = new float[][]{{-1, 0}, {0.6f, -1}, {0.6f, 1}}; break;
                    default: pts = new float[][]{{1, 0}, {-0.6f, -1}, {-0.6f, 1}}; break;
                }
                path.moveTo(cx + pts[0][0] * r, cy + pts[0][1] * r);
                path.lineTo(cx + pts[1][0] * r, cy + pts[1][1] * r);
                path.lineTo(cx + pts[2][0] * r, cy + pts[2][1] * r);
                path.close();
                c.drawPath(path, paint);
                return;
            }
            // Open circle with the arrow head at the top, pointing the way it turns (like ↻ and ↺).
            float rr = r * 1.15f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4.5f);
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr);
            boolean cw = kind == CW;
            float start = cw ? -20 : -160, sweep = cw ? 280 : -280;
            c.drawArc(arc, start, sweep, false, paint);
            double end = Math.toRadians(start + sweep);
            float ex = cx + (float) (rr * Math.cos(end)), ey = cy + (float) (rr * Math.sin(end));
            // Tangent direction at the end of the arc, in the sweep direction.
            float tx = (float) -Math.sin(end) * (cw ? 1 : -1), ty = (float) Math.cos(end) * (cw ? 1 : -1);
            float nx = -ty, ny = tx, a = r * 0.55f;
            paint.setStyle(Paint.Style.FILL);
            path.moveTo(ex + tx * a, ey + ty * a);
            path.lineTo(ex - tx * a * 0.3f + nx * a * 0.8f, ey - ty * a * 0.3f + ny * a * 0.8f);
            path.lineTo(ex - tx * a * 0.3f - nx * a * 0.8f, ey - ty * a * 0.3f - ny * a * 0.8f);
            path.close();
            c.drawPath(path, paint);
        }
    }
}
