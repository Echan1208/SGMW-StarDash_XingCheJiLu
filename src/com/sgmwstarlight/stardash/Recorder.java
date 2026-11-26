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
 * Capture thread: shared mosaic -> NV12 (+ info bar) -> hardware encoders.
 * The main encoder runs at full frame rate all the time: 1-minute driving segments, and while parked a
 * pre-roll ring that becomes the start of a motion event file, so events are 25 fps from the first frame.
 * While parked a second encoder takes a few frames a second for the 10-minute time-lapse segments.
 */
final class Recorder {
    private static final String TAG = "StarDash";
    static final int OUT_W = 1280, CAM_H = 960;
    private static final long DRIVE_SEGMENT_MS = 60_000, PARK_SEGMENT_MS = 600_000, EVENT_MAX_MS = 120_000;
    private static final long EVENT_COOLDOWN_MS = 30_000;
    private static final long PREROLL_US = 5_000_000, PARK_SYNC_US = 2_000_000;
    /** Extra ring length so the pre-roll can start on a keyframe (the main encoder makes one a second). */
    private static final long PREROLL_KEY_US = 1_500_000;
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
    private Thread captureThread;

    /** One hardware encoder with its drain and writer threads. */
    private static final class Enc {
        MediaCodec codec;
        final ArrayDeque<long[]> tags = new ArrayDeque<>(); // {ptsUs, modeOrdinal, break}
        final BlockingQueue<Sample> queue = new ArrayBlockingQueue<>(240);
        Thread drain, writer;
    }

    /** Full frame rate: driving files, parking pre-roll, event files. */
    private final Enc main = new Enc();
    /** Parking only: time-lapse files. */
    private final Enc lapse = new Enc();
    private long lapseRetryMs;
    private boolean hevc;
    private int outH;

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
    /** Lock the driving or event file being written / the time-lapse file being written. */
    private volatile boolean lockRequested, lapseLockRequested;
    private volatile String lapseFile = "";
    private volatile long lapseStartMs;

    // Preview for the activity
    volatile boolean previewWanted;
    /**
     * Sentry mode (set while the sentry screen is open): parking monitoring with motion events starts as
     * soon as the car is in P at standstill, without the usual delay and even if switched off in settings.
     */
    volatile boolean sentry;
    /** Debug hook: the next motion check starts an event regardless of what it sees. */
    volatile boolean forceEvent;
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
        // While parked without an event the only file being written is the time-lapse.
        if (mode == Mode.PARKING) lapseLockRequested = true;
        else lockRequested = true;
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
            boolean sentryNow = sentry;
            boolean parkedNow = (config.parking() || sentryNow) && vehicle.known(VehicleState.GEAR)
                    && vehicle.gear() == 10 && vehicle.speed() < 1f;
            if (parkedNow) {
                if (parkedSinceMs < 0) parkedSinceMs = nowMs;
                long delayMs = sentryNow ? 0 : config.parkDelaySec() * 1000L;
                if (nowMs - parkedSinceMs >= delayMs) want = Mode.PARKING;
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
                if ((config.motion() || sentryNow) && nowNs - lastMotionNs >= 100_000_000L) {
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
                        if (forceEvent) {
                            forceEvent = false;
                            motionHits = 3;
                            Log.i(TAG, "motion event forced (debug)");
                        }
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
                } else if (mode == Mode.EVENT && (config.motion() || sentryNow)) {
                    want = Mode.EVENT;
                }
            }
            mode = want;

            // ---- encoder lifecycle
            if (restartRequested) {
                stopEncoder(lapse);
                stopEncoder(main);
                lastFedMode = null;
            }
            restartRequested = false;
            boolean parked = mode != Mode.DRIVE;
            // While parked the full-rate encoder only feeds the event pre-roll, so it runs only if an event can
            // start (motion recording on, or sentry). Stopping it also closes the driving file.
            boolean mainWanted = recordingWanted && (!parked || mode == Mode.EVENT || config.motion() || sentryNow);
            if (!mainWanted && main.codec != null) stopEncoder(main);
            if ((!recordingWanted || !parked) && lapse.codec != null) stopEncoder(lapse);
            if (mainWanted && main.codec == null) {
                startEncoder(main);
                lastFedMode = null;
            }
            if (recordingWanted && parked && lapse.codec == null && nowMs >= lapseRetryMs) {
                startEncoder(lapse);
                nextTimelapseNs = 0;
                lastParkSyncUs = 0;
            }

            long ptsUs = nowNs / 1000;
            if (main.codec != null) {
                boolean brk = false;
                long minute = System.currentTimeMillis() / DRIVE_SEGMENT_MS;
                if (mode == Mode.DRIVE && minute != lastMinute) {
                    if (lastMinute >= 0) {
                        brk = true;
                        requestSync(main);
                    }
                    lastMinute = minute;
                }
                if (lastFedMode != mode) {
                    requestSync(main);
                    lastFedMode = mode;
                }
                if (feedEncoder(main, frame, ptsUs, mode, brk)) encoded++;
            }
            if (lapse.codec != null) {
                long interval = 1_000_000_000L / Math.max(1, Math.min(10, config.timelapseFps()));
                if (nowNs >= nextTimelapseNs) {
                    nextTimelapseNs = Math.max(nextTimelapseNs + interval, nowNs - interval);
                    if (ptsUs - lastParkSyncUs >= PARK_SYNC_US) {
                        requestSync(lapse);
                        lastParkSyncUs = ptsUs;
                    }
                    // Without the full-rate encoder the time-lapse is all that is encoded; show its rate.
                    if (feedEncoder(lapse, frame, ptsUs, Mode.PARKING, false) && main.codec == null) encoded++;
                }
            }

            // ---- preview
            if (previewWanted && nowNs - lastPreviewNs >= 400_000_000L) {
                lastPreviewNs = nowNs;
                previewRgba.rewind();
                Native.preview(frame.duplicate(), previewRgba, 4);
                previewRgba.rewind();
                synchronized (previewBitmap) {
                    previewBitmap.copyPixelsFromBuffer(previewRgba);
                    if (config.camLabels()) CamLabels.drawPreview(previewBitmap);
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
        stopEncoder(lapse);
        stopEncoder(main);
        source.close();
        sourceOk = false;
    }

    private static void requestSync(Enc e) {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            e.codec.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    private boolean feedEncoder(Enc e, ByteBuffer frame, long ptsUs, Mode m, boolean brk) {
        MediaCodec codec = e.codec;
        int index;
        try {
            index = codec.dequeueInputBuffer(15_000);
        } catch (IllegalStateException ex) {
            lastError = "编码器异常：" + ex.getMessage();
            stopEncoder(e);
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
        if (config.camLabels()) {
            CamLabels.drawNv12(planes[0].getBuffer(), planes[0].getRowStride(), planes[1].getBuffer(), planes[1].getRowStride());
        }
        synchronized (e.tags) {
            e.tags.add(new long[]{ptsUs, m.ordinal(), brk ? 1 : 0});
        }
        codec.queueInputBuffer(index, 0, Math.min(capacity, planes[0].getRowStride() * outH * 3 / 2), ptsUs, 0);
        return true;
    }

    private void startEncoder(Enc e) {
        boolean isMain = e == main;
        // Both encoders must write the same format; take the settings only when the other one is not running.
        if ((isMain ? lapse : main).codec == null) {
            hevc = config.hevc();
            outH = config.infoBar() ? CAM_H + InfoBar.H : CAM_H;
        }
        int full = config.bitrateMbps() * 1_000_000;
        // The encoder budgets bits per second of input time, so at 3 frames a second it would spend the whole
        // driving bitrate on very few frames. Scale it to keep the per-frame quality of driving.
        int fps = Math.max(1, Math.min(10, config.timelapseFps()));
        int bitrate = isMain ? full : Math.max(300_000, full * fps / 25);
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, OUT_W, outH);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        MediaCodec c = null;
        try {
            c = MediaCodec.createEncoderByType(mime);
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
        } catch (IOException | RuntimeException ex) {
            lastError = "编码器启动失败：" + ex;
            Log.e(TAG, (isMain ? "encoder" : "time-lapse encoder") + " start failed", ex);
            if (c != null) c.release();
            if (isMain) recordingWanted = false;
            else lapseRetryMs = SystemClock.elapsedRealtime() + 10_000;
            return;
        }
        synchronized (e.tags) {
            e.tags.clear();
        }
        e.queue.clear();
        e.codec = c;
        final MediaCodec started = c;
        e.drain = new Thread(() -> drainLoop(e, started), isMain ? "Drain" : "LapseDrain");
        e.writer = isMain ? new Thread(this::writerLoop, "Writer") : new Thread(this::lapseWriterLoop, "LapseWriter");
        e.writer.start();
        e.drain.start();
        encoding = true;
        lastError = "";
        Log.i(TAG, (isMain ? "encoder" : "time-lapse encoder") + " started " + mime + " " + OUT_W + "x" + outH
                + " " + bitrate + " bps");
    }

    private void stopEncoder(Enc e) {
        if (e.codec == null) return;
        MediaCodec c = e.codec;
        e.codec = null;
        encoding = (e == main ? lapse : main).codec != null;
        try {
            e.drain.join(1500);
        } catch (InterruptedException ignored) {
        }
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        c.release();
        e.queue.offer(Sample.END);
        try {
            e.writer.join(5000);
        } catch (InterruptedException ignored) {
        }
        Log.i(TAG, (e == main ? "encoder" : "time-lapse encoder") + " stopped");
    }

    // ---------------------------------------------------------------- drain

    private void drainLoop(Enc e, MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] csd = null;
        while (true) {
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 50_000);
            } catch (IllegalStateException ex) {
                break;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = c.getOutputFormat();
                csd = concat(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"));
                continue;
            }
            if (index < 0) {
                if (e.codec != c) break; // stopping and nothing more pending
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
            synchronized (e.tags) {
                while (!e.tags.isEmpty()) {
                    long[] t = e.tags.poll();
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
                if (!e.queue.offer(s)) Log.w(TAG, "writer queue full, frame dropped");
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

    /** Main stream: driving segments; while parked a pre-roll ring that starts each event file. */
    private void writerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        Mp4Writer w = null;
        Mode curMode = null;
        long segStartWall = 0;
        boolean pendingBreak = false;
        ArrayDeque<Sample> ring = new ArrayDeque<>();
        while (true) {
            Sample s;
            try {
                s = main.queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (s == Sample.END) break;
            if (s == null) {
                if (lockRequested && w != null) pendingBreak = true;
                continue;
            }

            if (s.mode == Mode.PARKING) {
                // Parked: the time-lapse encoder writes the file; here only keep the last few seconds.
                if (w != null) {
                    boolean lock = lockRequested;
                    lockRequested = false;
                    finish(w, lock);
                    w = null;
                    curMode = null;
                    currentFile = lapseFile;
                    segmentStartMs = lapseStartMs;
                }
                pendingBreak = false;
                ring.add(s);
                while (ring.size() > 1 && s.ptsUs - ring.peekFirst().ptsUs > PREROLL_US + PREROLL_KEY_US) ring.pollFirst();
                continue;
            }
            if (s.brk || lockRequested) pendingBreak = true;

            boolean due = false;
            if (w != null) {
                long wall = System.currentTimeMillis();
                if (curMode == Mode.EVENT) due = wall - segStartWall >= EVENT_MAX_MS;
                if (curMode == Mode.DRIVE) due = wall - segStartWall >= DRIVE_SEGMENT_MS + 5000; // safety net
            }
            boolean eventStart = s.mode == Mode.EVENT && curMode != Mode.EVENT;
            if (w == null || eventStart || (s.key && (s.mode != curMode || pendingBreak || due))) {
                // Pre-roll: the ring is every frame up to this one, so start at its oldest keyframe in the window.
                Sample[] pre = eventStart ? ring.toArray(new Sample[0]) : new Sample[0];
                int from = -1;
                for (int i = 0; i < pre.length; i++) {
                    if (pre[i].key && s.ptsUs - pre[i].ptsUs <= PREROLL_US + PREROLL_KEY_US) {
                        from = i;
                        break;
                    }
                }
                if (from < 0 && !s.key) {
                    if (w == null) continue;
                    // Wait for a keyframe before switching files.
                    write(w, s, s.ptsUs);
                    continue;
                }
                boolean lock = lockRequested;
                lockRequested = false;
                finish(w, lock);
                w = null;
                pendingBreak = false;
                curMode = s.mode;
                File file = Storage.newFile(curMode == Mode.EVENT ? Storage.Kind.EVENT : Storage.Kind.DRIVE,
                        System.currentTimeMillis());
                try {
                    w = new Mp4Writer(file, hevc, OUT_W, outH, s.csd);
                } catch (IOException e) {
                    lastError = "无法写入存储：" + e.getMessage();
                    Log.e(TAG, "open failed", e);
                    curMode = null;
                    continue;
                }
                // A file opened, so an earlier open failure (e.g. storage granted since) no longer applies.
                if (lastError.startsWith("无法写入存储")) lastError = "";
                currentFile = file.getName();
                segmentStartMs = System.currentTimeMillis();
                segStartWall = segmentStartMs;
                if (from >= 0) {
                    for (int i = from; i < pre.length; i++) write(w, pre[i], pre[i].ptsUs);
                    Log.i(TAG, "event pre-roll " + (pre.length - from) + " frames, "
                            + (s.ptsUs - pre[from].ptsUs) / 1000 + " ms");
                }
                ring.clear();
            }
            write(w, s, s.ptsUs);
        }
        finish(w, lockRequested);
        lockRequested = false;
        currentFile = lapseFile;
    }

    /** Time-lapse stream: 10-minute parking files played back at 25 fps. */
    private void lapseWriterLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        Mp4Writer w = null;
        long segStartWall = 0, index = 0;
        while (true) {
            Sample s;
            try {
                s = lapse.queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (s == Sample.END) break;
            if (s == null) continue;
            boolean due = w != null && (lapseLockRequested || System.currentTimeMillis() - segStartWall >= PARK_SEGMENT_MS);
            if (w == null || (s.key && due)) {
                if (w == null && !s.key) continue;
                boolean lock = lapseLockRequested;
                lapseLockRequested = false;
                finish(w, lock);
                w = null;
                File file = Storage.newFile(Storage.Kind.PARKING, System.currentTimeMillis());
                try {
                    w = new Mp4Writer(file, hevc, OUT_W, outH, s.csd);
                } catch (IOException e) {
                    lastError = "无法写入存储：" + e.getMessage();
                    Log.e(TAG, "open failed", e);
                    continue;
                }
                if (lastError.startsWith("无法写入存储")) lastError = "";
                segStartWall = System.currentTimeMillis();
                lapseFile = file.getName();
                lapseStartMs = segStartWall;
                if (mode != Mode.EVENT) {
                    currentFile = lapseFile;
                    segmentStartMs = lapseStartMs;
                }
                index = 0;
            }
            write(w, s, (index++) * 40_000L);
        }
        finish(w, lapseLockRequested);
        lapseLockRequested = false;
        if (currentFile.equals(lapseFile)) currentFile = "";
        lapseFile = "";
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
            File done = w.target;
            if (lock && done.exists()) {
                File locked = Storage.lock(done);
                if (locked != null) done = locked;
            }
            if (done.exists()) UsbStorage.submit(done, config);
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
