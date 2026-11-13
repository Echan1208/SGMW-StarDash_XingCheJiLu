package com.sgmwstarlight.stardash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.content.res.Configuration;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Owns the recorder, vehicle signals, info bar and floating views for the life of the process. */
public final class RecorderService extends Service {
    static final String ACTION_START = "com.sgmwstarlight.stardash.START";
    static final String ACTION_STOP = "com.sgmwstarlight.stardash.STOP";
    static final String ACTION_KEEPALIVE = "com.sgmwstarlight.stardash.KEEPALIVE";
    static final String ACTION_PREVIEW = "com.sgmwstarlight.stardash.PREVIEW";
    static volatile RecorderService instance;

    Config config;
    VehicleState vehicle;
    InfoBar bar;
    Recorder recorder;
    MirrorController mirror;
    private final DvrStatusBar statusIcon = new DvrStatusBar();
    private final Handler main = new Handler(Looper.getMainLooper());
    private StatusIconOverlay overlayIcon;
    private final WallpaperMode wallpaper = new WallpaperMode();
    private ForegroundApp foreground;
    private int tickCount;
    private HandlerThread barThread;
    private Handler barHandler;
    private Notification.Builder notification;
    private String lastNotice = "";

    static void start(Context c, String action) {
        Intent i = new Intent(c, RecorderService.class);
        if (action != null) i.setAction(action);
        c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        config = new Config(this);
        vehicle = new VehicleState();
        vehicle.start(this);
        bar = new InfoBar();
        recorder = new Recorder(config, vehicle, bar);
        mirror = new MirrorController(this, config, vehicle, recorder);

        Intent open = new Intent(this, MainActivity.class);
        notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("行车记录")
                .setContentText("正在启动…")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        startForeground(1, notification.build());

        overlayIcon = new StatusIconOverlay(this);
        wallpaper.start(this);
        foreground = new ForegroundApp(this);
        statusIcon.start(this, () -> {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });

        barThread = new HandlerThread("InfoBar");
        barThread.start();
        barHandler = new Handler(barThread.getLooper());
        barHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    bar.draw(vehicle, modeLabel());
                } catch (RuntimeException e) {
                    Log.w("StarDash", "bar draw failed", e);
                }
                updateNotification();
                int iconState = statusIconState();
                statusIcon.update(iconState);
                StatusIconOverlay.State overlayState = iconState == DvrStatusBar.CLOSED ? StatusIconOverlay.State.HIDDEN
                        : iconState == DvrStatusBar.EMERGENCY ? StatusIconOverlay.State.EVENT
                        : iconState == DvrStatusBar.ABNORMAL ? StatusIconOverlay.State.ERROR
                        : StatusIconOverlay.State.RECORDING;
                boolean checkFront = (tickCount++ % 4) == 0; // about once a second
                main.post(() -> {
                    if (checkFront) {
                        boolean home = "com.sgmw.launcher".equals(foreground.poll());
                        int mode = wallpaper.mode();
                        overlayIcon.setTint(home && (mode == 0 || mode == 1) ? mode : -1);
                    }
                    overlayIcon.show(overlayState);
                });
                barHandler.postDelayed(this, 250);
            }
        });

        new Thread(() -> {
            Storage.recoverAll();
            recorder.setRecording(config.prefs().getBoolean("recordingOn", config.autoStart()));
            recorder.start();
        }, "Startup").start();
        mirror.start();
        scheduleKeepAlive();
    }

    String modeLabel() {
        if (!recorder.recordingWanted()) return null;
        switch (recorder.mode) {
            case PARKING:
                return "停车缩时 ×" + Math.round(25f / Math.max(1, config.timelapseFps()));
            case EVENT:
                return "停车事件";
            default:
                return null;
        }
    }

    /** Maps recorder state onto the factory DVR icon: recording, event (lock icon), fault, closed, starting. */
    private int statusIconState() {
        if (!recorder.recordingWanted()) return DvrStatusBar.CLOSED;
        if (!recorder.sourceOk || !recorder.lastError.isEmpty()) return DvrStatusBar.ABNORMAL;
        if (!recorder.encoding) return DvrStatusBar.INIT;
        if (recorder.mode == Recorder.Mode.EVENT) return DvrStatusBar.EMERGENCY;
        return DvrStatusBar.RECORDING;
    }

    void setRecording(boolean on) {
        config.prefs().edit().putBoolean("recordingOn", on).apply();
        recorder.setRecording(on);
    }

    private void updateNotification() {
        String text;
        if (!recorder.recordingWanted()) text = "已暂停录制";
        else if (!recorder.sourceOk) text = "等待 360 画面…";
        else if (recorder.mode == Recorder.Mode.DRIVE) text = "行车录制中";
        else if (recorder.mode == Recorder.Mode.PARKING) text = "停车监控中";
        else text = "停车事件录制中";
        if (!text.equals(lastNotice)) {
            lastNotice = text;
            notification.setContentText(text);
            startForeground(1, notification.build());
        }
    }

    private void scheduleKeepAlive() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent i = new Intent(this, BootReceiver.class).setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 300_000, 300_000, pi);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) setRecording(true);
        else if (ACTION_STOP.equals(action)) setRecording(false);
        else if (ACTION_PREVIEW.equals(action) && intent != null) {
            // adb shell am startservice -n com.sgmwstarlight.stardash/.RecorderService
            //     -a com.sgmwstarlight.stardash.PREVIEW --es side LEFT --ei seconds 20
            String side = intent.getStringExtra("side");
            int seconds = intent.getIntExtra("seconds", 5);
            try {
                mirror.preview(MirrorController.Side.valueOf(side == null ? "LEFT" : side), seconds * 1000L);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Day/night switch: reload so the icon matches the other status bar icons.
        if (overlayIcon != null) overlayIcon.refresh();
    }

    @Override
    public void onDestroy() {
        statusIcon.update(DvrStatusBar.CLOSED);
        main.post(() -> overlayIcon.remove());
        mirror.stop();
        recorder.stop();
        vehicle.stop();
        barThread.quitSafely();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
