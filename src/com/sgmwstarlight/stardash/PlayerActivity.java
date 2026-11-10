package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** Plays a clip and continues into the next one, like watching one long recording. */
public final class PlayerActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private ArrayList<String> paths;
    private int index;
    private TextView name, time, play, speedBtn;
    private SeekBar seek;
    private LinearLayout controls;
    private boolean dragging;
    private float speed = 1f;
    private MediaPlayer player;

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
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(Color.argb(200, 11, 14, 19));
        controls.setPadding(36, 18, 36, 22);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹ 返回", 20, Ui.TEXT, true);
        back.setPadding(0, 8, 30, 8);
        back.setOnClickListener(v -> finish());
        name = Ui.text(this, "", 18, Ui.SUB, false);
        time = Ui.text(this, "", 18, Ui.TEXT, false);
        top.addView(back);
        top.addView(name, Ui.lp(0, -2, 1));
        top.addView(time);
        controls.addView(top);

        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) video.seekTo(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; }
        });
        controls.addView(seek, Ui.margins(Ui.lp(-1, -2), 0, 10, 0, 10));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        TextView prev = Ui.button(this, "⏮ 上一段", Ui.CARD2, Ui.TEXT);
        play = Ui.button(this, "暂停", Ui.GREEN, Ui.BG);
        TextView next = Ui.button(this, "下一段 ⏭", Ui.CARD2, Ui.TEXT);
        speedBtn = Ui.button(this, "1×", Ui.CARD2, Ui.TEXT);
        TextView lock = Ui.button(this, "锁定", Ui.CARD2, Ui.TEXT);
        prev.setOnClickListener(v -> open(index - 1));
        next.setOnClickListener(v -> open(index + 1));
        play.setOnClickListener(v -> {
            if (video.isPlaying()) video.pause();
            else video.start();
            updatePlay();
        });
        speedBtn.setOnClickListener(v -> {
            speed = speed == 1f ? 2f : speed == 2f ? 4f : 1f;
            applySpeed();
        });
        lock.setOnClickListener(v -> {
            File f = new File(paths.get(index));
            if (f.getParentFile() != null && f.getParentFile().getName().equals(Storage.LOCKED)) return;
            int pos = video.getCurrentPosition();
            File to = Storage.lock(f);
            if (to != null) {
                paths.set(index, to.getPath());
                lock.setText("已锁定 ✓");
                video.setVideoPath(to.getPath());
                video.seekTo(pos);
                video.start();
            }
        });
        for (TextView b : new TextView[]{prev, play, next, speedBtn, lock}) {
            buttons.addView(b, Ui.margins(Ui.lp(-2, -2), 10, 0, 10, 0));
        }
        controls.addView(buttons);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        video.setOnClickListener(null);
        root.setOnClickListener(v -> controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            player = mp;
            seek.setMax(mp.getDuration());
            applySpeed();
            video.start();
            updatePlay();
        });
        video.setOnCompletionListener(mp -> {
            if (index + 1 < paths.size()) open(index + 1);
            else updatePlay();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            name.setText(new File(paths.get(index)).getName() + "  （无法播放）");
            return true;
        });
        open(index);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!dragging && video.isPlaying()) seek.setProgress(video.getCurrentPosition());
                time.setText(fmt(video.getCurrentPosition()) + " / " + fmt(Math.max(0, video.getDuration())));
                handler.postDelayed(this, 300);
            }
        });
    }

    private void open(int i) {
        if (i < 0 || i >= paths.size()) return;
        index = i;
        File f = new File(paths.get(i));
        name.setText(f.getName() + "   (" + (i + 1) + "/" + paths.size() + ")");
        video.setVideoPath(f.getPath());
    }

    private void applySpeed() {
        speedBtn.setText((int) speed + "×");
        if (player == null) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        } catch (RuntimeException ignored) {
        }
    }

    private void updatePlay() {
        play.setText(video.isPlaying() ? "暂停" : "播放");
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
