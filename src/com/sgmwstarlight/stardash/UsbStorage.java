package com.sgmwstarlight.stardash;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * USB stick storage. Clips are always recorded on the head unit; each finished clip is then copied to
 * StarDash/ on the stick (same sub-folders), checked, and removed from the head unit. If the stick is
 * missing the clip simply stays on the head unit. The app's own file access is tried first; if the stick
 * refuses it, the copy goes through {@link RootShell}.
 */
final class UsbStorage {
    private static final String TAG = "StarDash";
    static final String DIR = "StarDash";
    /** Space always left free on the stick. */
    static final long MIN_FREE = 512L << 20;

    static final class Vol {
        final File dir;
        final String uuid;
        final long total, free;

        Vol(File dir, String uuid, long total, long free) {
            this.dir = dir;
            this.uuid = uuid;
            this.total = total;
            this.free = free;
        }

        File root() {
            return new File(dir, DIR);
        }

        /** Largest quota: the stick's size in GB, rounded down, less 1 GB. */
        int maxQuotaGb() {
            return Math.max(1, (int) (total >> 30) - 1);
        }
    }

    /** True while SystemUI shows its USB icon (a removable volume is mounted, even read-only). */
    static volatile boolean iconShown;
    /** The app's own writes to the stick were refused; use the root shell for writes from now on. */
    private static volatile boolean viaShell;
    private static final Object busy = new Object();
    private static final LinkedBlockingQueue<File> queue = new LinkedBlockingQueue<>();
    private static Thread worker;
    /** Clips finished in USB mode while no stick was in; moved once one is back. Kept in prefs. */
    private static final String WAITING = "usbWaiting";

    private UsbStorage() {
    }

    /** The first writable USB stick, or null. Also updates {@link #iconShown}. */
    static Vol find(Context c) {
        boolean anyMounted = false;
        Vol found = null;
        try {
            StorageManager sm = c.getSystemService(StorageManager.class);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable() || v.isPrimary()) continue;
                String state = v.getState();
                boolean rw = Environment.MEDIA_MOUNTED.equals(state);
                if (!rw && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                anyMounted = true;
                File dir = v.getDirectory();
                if (dir == null && v.getUuid() != null) dir = new File("/storage/" + v.getUuid());
                if (!rw || dir == null || found != null) continue;
                StatFs s = new StatFs(dir.getPath());
                found = new Vol(dir, v.getUuid() == null ? dir.getName() : v.getUuid(), s.getTotalBytes(), s.getAvailableBytes());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "usb volume query failed: " + e);
        }
        iconShown = anyMounted;
        return found;
    }

    /** About once a second from the service: which stick (if any) the clip list and pruning use. */
    static Vol refresh(Context c) {
        Vol v = find(c);
        Storage.usbRoot = v == null ? null : v.root();
        RecorderService svc = RecorderService.instance;
        if (v != null && svc != null && svc.config.usbStorage()) {
            Set<String> waiting = waiting(svc.config);
            if (!waiting.isEmpty()) {
                svc.config.prefs().edit().remove(WAITING).apply();
                Log.i(TAG, "usb: stick back, moving " + waiting.size() + " waiting clip(s)");
                for (String path : waiting) submit(new File(path), svc.config);
            }
        }
        return v;
    }

    private static Set<String> waiting(Config config) {
        return new HashSet<>(config.prefs().getStringSet(WAITING, new HashSet<>()));
    }

    private static synchronized void addWaiting(Config config, File f) {
        Set<String> w = waiting(config);
        w.add(f.getPath());
        config.prefs().edit().putStringSet(WAITING, w).apply();
    }

    /** Makes StarDash/ on the stick; returns false if neither the app nor the root shell can. */
    static boolean prepare(Vol v) {
        synchronized (busy) {
            File root = v.root();
            if (!viaShell && (root.isDirectory() || root.mkdirs())) {
                // A folder may exist without the app being allowed to write into it; check once.
                File probe = new File(root, ".probe");
                try (FileOutputStream o = new FileOutputStream(probe)) {
                    o.write(1);
                } catch (IOException e) {
                    viaShell = true;
                }
                probe.delete();
                if (!viaShell) return true;
            }
            viaShell = true;
            Log.i(TAG, "usb: app writes refused, using the root shell");
            return RootShell.run("mkdir -p " + RootShell.q(shellPath(root, v)), 20).ok();
        }
    }

    /** Queues a finished clip for the stick (only in USB mode). */
    static synchronized void submit(File clip, Config config) {
        if (!config.usbStorage()) return;
        queue.offer(clip);
        if (worker == null) {
            worker = new Thread(() -> loop(config), "UsbMover");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void loop(Config config) {
        while (true) {
            File f;
            try {
                f = queue.take();
            } catch (InterruptedException e) {
                continue;
            }
            synchronized (busy) {
                if (!config.usbStorage() || !f.exists()) continue;
                RecorderService svc = RecorderService.instance;
                Vol v = svc == null ? null : find(svc);
                if (v == null) {
                    Log.i(TAG, "usb: no stick, " + f.getName() + " waits on the head unit");
                    addWaiting(config, f);
                    continue;
                }
                File dest = target(v, f);
                if (dest == null) continue;
                long t0 = SystemClock.elapsedRealtime(), mb = f.length() >> 20;
                if (move(f, dest, v)) {
                    Log.i(TAG, "usb: moved " + f.getName() + " (" + mb + " MB) in "
                            + (SystemClock.elapsedRealtime() - t0) + " ms" + (viaShell ? " via shell" : ""));
                    Storage.prune(v.root(), (long) config.usbQuotaGb(v.maxQuotaGb()) << 30, v.dir, MIN_FREE);
                }
            }
        }
    }

    /** Same path below StarDash/ on the stick as below StarDash/ on the head unit. */
    private static File target(Vol v, File f) {
        String root = Storage.ROOT.getPath() + "/";
        if (!f.getPath().startsWith(root)) return null;
        return new File(v.root(), f.getPath().substring(root.length()));
    }

    private static boolean move(File src, File dest, Vol v) {
        long len = src.length();
        File part = new File(dest.getPath() + ".part");
        if (!viaShell) {
            try {
                File parent = dest.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir refused");
                copy(src, part);
                if (part.length() == len && part.renameTo(dest)) {
                    Storage.deleteLocal(src);
                    return true;
                }
                part.delete();
                throw new IOException("copy check failed");
            } catch (IOException e) {
                Log.w(TAG, "usb: app copy failed (" + e.getMessage() + "), using the root shell");
                part.delete();
                viaShell = true;
            }
        }
        String d = shellPath(dest, v), p = shellPath(part, v);
        RootShell.Result r = RootShell.run("mkdir -p " + RootShell.q(new File(d).getParent()) + " && cp "
                + RootShell.q(src.getPath()) + " " + RootShell.q(p) + " && mv " + RootShell.q(p) + " " + RootShell.q(d)
                + " && stat -c %s " + RootShell.q(d), 900);
        if (r.ok() && r.out.trim().endsWith(String.valueOf(len))) {
            Storage.deleteLocal(src);
            return true;
        }
        Log.w(TAG, "usb: shell copy of " + src.getName() + " failed: " + r.out);
        return false;
    }

    /** Deletes a clip on the stick. */
    static boolean delete(File f) {
        if (!viaShell && f.delete()) return true;
        if (!f.exists()) return true;
        Vol v = volumeOf(f);
        return v != null && RootShell.run("rm -f " + RootShell.q(shellPath(f, v)), 30).ok();
    }

    /** Renames within the stick (lock / unlock). */
    static boolean rename(File from, File to) {
        if (!viaShell) {
            File dir = to.getParentFile();
            if (dir != null) dir.mkdirs();
            if (from.renameTo(to)) return true;
        }
        Vol v = volumeOf(from);
        if (v == null) return false;
        String t = shellPath(to, v);
        return RootShell.run("mkdir -p " + RootShell.q(new File(t).getParent()) + " && mv "
                + RootShell.q(shellPath(from, v)) + " " + RootShell.q(t), 30).ok();
    }

    /** Removes an empty folder on the stick. */
    static void removeEmptyDir(File dir) {
        String[] left = dir.list();
        if (left == null || left.length > 0) return;
        if (!viaShell && dir.delete()) return;
        Vol v = volumeOf(dir);
        if (v != null) RootShell.run("rmdir " + RootShell.q(shellPath(dir, v)), 10);
    }

    private static Vol volumeOf(File f) {
        RecorderService svc = RecorderService.instance;
        Vol v = svc == null ? null : find(svc);
        return v != null && f.getPath().startsWith(v.dir.getPath() + "/") ? v : null;
    }

    /**
     * Formats the stick as FAT32 (label STARDASH) and creates StarDash/ again.
     * Returns null on success or the reason it failed. Runs on a background thread.
     */
    static String format(Context c, Vol v) {
        synchronized (busy) {
            RootShell.Result list = RootShell.run("sm list-volumes public", 20);
            if (!list.ok()) return list.out.isEmpty() ? "无法读取存储设备列表" : list.out;
            String id = null;
            for (String line : list.out.split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 3 && p[0].startsWith("public:") && p[2].equalsIgnoreCase(v.uuid)) id = p[0];
            }
            if (id == null) return "找不到这个U盘对应的存储设备";
            Log.i(TAG, "usb: formatting " + id + " (" + v.uuid + ") as FAT32");
            RootShell.Result r = RootShell.run("sm unmount " + id + " && sleep 1 && newfs_msdos -F 32 -L STARDASH "
                    + RootShell.q("/dev/block/vold/" + id) + "; rc=$?; sm mount " + id + "; (exit $rc)", 600);
            Log.i(TAG, "usb: format result " + r.code + " " + r.out);
            viaShell = false;
            Vol fresh = null;
            for (int i = 0; i < 40 && fresh == null; i++) {
                SystemClock.sleep(500);
                fresh = find(c);
            }
            if (!r.ok()) return "格式化失败：" + lastLine(r.out);
            if (fresh == null) return "格式化完成，但U盘没有重新挂载，请拔出后重新插入";
            Storage.usbRoot = fresh.root();
            return prepare(fresh) ? null : "格式化完成，但无法在U盘上创建 StarDash 文件夹";
        }
    }

    private static String lastLine(String s) {
        String[] lines = s.trim().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    /** /storage/UUID/... as the root shell should write it: the stick's own mount, not the app view. */
    private static String shellPath(File f, Vol v) {
        String path = f.getPath(), base = v.dir.getPath();
        return path.startsWith(base) ? "/mnt/media_rw/" + v.dir.getName() + path.substring(base.length()) : path;
    }

    private static void copy(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        }
    }
}
