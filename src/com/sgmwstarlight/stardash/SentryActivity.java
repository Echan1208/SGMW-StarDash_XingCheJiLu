package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Parking sentry screen. Full screen without the system bars; after 15 seconds without a touch the
 * screen goes dark (black window with the backlight override at 0, which on this head unit drives the
 * panel backlight, since the stock display settings write the same Android brightness). A tap or a
 * parking event lights it again. While open, the recorder runs parking monitoring at once.
 */
public final class SentryActivity extends Activity {
    private static final String TAG = "StarDash";
    private static final long SLEEP_AFTER_MS = 15_000;
    private static final String EVENT_TEXT = "检测到车辆环境变化，开始停车事件录制...";
    private static final float SCALE = 2.5f;
    private static final int ICON_W = Math.round(128 * SCALE), ICON_H = Math.round(88 * SCALE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FlowView flow;
    private RecIcon icon;
    private View blackout;
    private TextView subtitle;
    private boolean asleep;
    /** Last touch or event (elapsedRealtime); the screen sleeps SLEEP_AFTER_MS after it. */
    private long lastActiveMs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        flow = new FlowView(this);
        root.addView(flow, new FrameLayout.LayoutParams(-1, -1));

        // Centre: big title with the red camera beside it, the subtitle below.
        LinearLayout centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER_HORIZONTAL);
        centre.setClipChildren(false);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setClipChildren(false);
        // Very large: the title block takes about a third of the screen height; everything scales by 2.5.
        TextView title = Ui.text(this, "正在记录...", 88 * SCALE, Color.WHITE, true);
        title.setIncludeFontPadding(false);
        titleRow.addView(title, Ui.lp(-2, -2));
        icon = new RecIcon(this);
        titleRow.addView(icon, Ui.margins(Ui.lp(ICON_W, ICON_H), Math.round(36 * SCALE), 0, 0, 0));
        // Wrap width: by default a vertical LinearLayout stretches it to the subtitle's width and cuts the icon.
        centre.addView(titleRow, Ui.lp(-2, -2));
        subtitle = Ui.text(this, "", 26 * SCALE, Color.rgb(160, 168, 178), false);
        centre.addView(subtitle, Ui.margins(Ui.lp(-2, -2), 0, Math.round(24 * SCALE), 0, 0));
        root.addView(centre, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Bottom centre: the exit button.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView exit = Ui.button(this, "退出停车哨兵模式", Color.argb(46, 255, 255, 255), Color.WHITE);
        exit.setBackground(Ui.round(Color.argb(46, 255, 255, 255), 40));
        exit.setPadding(56, 22, 56, 22);
        exit.setOnClickListener(v -> finish());
        bottom.addView(exit, Ui.lp(-2, -2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = 64;
        root.addView(bottom, blp);

        // Covers everything while the screen is dark, so nothing can glow through.
        blackout = new View(this);
        blackout.setBackgroundColor(Color.BLACK);
        blackout.setVisibility(View.GONE);
        root.addView(blackout, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        RecorderService.start(this, null);
        lastActiveMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "sentry on");
    }

    private Recorder recorder() {
        RecorderService s = RecorderService.instance;
        return s == null ? null : s.recorder;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 500);
        }
    };

    private boolean recordingEnsured;

    private void update() {
        long now = SystemClock.elapsedRealtime();
        RecorderService svc = RecorderService.instance;
        Recorder r = recorder();
        if (r != null) {
            r.sentry = true;
            if (!recordingEnsured) {
                recordingEnsured = true;
                if (!r.recordingWanted()) svc.setRecording(true); // sentry always records
                svc.dimCluster();
            }
        }
        boolean event = r != null && r.mode == Recorder.Mode.EVENT;
        if (event) {
            lastActiveMs = now;
            if (asleep) {
                Log.i(TAG, "sentry wake: parking event");
                wake();
            }
        }
        if (r == null || !r.sourceOk) subtitle.setText("等待 360 画面…");
        else if (event) subtitle.setText(EVENT_TEXT);
        else if (r.mode == Recorder.Mode.PARKING) subtitle.setText("停车缩时录制中，画面有变动时将自动录制停车事件");
        else subtitle.setText("车辆挂入 P 挡并静止后开始停车监控");
        if (!asleep && !event && now - lastActiveMs >= SLEEP_AFTER_MS) sleep();
    }

    private void sleep() {
        asleep = true;
        blackout.setVisibility(View.VISIBLE);
        flow.setRunning(false);
        icon.setRunning(false);
        setBrightness(0f);
        Log.i(TAG, "sentry screen off");
    }

    private void wake() {
        asleep = false;
        lastActiveMs = SystemClock.elapsedRealtime();
        blackout.setVisibility(View.GONE);
        flow.setRunning(true);
        icon.setRunning(true);
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        update();
    }

    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (asleep) {
            // The tap that lights the screen does nothing else (so it cannot hit the exit button).
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                Log.i(TAG, "sentry wake: tap");
                wake();
            }
            return true;
        }
        lastActiveMs = SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (!asleep) {
            flow.setRunning(true);
            icon.setRunning(true);
        }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        flow.setRunning(false);
        icon.setRunning(false);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Recorder r = recorder();
        if (r != null) r.sentry = false;
        RecorderService svc = RecorderService.instance;
        if (svc != null) svc.restoreCluster();
        Log.i(TAG, "sentry off");
        super.onDestroy();
    }

    /** Black to dark grey radial glows drifting slowly; redrawn about 30 times a second while shown. */
    private static final class FlowView extends View {
        private final Paint a = new Paint(), b = new Paint();
        private final Matrix m = new Matrix();
        private RadialGradient ga, gb;
        private boolean running;
        private final long t0 = SystemClock.elapsedRealtime();

        FlowView(Context c) {
            super(c);
        }

        void setRunning(boolean on) {
            if (running == on) return;
            running = on;
            if (on) invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float r = Math.max(w, h) * 0.7f;
            ga = new RadialGradient(0, 0, r, Color.rgb(46, 49, 54), Color.argb(0, 46, 49, 54), Shader.TileMode.CLAMP);
            gb = new RadialGradient(0, 0, r * 0.85f, Color.rgb(32, 36, 42), Color.argb(0, 32, 36, 42), Shader.TileMode.CLAMP);
            a.setShader(ga);
            b.setShader(gb);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(Color.BLACK);
            if (ga == null) return;
            double t = (SystemClock.elapsedRealtime() - t0) / 1000.0;
            m.setTranslate((float) (w * (0.5 + 0.28 * Math.sin(t * 2 * Math.PI / 23))),
                    (float) (h * (0.5 + 0.22 * Math.cos(t * 2 * Math.PI / 31))));
            ga.setLocalMatrix(m);
            c.drawRect(0, 0, w, h, a);
            m.setTranslate((float) (w * (0.5 + 0.30 * Math.cos(t * 2 * Math.PI / 29))),
                    (float) (h * (0.5 + 0.25 * Math.sin(t * 2 * Math.PI / 19))));
            gb.setLocalMatrix(m);
            c.drawRect(0, 0, w, h, b);
            if (running) postInvalidateDelayed(33);
        }
    }

    /** Red camera with a slow "recording" pulse. */
    private static final class RecIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path lens = new Path();
        private final RectF body = new RectF();
        private boolean running;

        RecIcon(Context c) {
            super(c);
            p.setColor(Ui.RED);
        }

        void setRunning(boolean on) {
            if (running == on) return;
            running = on;
            if (on) invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            // Fit a 1.45 : 1 camcorder (body plus lens) inside the view, centred.
            float vw = getWidth(), vh = getHeight();
            float h = Math.min(vh, vw / 1.45f), w = h * 1.45f;
            float x0 = (vw - w) / 2, y0 = (vh - h) / 2;
            double t = SystemClock.elapsedRealtime() / 1000.0;
            p.setAlpha((int) (255 * (0.6 + 0.4 * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI / 1.6)))));
            body.set(x0, y0 + h * 0.1f, x0 + w * 0.66f, y0 + h * 0.9f);
            c.drawRoundRect(body, h * 0.16f, h * 0.16f, p);
            lens.reset();
            lens.moveTo(x0 + w * 0.7f, y0 + h * 0.5f);
            lens.lineTo(x0 + w, y0 + h * 0.18f);
            lens.lineTo(x0 + w, y0 + h * 0.82f);
            lens.close();
            c.drawPath(lens, p);
            if (running) postInvalidateDelayed(50);
        }
    }
}
