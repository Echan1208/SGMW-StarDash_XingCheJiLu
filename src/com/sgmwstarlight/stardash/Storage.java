package com.sgmwstarlight.stardash;

import android.os.StatFs;
import android.util.Log;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Folder layout under /storage/emulated/0/StarDash:
 * Drive/yyyyMMdd (1-minute driving segments), Parking/yyyyMMdd (time-lapse), Event (parking motion),
 * Locked (never pruned).
 */
final class Storage {
    static final File ROOT = new File("/storage/emulated/0/StarDash");
    static final String DRIVE = "Drive", PARKING = "Parking", EVENT = "Event", LOCKED = "Locked";
    private static final long MIN_FREE = 2L << 30;

    enum Kind { DRIVE, PARKING, EVENT }

    static final class Clip {
        final File file;
        final String type; // DRIVE, PARKING, EVENT, LOCKED
        final long time;

        Clip(File file, String type) {
            this.file = file;
            this.type = type;
            this.time = parseTime(file.getName(), file.lastModified());
        }
    }

    static File newFile(Kind kind, long nowMs) {
        Date d = new Date(nowMs);
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(d);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d);
        File dir;
        String suffix;
        switch (kind) {
            case PARKING:
                dir = new File(new File(ROOT, PARKING), day);
                suffix = "_P";
                break;
            case EVENT:
                dir = new File(ROOT, EVENT);
                suffix = "_E";
                break;
            default:
                dir = new File(new File(ROOT, DRIVE), day);
                suffix = "";
        }
        dir.mkdirs();
        File f = new File(dir, stamp + suffix + ".mp4");
        int n = 1;
        while (f.exists()) f = new File(dir, stamp + suffix + "_" + (n++) + ".mp4");
        return f;
    }

    /** Finishes files left open by a crash or power cut. */
    static void recoverAll() {
        List<File> found = new ArrayList<>();
        walk(ROOT, found, Mp4Writer.TMP);
        for (File f : found) {
            File done = Mp4Writer.recover(f);
            Log.i("StarDash", "recover " + f.getName() + " -> " + (done == null ? "dropped" : done.getName()));
        }
    }

    /** Deletes the oldest unlocked clips until under quota and above the free-space floor. */
    static void prune(long quotaBytes) {
        List<Clip> clips = list(false);
        Collections.sort(clips, (a, b) -> Long.compare(a.time, b.time));
        long used = 0;
        for (Clip c : clips) used += c.file.length();
        int i = 0;
        while (i < clips.size() && (used > quotaBytes || freeBytes() < MIN_FREE)) {
            Clip c = clips.get(i++);
            long len = c.file.length();
            if (c.file.delete()) {
                used -= len;
                File parent = c.file.getParentFile();
                String[] left = parent == null ? null : parent.list();
                if (left != null && left.length == 0 && !parent.equals(new File(ROOT, EVENT))) parent.delete();
            }
        }
    }

    static long freeBytes() {
        try {
            StatFs s = new StatFs("/storage/emulated/0");
            return s.getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return Long.MAX_VALUE;
        }
    }

    static long usedBytes() {
        long used = 0;
        for (Clip c : list(true)) used += c.file.length();
        return used;
    }

    /** All finished clips, newest first. */
    static List<Clip> list(boolean includeLocked) {
        List<Clip> out = new ArrayList<>();
        collect(new File(ROOT, DRIVE), "DRIVE", out);
        collect(new File(ROOT, PARKING), "PARKING", out);
        collect(new File(ROOT, EVENT), "EVENT", out);
        if (includeLocked) collect(new File(ROOT, LOCKED), "LOCKED", out);
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    static File lock(File f) {
        File dir = new File(ROOT, LOCKED);
        dir.mkdirs();
        File to = new File(dir, f.getName());
        return f.renameTo(to) ? to : null;
    }

    static File unlock(File f) {
        long t = parseTime(f.getName(), f.lastModified());
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(t));
        String name = f.getName();
        File dir = name.contains("_E") ? new File(ROOT, EVENT)
                : new File(new File(ROOT, name.contains("_P") ? PARKING : DRIVE), day);
        dir.mkdirs();
        File to = new File(dir, name);
        return f.renameTo(to) ? to : null;
    }

    private static void collect(File dir, String type, List<Clip> out) {
        List<File> files = new ArrayList<>();
        walk(dir, files, ".mp4");
        for (File f : files) out.add(new Clip(f, type));
    }

    private static void walk(File dir, List<File> out, String suffix) {
        File[] items = dir.listFiles();
        if (items == null) return;
        for (File f : items) {
            if (f.isDirectory()) walk(f, out, suffix);
            else if (f.getName().endsWith(suffix)) out.add(f);
        }
    }

    static long parseTime(String name, long fallback) {
        try {
            if (name.length() >= 15) {
                Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.substring(0, 15));
                if (d != null) return d.getTime();
            }
        } catch (java.text.ParseException ignored) {
        }
        return fallback;
    }
}
