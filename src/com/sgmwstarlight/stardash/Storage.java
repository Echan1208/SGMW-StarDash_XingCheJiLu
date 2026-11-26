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
 * Folder layout under /storage/emulated/0/StarDash, and the same under StarDash/ on a USB stick:
 * Drive/yyyyMMdd (1-minute driving segments), Parking/yyyyMMdd (time-lapse), Event (parking motion),
 * Locked (never pruned). Recording always happens on the head unit; in USB mode finished clips move to
 * the stick (see UsbStorage).
 */
final class Storage {
    static final File ROOT = new File("/storage/emulated/0/StarDash");
    static final String DRIVE = "Drive", PARKING = "Parking", EVENT = "Event", LOCKED = "Locked";
    private static final long MIN_FREE = 2L << 30;
    private static final File LOCAL_VOLUME = new File("/storage/emulated/0");
    /** StarDash/ on the mounted USB stick, or null; set by UsbStorage.refresh. */
    static volatile File usbRoot;

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

    /** Prunes the head unit's own clips. */
    static void prune(long quotaBytes) {
        prune(ROOT, quotaBytes, LOCAL_VOLUME, MIN_FREE);
    }

    /** Deletes the oldest unlocked clips under {@code root} until under quota and above the free-space floor. */
    static synchronized void prune(File root, long quotaBytes, File volume, long minFree) {
        List<Clip> clips = new ArrayList<>();
        collect(new File(root, DRIVE), "DRIVE", clips);
        collect(new File(root, PARKING), "PARKING", clips);
        collect(new File(root, EVENT), "EVENT", clips);
        Collections.sort(clips, (a, b) -> Long.compare(a.time, b.time));
        long used = 0;
        for (Clip c : clips) used += c.file.length();
        int i = 0;
        while (i < clips.size() && (used > quotaBytes || freeBytes(volume) < minFree)) {
            Clip c = clips.get(i++);
            long len = c.file.length();
            if (delete(c.file)) used -= len;
        }
    }

    /** Deletes a clip (on the head unit or the stick) and its folder once empty, except the top folders. */
    static boolean delete(File f) {
        boolean usb = onUsb(f);
        boolean ok = usb ? UsbStorage.delete(f) : f.delete();
        if (!ok) return false;
        File parent = f.getParentFile();
        if (parent != null && !parent.getName().equals(EVENT) && !parent.getName().equals(LOCKED)) {
            if (usb) UsbStorage.removeEmptyDir(parent);
            else {
                String[] left = parent.list();
                if (left != null && left.length == 0) parent.delete();
            }
        }
        return true;
    }

    /** Removes a clip from the head unit once it is safely on the stick. */
    static void deleteLocal(File f) {
        delete(f);
    }

    static boolean onUsb(File f) {
        File u = usbRoot;
        return u != null && f.getPath().startsWith(u.getPath() + "/");
    }

    static long freeBytes(File volume) {
        try {
            return new StatFs(volume.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Bytes of clips under one root (locked ones included). */
    static long usedBytes(File root) {
        long used = 0;
        List<File> files = new ArrayList<>();
        walk(root, files, ".mp4");
        for (File f : files) used += f.length();
        return used;
    }

    static long freeBytes() {
        return freeBytes(LOCAL_VOLUME);
    }

    /** Bytes of the head unit's own clips. */
    static long usedBytes() {
        return usedBytes(ROOT);
    }

    /** All finished clips on the head unit and the USB stick, newest first. */
    static List<Clip> list(boolean includeLocked) {
        List<Clip> out = new ArrayList<>();
        File usb = usbRoot;
        for (File root : usb == null ? new File[]{ROOT} : new File[]{ROOT, usb}) {
            collect(new File(root, DRIVE), "DRIVE", out);
            collect(new File(root, PARKING), "PARKING", out);
            collect(new File(root, EVENT), "EVENT", out);
            if (includeLocked) collect(new File(root, LOCKED), "LOCKED", out);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    private static File rootOf(File f) {
        return onUsb(f) ? usbRoot : ROOT;
    }

    private static File move(File f, File to) {
        if (onUsb(f)) return UsbStorage.rename(f, to) ? to : null;
        File dir = to.getParentFile();
        if (dir != null) dir.mkdirs();
        return f.renameTo(to) ? to : null;
    }

    static File lock(File f) {
        return move(f, new File(new File(rootOf(f), LOCKED), f.getName()));
    }

    static File unlock(File f) {
        long t = parseTime(f.getName(), f.lastModified());
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(t));
        String name = f.getName();
        File root = rootOf(f);
        File dir = name.contains("_E") ? new File(root, EVENT)
                : new File(new File(root, name.contains("_P") ? PARKING : DRIVE), day);
        return move(f, new File(dir, name));
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
