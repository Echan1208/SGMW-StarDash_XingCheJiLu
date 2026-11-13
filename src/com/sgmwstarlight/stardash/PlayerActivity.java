package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Plays a clip and continues into the next one. Controls float in a rounded bar near the bottom and
 * hide after 5 s of playback; a tap brings them back. Pinch to zoom, up to one camera filling the screen.
 */
public final class PlayerActivity extends Activity {
    private static final long HIDE_DELAY_MS = 5000;
    /** Recorded frame: 2x2 cameras of 640x480 (scaled to 1280x960) plus the 48 px status strip. */
    private static final float CAMERA_W = 640, CAMERA_H = 480;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayList<String> paths;
    private int index;

    private TextureView texture;
    private MediaPlayer player;
    private Surface surface;
    private boolean prepared;
    private int videoW, videoH;
    private float speed = 1f;
    private int pendingSeekMs;

    // Zoom state: scale relative to "whole frame fits the screen", and pan offset in view pixels.
    private float zoom = 1f, panX, panY;

    private View controls, back;
    private TextView title, timeNow, timeTotal, speedBtn, lockBtn;
    private IconView playBtn;
    private SeekBar seek;
    private boolean dragging, controlsShown = true;

    private final Runnable autoHide = () -> {
        if (player != null && prepared && player.isPlaying() && !dragging) setControls(false);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        paths = getIntent().getStringArrayListExtra("paths");
        index = getIntent().getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                open(index, false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        // Back button, floating in the top-left corner.
        IconView backIcon = new IconView(this, IconView.BACK);
        backIcon.setBackground(Ui.round(Color.argb(170, 20, 24, 30), 32));
        backIcon.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(64, 64, Gravity.TOP | Gravity.LEFT);
        blp.setMargins(24, 24, 0, 0);
        root.addView(backIcon, blp);
        back = backIcon;

        root.addView(buildControls(), centeredBottom());
        setContentView(root);

        // Taps toggle the controls; pinch zooms; drag pans while zoomed; double tap zooms into one camera.
        ScaleGestureDetector scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                setControls(!controlsShown);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1.05f) {
                    zoom = 1f;
                    panX = panY = 0;
                    applyTransform();
                } else {
                    zoomAround(maxZoom(), e.getX(), e.getY());
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (zoom <= 1f) return false;
                panX -= dx;
                panY -= dy;
                applyTransform();
                return true;
            }
        });
        texture.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) gestures.onTouchEvent(e);
            return true;
        });

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (player != null && prepared) {
                    int pos = player.getCurrentPosition();
                    if (!dragging) seek.setProgress(pos);
                    timeNow.setText(fmt(pos));
                }
                handler.postDelayed(this, 300);
            }
        });
    }

    // ------------------------------------------------------------------ controls

    private View buildControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Color.argb(205, 20, 24, 30), 26));
        box.setPadding(28, 12, 28, 14);

        title = Ui.text(this, "", 14, Ui.SUB, false);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        IconView prev = new IconView(this, IconView.PREV);
        playBtn = new IconView(this, IconView.PAUSE);
        IconView next = new IconView(this, IconView.NEXT);
        prev.setOnClickListener(v -> open(index - 1, true));
        next.setOnClickListener(v -> open(index + 1, true));
        playBtn.setOnClickListener(v -> {
            if (player == null || !prepared) return;
            if (player.isPlaying()) player.pause();
            else player.start();
            updatePlay();
            poke();
        });
        row.addView(prev, Ui.lp(48, 48));
        row.addView(playBtn, Ui.margins(Ui.lp(52, 52), 14, 0, 14, 0));
        row.addView(next, Ui.lp(48, 48));

        timeNow = Ui.text(this, "0:00", 15, Ui.TEXT, false);
        timeTotal = Ui.text(this, "0:00", 15, Ui.SUB, false);
        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && player != null && prepared) {
                    player.seekTo(p);
                    timeNow.setText(fmt(p));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                handler.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                poke();
            }
        });
        row.addView(timeNow, Ui.margins(Ui.lp(56, -2), 24, 0, 0, 0));
        row.addView(seek, Ui.lp(0, -2, 1));
        row.addView(timeTotal, Ui.lp(56, -2));

        speedBtn = pill("1×");
        speedBtn.setOnClickListener(v -> {
            speed = speed == 1f ? 2f : speed == 2f ? 4f : 1f;
            applySpeed();
            poke();
        });
        lockBtn = pill("锁定");
        lockBtn.setOnClickListener(v -> {
            lockCurrent();
            poke();
        });
        row.addView(speedBtn, Ui.margins(Ui.lp(-2, -2), 18, 0, 0, 0));
        row.addView(lockBtn, Ui.margins(Ui.lp(-2, -2), 10, 0, 0, 0));
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        controls = box;
        return box;
    }

    private FrameLayout.LayoutParams centeredBottom() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1040, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 40;
        return lp;
    }

    private TextView pill(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(16, 6, 16, 6);
        t.setBackground(Ui.round(Ui.CARD2, 14));
        return t;
    }

    private void setControls(boolean show) {
        controlsShown = show;
        for (View v : new View[]{controls, back}) {
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(150).start();
            } else {
                v.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    if (!controlsShown) v.setVisibility(View.GONE);
                }).start();
            }
        }
        if (show) poke();
        else handler.removeCallbacks(autoHide);
    }

    /** Restarts the 5 s auto-hide timer. */
    private void poke() {
        handler.removeCallbacks(autoHide);
        handler.postDelayed(autoHide, HIDE_DELAY_MS);
    }

    private void updatePlay() {
        playBtn.setKind(player != null && prepared && player.isPlaying() ? IconView.PAUSE : IconView.PLAY);
    }

    // ------------------------------------------------------------------ playback

    private void open(int i, boolean showControls) {
        if (i < 0 || i >= paths.size() || surface == null) return;
        index = i;
        File f = new File(paths.get(i));
        long t = Storage.parseTime(f.getName(), f.lastModified());
        title.setText(new SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA).format(new Date(t))
                + "   ·   " + (i + 1) + " / " + paths.size());
        boolean locked = f.getParentFile() != null && f.getParentFile().getName().equals(Storage.LOCKED);
        lockBtn.setText(locked ? "已锁定" : "锁定");
        releasePlayer();
        prepared = false;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setSurface(surface);
            mp.setDataSource(f.getPath());
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                videoW = p.getVideoWidth();
                videoH = p.getVideoHeight();
                seek.setMax(p.getDuration());
                timeTotal.setText(fmt(p.getDuration()));
                applyTransform();
                applySpeed();
                if (pendingSeekMs > 0) p.seekTo(pendingSeekMs);
                pendingSeekMs = 0;
                p.start();
                updatePlay();
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w;
                videoH = h;
                applyTransform();
            });
            mp.setOnCompletionListener(p -> {
                if (index + 1 < paths.size()) open(index + 1, true);
                else {
                    updatePlay();
                    setControls(true);
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                title.setText(f.getName() + "   ·   无法播放");
                setControls(true);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            title.setText(f.getName() + "   ·   无法播放");
        }
        if (showControls) setControls(true);
        else poke();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        prepared = false;
    }

    private void applySpeed() {
        speedBtn.setText((int) speed + "×");
        if (player == null || !prepared) return;
        try {
            boolean playing = player.isPlaying();
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            if (!playing) player.pause(); // setting a speed resumes playback
        } catch (RuntimeException ignored) {
        }
    }

    private void lockCurrent() {
        File f = new File(paths.get(index));
        if (f.getParentFile() != null && f.getParentFile().getName().equals(Storage.LOCKED)) return;
        int pos = player != null && prepared ? player.getCurrentPosition() : 0;
        File to = Storage.lock(f);
        if (to == null) return;
        paths.set(index, to.getPath());
        lockBtn.setText("已锁定");
        // The file moved, so reopen it and continue from the same spot.
        pendingSeekMs = pos;
        open(index, false);
    }

    // ------------------------------------------------------------------ zoom

    /** Largest zoom: one camera covers the whole view. */
    private float maxZoom() {
        if (videoW == 0 || videoH == 0) return 1f;
        float vw = texture.getWidth(), vh = texture.getHeight();
        float fit = Math.min(vw / videoW, vh / videoH);
        // Cameras are 640x480 of the recorded 1280-wide frame.
        float camW = CAMERA_W * videoW / 1280f, camH = CAMERA_H * videoW / 1280f;
        return Math.max(1f, Math.max(vw / (camW * fit), vh / (camH * fit)));
    }

    private void zoomAround(float newZoom, float fx, float fy) {
        newZoom = Math.max(1f, Math.min(maxZoom(), newZoom));
        float vw = texture.getWidth(), vh = texture.getHeight();
        // Keep the point under the fingers where it is.
        float cx = vw / 2f + panX, cy = vh / 2f + panY;
        float k = newZoom / zoom;
        panX = fx - (fx - cx) * k - vw / 2f;
        panY = fy - (fy - cy) * k - vh / 2f;
        zoom = newZoom;
        applyTransform();
    }

    /** TextureView stretches the video to the view; this matrix restores the aspect ratio, then zooms and pans. */
    private void applyTransform() {
        if (videoW == 0 || videoH == 0 || texture.getWidth() == 0) return;
        float vw = texture.getWidth(), vh = texture.getHeight();
        float fit = Math.min(vw / videoW, vh / videoH);
        float contentW = videoW * fit * zoom, contentH = videoH * fit * zoom;
        // Never pan past the frame's edges.
        float maxX = Math.max(0, (contentW - vw) / 2f), maxY = Math.max(0, (contentH - vh) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        Matrix m = new Matrix();
        m.setScale(contentW / vw, contentH / vh, vw / 2f, vh / 2f);
        m.postTranslate(panX, panY);
        texture.setTransform(m);
        texture.invalidate();
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && prepared && player.isPlaying()) player.pause();
        updatePlay();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    /** Small drawn icons so the controls stay crisp and consistent. */
    static final class IconView extends View {
        static final int BACK = 0, PREV = 1, PLAY = 2, PAUSE = 3, NEXT = 4;
        private int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context c, int kind) {
            super(c);
            this.kind = kind;
            paint.setColor(Color.WHITE);
            setClickable(true);
        }

        void setKind(int k) {
            if (k != kind) {
                kind = k;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.26f;
            path.reset();
            paint.setStyle(Paint.Style.FILL);
            switch (kind) {
                case BACK:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(4);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    path.moveTo(cx + r * 0.45f, cy - r);
                    path.lineTo(cx - r * 0.55f, cy);
                    path.lineTo(cx + r * 0.45f, cy + r);
                    break;
                case PLAY:
                    path.moveTo(cx - r * 0.7f, cy - r);
                    path.lineTo(cx + r, cy);
                    path.lineTo(cx - r * 0.7f, cy + r);
                    path.close();
                    break;
                case PAUSE:
                    c.drawRoundRect(cx - r * 0.85f, cy - r, cx - r * 0.25f, cy + r, 3, 3, paint);
                    c.drawRoundRect(cx + r * 0.25f, cy - r, cx + r * 0.85f, cy + r, 3, 3, paint);
                    return;
                case PREV:
                    c.drawRoundRect(cx - r, cy - r * 0.85f, cx - r * 0.7f, cy + r * 0.85f, 2, 2, paint);
                    path.moveTo(cx + r * 0.9f, cy - r * 0.85f);
                    path.lineTo(cx - r * 0.55f, cy);
                    path.lineTo(cx + r * 0.9f, cy + r * 0.85f);
                    path.close();
                    break;
                default: // NEXT
                    c.drawRoundRect(cx + r * 0.7f, cy - r * 0.85f, cx + r, cy + r * 0.85f, 2, 2, paint);
                    path.moveTo(cx - r * 0.9f, cy - r * 0.85f);
                    path.lineTo(cx + r * 0.55f, cy);
                    path.lineTo(cx - r * 0.9f, cy + r * 0.85f);
                    path.close();
            }
            c.drawPath(path, paint);
        }
    }
}
