package com.sgmwstarlight.stardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Clip thumbnails: one frame per file, decoded once with the hardware decoder and cached as JPEG. */
final class Thumbs {
    static final int W = 400, H = 315;
    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(64 * W * H * 4) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    Thumbs(Context c) {
        dir = new File(c.getCacheDir(), "thumbs");
        dir.mkdirs();
    }

    void load(ImageView view, File clip) {
        String key = clip.getName();
        view.setTag(key);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        worker.execute(() -> {
            if (!key.equals(view.getTag())) return; // scrolled away before we got to it
            Bitmap b = fromDisk(key);
            if (b == null) b = extract(clip, key);
            if (b == null) return;
            memory.put(key, b);
            Bitmap result = b;
            main.post(() -> {
                if (key.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    private Bitmap fromDisk(String key) {
        File f = new File(dir, key + ".jpg");
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private Bitmap extract(File clip, String key) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getPath());
            Bitmap b = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H);
            if (b == null) b = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H);
            if (b == null) return null;
            try (FileOutputStream out = new FileOutputStream(new File(dir, key + ".jpg"))) {
                b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            return b;
        } catch (RuntimeException | IOException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    /** Drops cached thumbnails whose clips no longer exist (pruned or deleted). */
    void trim(java.util.Set<String> alive) {
        worker.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".jpg") && !alive.contains(name.substring(0, name.length() - 4))) f.delete();
            }
        });
    }
}
