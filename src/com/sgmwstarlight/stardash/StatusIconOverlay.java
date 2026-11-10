package com.sgmwstarlight.stardash;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Draws the head unit's own dashcam icon (loaded from SystemUI's resources, so day/night variants
 * follow the system) in a small window over the status bar, left of the right-hand icon group.
 * Nothing in the system is changed. Not used while SystemUI shows its own DVR icon
 * (persist.vendor.sgmw.support.dvr=1), to avoid two icons.
 */
final class StatusIconOverlay {
    private static final String TAG = "StarDash";
    private static final String SYSTEMUI = "com.android.systemui";
    /** Centre of the slot left of the six right-hand status icons (they sit about 101 px apart). */
    private static final int CENTER_X = 1113, SIZE = 56, TOP = 12;

    enum State { HIDDEN, RECORDING, EVENT, ERROR }

    private final Context context;
    private final WindowManager wm;
    private ImageView view;
    private State shown = State.HIDDEN;
    /** -1 follow day/night, 0 dark icon, 1 white icon (wallpaper setting while the home screen is up). */
    private int tint = -1;
    private final boolean systemIconActive;

    StatusIconOverlay(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.systemIconActive = "1".equals(getprop("persist.vendor.sgmw.support.dvr"));
        if (systemIconActive) Log.i(TAG, "SystemUI shows its own DVR icon; overlay not used");
    }

    /** Must be called on the main thread. */
    void show(State state) {
        if (systemIconActive) return;
        if (state == shown) return;
        if (state == State.HIDDEN) {
            remove();
            shown = state;
            return;
        }
        Drawable icon = loadIcon(state);
        if (icon == null) return;
        if (view == null) {
            view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setOnClickListener(v -> context.startActivity(
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(SIZE, SIZE,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = CENTER_X - SIZE / 2;
            lp.y = TOP;
            lp.setTitle("StarDashStatusIcon");
            try {
                wm.addView(view, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "status icon overlay failed: " + e);
                view = null;
                return;
            }
        }
        view.setImageDrawable(icon);
        view.setVisibility(statusBarVisible ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        ensureProbe();
        shown = state;
    }

    private boolean statusBarVisible = true;
    private android.view.View probe;

    /**
     * Our icon window sits above the status bar, and windows above it are not told about it, so a
     * 1x1 transparent window at app level reports whether the status bar is currently shown.
     */
    private void ensureProbe() {
        if (probe != null) return;
        probe = new android.view.View(context);
        probe.setOnApplyWindowInsetsListener((v, insets) -> {
            setStatusBarVisible(insets.isVisible(android.view.WindowInsets.Type.statusBars()));
            return insets;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 540;
        lp.setTitle("StarDashStatusProbe");
        try {
            wm.addView(probe, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "status probe failed: " + e);
            probe = null;
        }
    }

    private void setStatusBarVisible(boolean visible) {
        if (visible == statusBarVisible) return;
        statusBarVisible = visible;
        Log.i(TAG, "status bar " + (visible ? "shown" : "hidden"));
        if (view != null) view.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
    }

    /** Same rule as SystemUI: on the home screen follow the wallpaper's setting, elsewhere day/night. */
    void setTint(int newTint) {
        if (newTint == tint) return;
        tint = newTint;
        refresh();
    }

    /** Day/night switched: reload the icon so it picks the matching variant. */
    void refresh() {
        State s = shown;
        shown = State.HIDDEN;
        if (s != State.HIDDEN && view != null) {
            Drawable icon = loadIcon(s);
            if (icon != null) view.setImageDrawable(icon);
            shown = s;
        }
    }

    void remove() {
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
        if (probe != null) {
            try {
                wm.removeView(probe);
            } catch (RuntimeException ignored) {
            }
            probe = null;
        }
        shown = State.HIDDEN;
    }

    private Drawable loadIcon(State state) {
        String name = state == State.EVENT ? "icon_status_video_lock"
                : state == State.ERROR ? "icon_status_video_error" : "icon_status_video_nor";
        try {
            // A package context created now carries the current day/night configuration.
            Context sysui = context.createPackageContext(SYSTEMUI, 0)
                    .createConfigurationContext(context.getResources().getConfiguration());
            Resources r = sysui.getResources();
            String suffix = tint == 0 ? "_light" : tint == 1 ? "_night" : "";
            int id = r.getIdentifier(name + suffix, "mipmap", SYSTEMUI);
            if (id == 0) id = r.getIdentifier(name, "mipmap", SYSTEMUI);
            return id == 0 ? null : r.getDrawable(id, null);
        } catch (Exception e) {
            Log.w(TAG, "cannot load SystemUI icon " + name + ": " + e);
            return null;
        }
    }

    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
