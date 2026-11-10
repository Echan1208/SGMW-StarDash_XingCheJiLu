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
    boolean infoBar() { return p.getBoolean("infoBar", true); }

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
    int mirrorSize() { return p.getInt("mirrorSize", 1); } // 0 small, 1 medium, 2 large

    void put(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    void put(String key, int v) { p.edit().putInt(key, v).apply(); }
}
