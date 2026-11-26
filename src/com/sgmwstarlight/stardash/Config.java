package com.sgmwstarlight.stardash;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, with defaults chosen for the 2025 Wuling Starlight head unit. */
final class Config {
    private final SharedPreferences p;

    Config(Context c) {
        p = c.getSharedPreferences("stardash", Context.MODE_PRIVATE);
    }

    SharedPreferences prefs() {
        return p;
    }

    boolean autoStart() { return p.getBoolean("autoStart", true); }
    boolean hevc() { return p.getBoolean("hevc", true); }
    int bitrateMbps() { return p.getInt("bitrate", hevc() ? 5 : 8); }
    int quotaGb() { return p.getInt("quotaGb", 32); }
    /** Store finished clips on the USB stick instead of the head unit. */
    boolean usbStorage() { return p.getBoolean("usbStorage", false); }
    /** Quota on the stick, at most {@code maxGb} (the stick's size less 1 GB); defaults to the maximum. */
    int usbQuotaGb(int maxGb) { return Math.max(1, Math.min(maxGb, p.getInt("usbQuotaGb", maxGb))); }
    boolean infoBar() { return p.getBoolean("infoBar", true); }
    /** Tags each camera in the recording with 前/后/左/右. */
    boolean camLabels() { return p.getBoolean("camLabels", false); }

    boolean parking() { return p.getBoolean("parking", true); }
    int parkDelaySec() { return p.getInt("parkDelay", 60); }
    int timelapseFps() { return p.getInt("timelapseFps", 3); }
    boolean motion() { return p.getBoolean("motion", true); }
    /** Sensitivity 1..5; maps to the share of changed samples that starts an event. */
    int motionLevel() { return p.getInt("motionLevel", 3); }

    boolean mirror() { return p.getBoolean("mirror", true); }
    boolean ruleTurn() { return p.getBoolean("ruleTurn", true); }
    boolean ruleBrake() { return p.getBoolean("ruleBrake", true); }
    int brakeShowKmh() { return p.getInt("brakeShowKmh", 90); }
    int brakeHideKmh() { return p.getInt("brakeHideKmh", 10); }
    /** Side-rear warning while the left or right view is up. */
    boolean blindSpot() { return p.getBoolean("blindSpot", true); }
    boolean blindSpotSound() { return p.getBoolean("blindSpotSound", true); }
    /** Exit warning: first pull of an inner door handle at standstill shows that side for 5 s. */
    boolean doorWarn() { return p.getBoolean("doorWarn", true); }
    /** Rear view size, and the starting size of the side views: 0 small, 1 medium, 2 large. */
    int mirrorSize() { return p.getInt("mirrorSize", 1); }

    /** Size (0..2) of the "LEFT" or "RIGHT" floating view, set on the custom view page. */
    int sideSize(String side) { return p.getInt("win_" + side + "_size", mirrorSize()); }

    /** Saved top-left screen position of a side floating view, or {-1, -1} for the default spot. */
    int[] sidePos(String side) {
        return new int[]{p.getInt("win_" + side + "_x", -1), p.getInt("win_" + side + "_y", -1)};
    }

    /** Saves a side view's size and position; x < 0 goes back to the default spot. */
    void putSideWindow(String side, int size, int x, int y) {
        SharedPreferences.Editor e = p.edit().putInt("win_" + side + "_size", size);
        if (x < 0) e.remove("win_" + side + "_x").remove("win_" + side + "_y");
        else e.putInt("win_" + side + "_x", x).putInt("win_" + side + "_y", y);
        e.apply();
    }

    /** Custom view adjustment for side "LEFT" or "RIGHT": pan right, shift up, rotate clockwise (degrees). */
    float[] sideTune(String side) {
        return new float[]{p.getFloat("tune_" + side + "_pan", 0), p.getFloat("tune_" + side + "_up", 0),
                p.getFloat("tune_" + side + "_rot", 0)};
    }

    void putSideTune(String side, float pan, float up, float rot) {
        p.edit().putFloat("tune_" + side + "_pan", pan).putFloat("tune_" + side + "_up", up)
                .putFloat("tune_" + side + "_rot", rot).apply();
    }

    void put(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    void put(String key, int v) { p.edit().putInt(key, v).apply(); }
}
