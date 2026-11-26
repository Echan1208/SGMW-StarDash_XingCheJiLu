package com.sgmwstarlight.stardash;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The side-rear warning sound, repeated while the warning lasts, on the left speakers for the left view
 * and the right speakers for the right view (user-checked). Built in: a soft "嘟嘟嘟" of three short
 * chime notes that ring out instead of a hard beep. A 16-bit PCM WAV at StarDash/alert.wav (internal
 * storage) replaces it. It plays as a system sound (USAGE_ASSISTANCE_SONIFICATION, the bus the car does
 * not turn down in R) and briefly ducks music.
 */
final class Beeper {
    private static final String TAG = "StarDash";
    private static final int RATE = 48000;
    /** Three chime notes 150 ms apart, then a pause: about 0.9 s per burst. */
    private static final int NOTE_MS = 150, BEEPS = 3, PAUSE_MS = 450;
    /** A5 with a soft octave; fast attack, ringing decay. */
    /** Chime level; 0.70 is clearly audible from one side's speakers without being harsh. */
    private static final double FREQ = 880, DECAY_S = 0.065, LEVEL = 0.70;
    static final java.io.File CUSTOM = new java.io.File(Storage.ROOT, "alert.wav");
    private long customStamp = -1;

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioTrack leftTrack, rightTrack;
    private boolean leftOn, rightOn;
    private AudioFocusRequest focus;

    Beeper(Context context) {
        this.context = context;
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Sound on or off for each side (any thread). */
    synchronized void update(boolean left, boolean right) {
        RecorderService svc = RecorderService.instance;
        boolean sound = svc == null || svc.config.blindSpotSound();
        left &= sound;
        right &= sound;
        if (left == leftOn && right == rightOn) return;
        if (!leftOn && !rightOn) takeFocus();
        if (left != leftOn) leftTrack = set(leftTrack, left, true);
        if (right != rightOn) rightTrack = set(rightTrack, right, false);
        leftOn = left;
        rightOn = right;
        if (!leftOn && !rightOn) dropFocus();
    }

    /** Plays one side for {@code ms} regardless of the warning (to check the channels). */
    void test(boolean left, long ms) {
        Log.i(TAG, "bsd sound test " + (left ? "left" : "right"));
        update(left, !left);
        main.postDelayed(() -> update(false, false), ms);
    }

    /** Starts (looping) or stops one side's track; the track is made on first use or when alert.wav changes. */
    private AudioTrack set(AudioTrack t, boolean on, boolean left) {
        try {
            if (on) {
                long stamp = CUSTOM.exists() ? CUSTOM.lastModified() + CUSTOM.length() : 0;
                if (stamp != customStamp) {
                    customStamp = stamp;
                    if (leftTrack != null) leftTrack.release();
                    if (rightTrack != null) rightTrack.release();
                    leftTrack = rightTrack = null;
                    t = null;
                }
                if (t == null) t = make(left);
                t.stop();
                t.reloadStaticData();
                // The burst plays twice and then the track falls silent (two rounds are enough).
                t.setLoopPoints(0, t.getBufferSizeInFrames(), 1);
                t.play();
            } else if (t != null) {
                t.pause();
                t.flush();
                t.stop();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bsd sound failed: " + e);
        }
        return t;
    }

    /** One burst as 16-bit stereo with sound in one channel only: alert.wav if usable, else the chime. */
    private static AudioTrack make(boolean left) {
        int rate = RATE;
        short[] mono = null;
        if (CUSTOM.exists()) {
            int[] r = new int[1];
            mono = readWav(CUSTOM, r);
            if (mono != null) {
                rate = r[0];
                Log.i(TAG, "bsd sound: " + CUSTOM + " (" + mono.length * 1000L / rate + " ms)");
            }
        }
        if (mono == null) mono = chime();
        int pause = PAUSE_MS * rate / 1000;
        short[] pcm = new short[(mono.length + pause) * 2];
        for (int i = 0; i < mono.length; i++) pcm[i * 2 + (left ? 0 : 1)] = mono[i];
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    /** Three soft chime notes: quick attack, exponential ring-out, a little octave for body. */
    private static short[] chime() {
        int note = NOTE_MS * RATE / 1000, attack = RATE * 4 / 1000;
        short[] out = new short[BEEPS * note];
        for (int b = 0; b < BEEPS; b++) {
            for (int i = 0; i < note; i++) {
                double t = i / (double) RATE;
                double env = Math.min(1.0, i / (double) attack) * Math.exp(-t / DECAY_S);
                // Fade the tail to zero so the next note starts clean.
                env *= Math.min(1.0, (note - 1 - i) / (double) attack);
                double v = (Math.sin(2 * Math.PI * FREQ * t) + 0.3 * Math.sin(2 * Math.PI * 2 * FREQ * t)) / 1.3;
                out[b * note + i] = (short) (v * env * LEVEL * 32767);
            }
        }
        return out;
    }

    /** 16-bit PCM WAV (mono or stereo, any rate) mixed to mono; rate in r[0]. Null if not that format. */
    private static short[] readWav(java.io.File f, int[] r) {
        try {
            byte[] d = java.nio.file.Files.readAllBytes(f.toPath());
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (d.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) return null; // RIFF WAVE
            int pos = 12, channels = 0, bits = 0, rate = 0;
            while (pos + 8 <= d.length) {
                int id = b.getInt(pos), len = b.getInt(pos + 4);
                if (id == 0x20746d66) { // "fmt "
                    if (b.getShort(pos + 8) != 1) return null; // PCM only
                    channels = b.getShort(pos + 10);
                    rate = b.getInt(pos + 12);
                    bits = b.getShort(pos + 22);
                } else if (id == 0x61746164 && channels > 0) { // "data"
                    if (bits != 16 || rate < 8000 || channels > 2) return null;
                    int n = Math.min(len, d.length - pos - 8) / (2 * channels);
                    n = Math.min(n, rate * 5); // at most 5 s
                    short[] out = new short[n];
                    for (int i = 0; i < n; i++) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) sum += b.getShort(pos + 8 + (i * channels + c) * 2);
                        out[i] = (short) (sum / channels);
                    }
                    r[0] = rate;
                    return out;
                }
                pos += 8 + len + (len & 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "bsd sound: cannot read " + f + ": " + e);
        }
        return null;
    }

    /** The car keeps a bus muted unless someone holds focus; ask for a short, ducking focus. */
    private void takeFocus() {
        if (audio == null) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setOnAudioFocusChangeListener(c -> { })
                .build();
        try {
            audio.requestAudioFocus(focus);
        } catch (RuntimeException e) {
            Log.w(TAG, "bsd focus failed: " + e);
        }
    }

    private void dropFocus() {
        if (audio == null || focus == null) return;
        try {
            audio.abandonAudioFocusRequest(focus);
        } catch (RuntimeException ignored) {
        }
        focus = null;
    }
}
