package com.sgmwstarlight.stardash;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Capture thread: shared mosaic -> NV12 (+ info bar) -> hardware encoder.
 * Drain thread: encoder output -> writer queue. Writer thread: 1-minute driving segments,
 * 10-minute parking time-lapse segments, and motion events with a short pre-roll.
 */
final class Recorder {
    private static final String TAG = "StarDash";
    static final int OUT_W = 1280, CAM_H = 960;
    private static final long DRIVE_SEGMENT_MS = 60_000, PARK_SEGMENT_MS = 600_000, EVENT_MAX_MS = 120_000;
    private static final long EVENT_COOLDOWN_MS = 30_000;
    private static final long PREROLL_US = 5_000_000, PARK_SYNC_US = 2_000_000;
    /** Moving blocks (of 300) needed to start an event, by sensitivity 1..5. */
    private static final int[] MOTION_START_BLOCKS = {8, 5, 3, 2, 1};
    /** A block moves when this many of its 64 samples changed. */
    private static final int BLOCK_MIN_SAMPLES = 16;

    enum Mode { DRIVE, PARKING, EVENT }

    private final Config config;
    private final VehicleState vehicle;
    private final InfoBar bar;
    private final AvmSource source = new AvmSource(15);

    private volatile boolean running, recordingWanted;
    private Thread captureThread, drainThread, writerThread;
    private MediaCodec codec;
    private boolean hevc;
    private int outH;
    private final ArrayDeque<long[]> tags = new ArrayDeque<>(); // {ptsUs, modeOrdinal, break}
    private final BlockingQueue<Sample> queue = new ArrayBlockingQueue<>(240);

    // Status for the UI
    volatile Mode mode = Mode.DRIVE;
    volatile boolean sourceOk, encoding;
    volatile float sourceFps, encodedFps;
    volatile String currentFile = "";
    volatile long segmentStartMs;
    volatile String lastError = "";
    volatile ByteBuffer latestFrame;
    volatile long latestFrameAt;
    /** Increments for every new 360 frame; floating views redraw when it changes. */
    volatile long frameSeq;
    private int fpsLogTick;
    private volatile boolean lockRequested;

    // Preview for the activity
    volatile boolean previewWanted;
    private final ByteBuffer previewRgba = ByteBuffer.allocateDirect(640 * 480 * 4);
    private final Bitmap previewBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
    private volatile long previewVersion;

    Recorder(Config config, VehicleState vehicle, InfoBar bar) {
        this.config = config;
        this.vehicle = vehicle;
        this.bar = bar;
    }

    // ---------------------------------------------------------------- lifecycle

    synchronized void start() {
        if (running) return;
        running = true;
        captureThread = new Thread(this::captureLoop, "Capture");
        captureThread.start();
    }

    synchronized void stop() {
        running = false;
        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        captureThread = null;
    }

    void setRecording(boolean on) {
        recordingWanted = on;
    }

    private volatile boolean restartRequested;

    /** Applies new encoder settings: closes the current file and starts a new one. */
    void restartEncoder() {
        restartRequested = true;
    }

    boolean recordingWanted() {
        return recordingWanted;
    }

    void lockCurrent() {
        lockRequested = true;
    }

    Bitmap copyPreview(Bitmap into, long[] version) {
        synchronized (previewBitmap) {
            if (version[0] == previewVersion) return null;
            version[0] = previewVersion;
            return previewBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    // ---------------------------------------------------------------- capture

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        ByteBuffer motionPrev = ByteBuffer.allocateDirect(160 * 120);
        long lastMotionNs = 0, parkedSinceMs = -1, lastPreviewNs = 0, nextTimelapseNs = 0, lastParkSyncUs = 0;
        long lastMinute = -1, fpsWindowStart = SystemClock.elapsedRealtime();
        int frames = 0, encoded = 0, motionHits = 0, lastLuma = 128, peakBlocks = 0;
        long lastMotionLogMs = 0;
        long motionQuietSinceMs = -1, eventStartMs = 0, parkingEnteredMs = 0, eventEndMs = -EVENT_COOLDOWN_MS;
        Mode lastFedMode = null;
        while (running) {
            if (!source.isOpen()) {
                try {
                    source.open();
                    sourceOk = true;
                } catch (IOException e) {
                    sourceOk = false;
                    lastError = "360 画面连接失败：" + e.getMessage();
                    sleep(2000);
                    continue;
                }
            }
            ByteBuffer frame;
            try {
                frame = source.next();
            } catch (IOException e) {
                Log.w(TAG, "source lost: " + e);
                sourceOk = false;
                source.close();
                sleep(1000);
                continue;
            }
            long nowNs = SystemClock.elapsedRealtimeNanos();
            long nowMs = nowNs / 1_000_000;
            latestFrame = frame;
            latestFrameAt = nowMs;
            frameSeq++;
            frames++;

            // ---- mode
            Mode want = Mode.DRIVE;
            boolean parkedNow = config.parking() && vehicle.known(VehicleState.GEAR) && vehicle.gear() == 10
                    && vehicle.speed() < 1f;
            if (parkedNow) {
                if (parkedSinceMs < 0) parkedSinceMs = nowMs;
                if (nowMs - parkedSinceMs >= config.parkDelaySec() * 1000L) want = Mode.PARKING;
            } else {
                parkedSinceMs = -1;
            }
            if (want == Mode.PARKING) {
                if (mode == Mode.DRIVE) {
                    parkingEnteredMs = nowMs;
                    motionHits = 0;
                    motionQuietSinceMs = -1;
                }
                // ---- motion detection, 10 times a second
                if (config.motion() && nowNs - lastMotionNs >= 100_000_000L) {
                    lastMotionNs = nowNs;
                    // Darker scenes are noisier, so they need a larger per-sample change.
                    int r = Native.motion(frame.duplicate(), motionPrev, lastLuma < 30 ? 20 : 12, BLOCK_MIN_SAMPLES);
                    int moving = r & 0xffff;
                    lastLuma = r >>> 16;
                    peakBlocks = Math.max(peakBlocks, moving);
                    if (nowMs - lastMotionLogMs >= 10_000) {
                        // For tuning: the busiest moment of the last 10 seconds.
                        Log.i(TAG, "parking motion peakBlocks=" + peakBlocks + " luma=" + lastLuma + " mode=" + mode);
                        lastMotionLogMs = nowMs;
                        peakBlocks = 0;
                    }
                    int start = MOTION_START_BLOCKS[Math.max(1, Math.min(5, config.motionLevel())) - 1];
                    boolean warm = nowMs - parkingEnteredMs > 2000 && nowMs - eventEndMs > EVENT_COOLDOWN_MS;
                    if (mode != Mode.EVENT) {
                        motionHits = warm && moving >= start ? motionHits + 1 : 0;
                        if (motionHits >= 3) {
                            want = Mode.EVENT;
                            eventStartMs = nowMs;
                            motionQuietSinceMs = -1;
                            Log.i(TAG, "motion event start blocks=" + moving);
                        }
                    } else {
                        want = Mode.EVENT;
                        if (moving < Math.max(1, (start + 1) / 2)) {
                            if (motionQuietSinceMs < 0) motionQuietSinceMs = nowMs;
                        } else {
                            motionQuietSinceMs = -1;
                        }
                        boolean quiet = motionQuietSinceMs > 0 && nowMs - motionQuietSinceMs >= 6000;
                        if ((quiet && nowMs - eventStartMs >= 10_000) || nowMs - eventStartMs >= EVENT_MAX_MS) {
                            want = Mode.PARKING;
                            motionHits = 0;
                            eventEndMs = nowMs;
                            Log.i(TAG, "motion event end after " + (nowMs - eventStartMs) / 1000 + " s");
                        }
                    }
                } else if (mode == Mode.EVENT) {
                    want = Mode.EVENT;
                }
            }
            mode = want;

            // ---- encoder lifecycle
            if (restartRequested && codec != null) {
                stopEncoder();
                lastFedMode = null;
            }
            restartRequested = false;
            if (recordingWanted && codec == null) startEncoder();
            if (!recordingWanted && codec != null) stopEncoder();

            if (codec != null) {
                boolean feed;
                boolean brk = false;
                long ptsUs = nowNs / 1000;
                if (mode == Mode.PARKING) {
                    long interval = 1_000_000_000L / Math.max(1, Math.min(10, config.timelapseFps()));
                    feed = nowNs >= nextTimelapseNs;
                    if (feed) nextTimelapseNs = Math.max(nextTimelapseNs + interval, nowNs - interval);
                    if (feed && ptsUs - lastParkSyncUs >= PARK_SYNC_US) {
                        requestSync();
                        lastParkSyncUs = ptsUs;
                    }
                } else {
                    feed = true;
                    long minute = System.currentTimeMillis() / DRIVE_SEGMENT_MS;
                    if (mode == Mode.DRIVE && minute != lastMinute) {
                        if (lastMinute >= 0) {
                            brk = true;
                            requestSync();
                        }
                        lastMinute = minute;
                    }
                }
                if (lastFedMode != mode && feed) {
                    // The encoder budgets bits per second of input time, so at 3 frames a second it would
                    // spend the whole driving bitrate on very few frames. Scale it to keep per-frame quality.
                    int full = config.bitrateMbps() * 1_000_000;
                    int fps = Math.max(1, Math.min(10, config.timelapseFps()));
                    setBitrate(mode == Mode.PARKING ? Math.max(300_000, full * fps / 25) : full);
                    requestSync();
                    lastFedMode = mode;
                }
                if (feed && feedEncoder(frame, ptsUs, mode, brk)) encoded++;
            }

            // ---- preview
            if (previewWanted && nowNs - lastPreviewNs >= 400_000_000L) {
                lastPreviewNs = nowNs;
                previewRgba.rewind();
                Native.preview(frame.duplicate(), previewRgba, 4);
                previewRgba.rewind();
                synchronized (previewBitmap) {
                    previewBitmap.copyPixelsFromBuffer(previewRgba);
                    previewVersion++;
                }
            }

            long elapsed = SystemClock.elapsedRealtime() - fpsWindowStart;
            if (elapsed >= 2000) {
                sourceFps = frames * 1000f / elapsed;
                encodedFps = encoded * 1000f / elapsed;
                if (++fpsLogTick % 5 == 0) {
                    Log.i(TAG, String.format(java.util.Locale.US, "fps source %.1f encoded %.1f mode %s",
                            sourceFps, encodedFps, mode));
                }
                frames = 0;
                encoded = 0;
                fpsWindowStart = SystemClock.elapsedRealtime();
            }
        }
        stopEncoder();
        source.close();
        sourceOk = false;
    }

    private void setBitrate(int bps) {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            codec.setParameters(b);
            Log.i(TAG, "bitrate " + bps + " for " + mode);
        } catch (IllegalStateException ignored) {
        }
    }

    private void requestSync() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    private boolean feedEncoder(ByteBuffer frame, long ptsUs, Mode m, boolean brk) {
        int index;
        try {
            index = codec.dequeueInputBuffer(15_000);
        } catch (IllegalStateException e) {
            lastError = "编码器异常：" + e.getMessage();
            stopEncoder();
            return false;
        }
        if (index < 0) return false;
        ByteBuffer raw = codec.getInputBuffer(index);
        int capacity = raw == null ? OUT_W * outH * 3 / 2 : raw.capacity();
        Image image = codec.getInputImage(index);
        if (image == null) {
            codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
            return false;
        }
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer barNv12 = config.infoBar() ? bar.ready() : null;
        Native.frame(frame.duplicate(), planes[0].getBuffer(), planes[0].getRowStride(), planes[1].getBuffer(),
                planes[1].getRowStride(), barNv12 == null ? null : barNv12.duplicate(), barNv12 == null ? 0 : InfoBar.H);
        synchronized (tags) {
            tags.add(new long[]{ptsUs, m.ordinal(), brk ? 1 : 0});
        }
        codec.queueInputBuffer(index, 0, Math.min(capacity, planes[0].getRowStride() * outH * 3 / 2), ptsUs, 0);
        return true;
    }

    private void startEncoder() {
        hevc = config.hevc();
        outH = config.infoBar() ? CAM_H + InfoBar.H : CAM_H;
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, OUT_W, outH);
        f.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateMbps() * 1_000_000);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        try {
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (IOException | RuntimeException e) {
            lastError = "编码器启动失败：" + e;
            Log.e(TAG, "encoder start failed", e);
            if (codec != null) codec.release();
            codec = null;
            recordingWanted = false;
            return;
        }
        synchronized (tags) {
            tags.clear();
        }
        queue.clear();
        final MediaCodec c = codec;
        drainThread = new Thread(() -> drainLoop(c), "Drain");
        writerThread = new Thread(this::writerLoop, "Writer");
        writerThread.start();
        drainThread.start();
        encoding = true;
        lastError = "";
        Log.i(TAG, "encoder started " + mime + " " + OUT_W + "x" + outH);
    }

    private void stopEncoder() {
        if (codec == null) return;
        MediaCodec c = codec;
        codec = null;
        encoding = false;
        try {
            drainThread.join(1500);
        } catch (InterruptedException ignored) {
        }
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        c.release();
        queue.offer(Sample.END);
        try {
            writerThread.join(5000);
        } catch (InterruptedException ignored) {
        }
        Log.i(TAG, "encoder stopped");
    }

    // ---------------------------------------------------------------- drain

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] csd = null;
        while (true) {
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 50_000);
            } catch (IllegalStateException e) {
                break;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = c.getOutputFormat();
                csd = concat(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"));
                continue;
            }
            if (index < 0) {
                if (codec != c) break; // stopping and nothing more pending
                continue;
            }
            ByteBuffer out = c.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (csd == null && out != null) {
                    csd = new byte[info.size];
                    out.position(info.offset);
                    out.get(csd);
                }
                c.releaseOutputBuffer(index, false);
                continue;
            }
            long[] tag = null;
            synchronized (tags) {
                while (!tags.isEmpty()) {
                    long[] t = tags.poll();
                    if (t[0] == info.presentationTimeUs) {
                        tag = t;
                        break;
                    }
                }
            }
            if (out != null && info.size > 0 && csd != null) {
                Sample s = new Sample();
                s.data = new byte[info.size];
                out.position(info.offset);
                out.get(s.data);
                s.ptsUs = info.presentationTimeUs;
                s.key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                s.mode = tag == null ? Mode.DRIVE : Mode.values()[(int) tag[1]];
                s.brk = tag != null && tag[2] == 1;
                s.csd = csd;
                if (!queue.offer(s)) Log.w(TAG, "writer queue full, frame dropped");
            }
            c.releaseOutputBuffer(index, false);
        }
    }

    private static byte[] concat(ByteBuffer a, ByteBuffer b) {
        int la = a == null ? 0 : a.remaining(), lb = b == null ? 0 : b.remaining();
        if (la + lb == 0) return null;
        byte[] r = new byte[la + lb];
        if (a != null) a.duplicate().get(r, 0, la);
        if (b != null) b.duplicate().get(r, la, lb);
        return r;
    }

    // ---------------------------------------------------------------- writer

    private static final class Sample {
        static final Sample END = new Sample();
        byte[] data;
        byte[] csd;
        long ptsUs;
        boolean key, brk;
        Mode mode;
    }

    private void writerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        Mp4Writer w = null;
        Mode curMode = null;
        long segStartPts = 0, segStartWall = 0, tlIndex = 0;
        boolean pendingBreak = false;
        ArrayDeque<Sample> ring = new ArrayDeque<>();
        while (true) {
            Sample s;
            try {
                s = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (s == Sample.END) break;
            if (s == null) {
                if (lockRequested && w != null) pendingBreak = true;
                continue;
            }
            if (s.brk || lockRequested) pendingBreak = true;

            // Keep a short pre-roll while in time-lapse, for the start of a motion event.
            if (s.mode == Mode.PARKING) {
                ring.add(s);
                while (!ring.isEmpty() && s.ptsUs - ring.peekFirst().ptsUs > PREROLL_US + PARK_SYNC_US) ring.pollFirst();
            }

            boolean due = false;
            if (w != null) {
                long wall = System.currentTimeMillis();
                if (curMode == Mode.PARKING) due = wall - segStartWall >= PARK_SEGMENT_MS;
                if (curMode == Mode.EVENT) due = wall - segStartWall >= EVENT_MAX_MS;
                if (curMode == Mode.DRIVE) due = wall - segStartWall >= DRIVE_SEGMENT_MS + 5000; // safety net
            }
            if (w == null || (s.key && (s.mode != curMode || pendingBreak || due))) {
                if (w == null && !s.key) continue;
                boolean lock = lockRequested;
                lockRequested = false;
                finish(w, lock);
                pendingBreak = false;
                curMode = s.mode;
                File file = Storage.newFile(curMode == Mode.PARKING ? Storage.Kind.PARKING
                        : curMode == Mode.EVENT ? Storage.Kind.EVENT : Storage.Kind.DRIVE, System.currentTimeMillis());
                try {
                    w = new Mp4Writer(file, hevc, OUT_W, outH, s.csd);
                } catch (IOException e) {
                    lastError = "无法写入存储：" + e.getMessage();
                    Log.e(TAG, "open failed", e);
                    w = null;
                    continue;
                }
                // A file opened, so an earlier open failure (e.g. storage granted since) no longer applies.
                if (lastError.startsWith("无法写入存储")) lastError = "";
                currentFile = file.getName();
                segmentStartMs = System.currentTimeMillis();
                segStartWall = segmentStartMs;
                segStartPts = s.ptsUs;
                tlIndex = 0;
                if (curMode == Mode.EVENT) {
                    // Pre-roll: from the oldest keyframe within the window, up to this frame.
                    Sample[] r = ring.toArray(new Sample[0]);
                    int from = -1;
                    for (int i = 0; i < r.length; i++) {
                        if (r[i].key && s.ptsUs - r[i].ptsUs <= PREROLL_US + PARK_SYNC_US) {
                            from = i;
                            break;
                        }
                    }
                    if (from >= 0) {
                        for (int i = from; i < r.length; i++) write(w, r[i], r[i].ptsUs);
                    }
                    ring.clear();
                }
            }
            long filePts = curMode == Mode.PARKING ? (tlIndex++) * 40_000L : s.ptsUs;
            if (curMode == Mode.PARKING && s.mode != Mode.PARKING) filePts = (tlIndex++) * 40_000L;
            write(w, s, filePts);
        }
        finish(w, lockRequested);
        lockRequested = false;
        currentFile = "";
    }

    private void write(Mp4Writer w, Sample s, long pts) {
        try {
            w.writeSample(s.data, s.data.length, pts, s.key);
        } catch (IOException e) {
            lastError = "写入失败：" + e.getMessage();
            Log.e(TAG, "write failed", e);
        }
    }

    private void finish(Mp4Writer w, boolean lock) {
        if (w == null) return;
        try {
            w.close();
            if (lock && w.target.exists()) Storage.lock(w.target);
        } catch (IOException e) {
            Log.e(TAG, "close failed", e);
        }
        Storage.prune((long) config.quotaGb() << 30);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
