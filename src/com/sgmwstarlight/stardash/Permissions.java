package com.sgmwstarlight.stardash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * Which of the permissions the app relies on are missing. Installs done with a Windows shell often skip
 * the README's grant step (its quoted multi-line command does not run there), which left one user with
 * "open failed: ENOENT" because the recording folder could not be created.
 */
final class Permissions {
    private static final String PKG = "com.sgmwstarlight.stardash";

    /** One missing permission: what breaks without it, and a one-line command that grants it. */
    static final class Missing {
        final String permission, label, effect, command;
        /** True when the system permission dialog can grant it (dangerous permissions). */
        final boolean runtime;

        Missing(String permission, String label, String effect, String command, boolean runtime) {
            this.permission = permission;
            this.label = label;
            this.effect = effect;
            this.command = command;
            this.runtime = runtime;
        }
    }

    private static final String[][] NEEDED = {
            {Manifest.permission.WRITE_EXTERNAL_STORAGE, "存储", "无法保存录像"},
            {Manifest.permission.READ_EXTERNAL_STORAGE, "存储（读取）", "无法回放录像"},
            {"android.car.permission.CAR_SPEED", "车速", "信息栏不显示车速，悬浮画面和停车判断不准"},
            {"android.car.permission.CAR_INFO", "车辆信息", "信息栏部分状态不显示"},
            {"android.car.permission.CAR_POWERTRAIN", "档位", "信息栏不显示档位，悬浮画面和停车判断不准"},
    };

    private Permissions() {
    }

    static List<Missing> missing(Context c) {
        List<Missing> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String[] n : NEEDED) {
            if (c.checkSelfPermission(n[0]) == PackageManager.PERMISSION_GRANTED) continue;
            boolean runtime = false;
            try {
                PermissionInfo info = pm.getPermissionInfo(n[0], 0);
                runtime = (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
            } catch (PackageManager.NameNotFoundException e) {
                continue; // not defined on this system, nothing to grant
            }
            out.add(new Missing(n[0], n[1], n[2], "adb shell pm grant " + PKG + " " + n[0], runtime));
        }
        if (!Settings.canDrawOverlays(c)) {
            out.add(new Missing(null, "悬浮窗", "不显示转向/刹车悬浮画面和状态栏录像图标",
                    "adb shell appops set " + PKG + " SYSTEM_ALERT_WINDOW allow", false));
        }
        return out;
    }

    static boolean storageMissing(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Permissions from {@code list} that the system dialog can grant. */
    static String[] runtime(List<Missing> list) {
        List<String> out = new ArrayList<>();
        for (Missing m : list) if (m.runtime) out.add(m.permission);
        return out.toArray(new String[0]);
    }
}
