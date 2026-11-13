package com.sgmwstarlight.stardash;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Whether this is the head unit the app was built for (2025 Wuling Starlight): Qualcomm trinket
 * platform, vehicle model 510C, and the F511C build of the factory system apps.
 */
final class CarCheck {
    private static Boolean supported;

    private CarCheck() {
    }

    static synchronized boolean isSupported() {
        if (supported == null) {
            boolean platform = "trinket".equals(getprop("ro.board.platform"));
            boolean model = getprop("persist.vendor.sgmw.vehicle.model").startsWith("510C");
            boolean build = new File("/system/priv-app/SGMWSystemUI_F511C").exists()
                    || new File("/system/app/Hvac_F511C").exists();
            supported = platform && model && build;
        }
        return supported;
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
