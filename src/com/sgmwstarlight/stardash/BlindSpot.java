package com.sgmwstarlight.stardash;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Side-rear warning while the left or right floating view is up (turn signal). On the same flattened
 * side-rear picture the view shows, it finds people, two-wheelers and vehicles, puts each on the ground
 * and follows it from look to look. It warns only for things that are close AND moving on their own:
 * the car's own motion (speed, and the turn from the steering angle) is taken out first, so parked cars,
 * pillars and reflective strips, which only seem to move because the car does, stay quiet.
 * While the car is stopped it also warns for movement close by. A warning flashes the view's outer edge
 * red and beeps on that side.
 */
final class BlindSpot {
    private static final String TAG = "StarDash";
    /** Analysis picture: same framing as the floating view (4:3), sized for the detector. */
    static final int DW = 320, DH = 240;
    /** Side cameras sit about 1 m above the ground (measured). */
    private static final double CAM_HEIGHT = 1.0;
    /** Warning area on the ground, measured from the camera: up to this far behind ... */
    private static final double MAX_BEHIND = 10.0;
    /** ... and this far out to the side; closer in than MIN_OUT is the car's own body. */
    private static final double MIN_OUT = 0.3, MAX_OUT = 4.0;
    /** Camera position from the middle of the rear axle (m): out to the side, and forward. */
    private static final double CAM_X = 0.95, CAM_Z = 1.8;
    /** 五菱星光 wheelbase (m) and an assumed steering ratio. Steering angle: positive = right. */
    private static final double WHEELBASE = 2.8, STEER_RATIO = 15.5;
    /** Own ground speed (m/s) that counts as moving: person, two-wheeler, vehicle. */
    private static final double[] MOVING = {0.9, 1.3, 1.8};
    /** Detector score needed: person, two-wheeler, vehicle. */
    private static final float[] SCORE = {0.40f, 0.35f, 0.35f};
    private static final long HOLD_MS = 1200;
    /** A track needs this many looks over at least this long before its speed is trusted. */
    private static final int MIN_LOOKS = 3;
    private static final long MIN_SPAN_MS = 500, TRACK_WINDOW_MS = 1600, TRACK_LOST_MS = 1000;
    /** Movement without a detection only counts while stopped, and only this close. */
    private static final float MOTION_MAX_KMH = 2f;
    private static final double MOTION_BEHIND = 6.0, MOTION_OUT = 3.0;
    /** Motion grid (1/4 of the analysis picture each way). */
    private static final int MW = DW / 4, MH = DH / 4;
    /** One look every 300 ms: a look takes about 190 ms on this head unit's CPU. */
    private static final long PERIOD_MS = 300;

    /** What a side's floating view should show right now. */
    static final class State {
        static final State NONE = new State(false);
        final boolean alert;

        State(boolean alert) {
            this.alert = alert;
        }
    }

    /** One position of a followed object, in vehicle coordinates from the rear axle (X right, Z forward). */
    private static final class Sample {
        final long t;
        final double x, z, v, w;

        Sample(long t, double x, double z, double v, double w) {
            this.t = t;
            this.x = x;
            this.z = z;
            this.v = v;
            this.w = w;
        }
    }

    /** An object followed from look to look. */
    private static final class Track {
        final int group; // 0 person, 1 two-wheeler, 2 vehicle
        final List<Sample> samples = new ArrayList<>();
        String name;
        double behind, out;

        Track(int group) {
            this.group = group;
        }

        Sample last() {
            return samples.get(samples.size() - 1);
        }
    }

    /** Per side: the maps for the current custom-view adjustment, tracks, motion history and the alert. */
    private static final class Side {
        final MirrorController.Side side;
        final int sign; // -1 left, +1 right: X of an object = sign * (CAM_X + outward)
        float[] tune;
        MirrorController.ViewMap map;
        MirrorController.Rays rays;
        boolean[] zone;
        int zoneCount;
        byte[] prev;
        int motionStreak;
        long lastSeq = -1, alertUntil;
        /**
         * Sound for this showing of the view (one turn signal): 0 not yet, 1 the first warning is sounding
         * (two rounds, then silent), 2 used up. Later warnings in the same showing only flash the edge.
         */
        int soundStage;
        final List<Track> tracks = new ArrayList<>();
        volatile State state = State.NONE;

        Side(MirrorController.Side side) {
            this.side = side;
            this.sign = side == MirrorController.Side.LEFT ? -1 : 1;
        }
    }

    private final Context context;
    private final Config config;
    private final VehicleState vehicle;
    private final Recorder recorder;
    private final MirrorController mirror;
    private final Beeper beeper;
    private final Side left = new Side(MirrorController.Side.LEFT), right = new Side(MirrorController.Side.RIGHT);
    private final ByteBuffer rgba = ByteBuffer.allocateDirect(DW * DH * 4);
    private Thread thread;
    private int statTracks, statMoving;

    BlindSpot(Context context, Config config, VehicleState vehicle, Recorder recorder, MirrorController mirror) {
        this.context = context;
        this.config = config;
        this.vehicle = vehicle;
        this.recorder = recorder;
        this.mirror = mirror;
        this.beeper = new Beeper(context);
    }

    /** Debug (BSD_SOUND): the side whose view shows the warning until {@link #testUntil}. */
    private volatile MirrorController.Side testSide;
    private volatile long testUntil;

    /** What the floating view of {@code side} should draw. */
    State state(MirrorController.Side side) {
        if (side == testSide && SystemClock.elapsedRealtime() < testUntil) return new State(true);
        Side s = side == MirrorController.Side.LEFT ? left : side == MirrorController.Side.RIGHT ? right : null;
        return s == null ? State.NONE : s.state;
    }

    /**
     * Called when a side view appears. The analysis thread is started once and then kept for the life of
     * the process (sleeping while no side view is up): ncnn's OpenMP must always be called from the same
     * thread, a new calling thread once brought the whole process down.
     */
    synchronized void wake() {
        if (!config.blindSpot()) return;
        if (thread == null) {
            thread = new Thread(this::loop, "BlindSpot");
            thread.start();
        }
        notifyAll();
    }

    /** Plays the warning sound on one side for a few seconds (to check the left/right channels). */
    void testSound(MirrorController.Side side, long ms) {
        testSide = side;
        testUntil = SystemClock.elapsedRealtime() + ms;
        beeper.test(side == MirrorController.Side.LEFT, ms);
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        if (!Detector.ready(context)) Log.w(TAG, "bsd: detector missing, only movement is checked");
        long idleSince = -1, statStart = SystemClock.elapsedRealtime(), detNs = 0;
        int runs = 0, alerts = 0;
        boolean turn = false;
        while (true) {
            long t0 = SystemClock.elapsedRealtime();
            boolean l = config.blindSpot() && mirror.showing(MirrorController.Side.LEFT);
            boolean r = config.blindSpot() && mirror.showing(MirrorController.Side.RIGHT);
            if (!l) clear(left);
            if (!r) clear(right);
            if (!l && !r) {
                beeper.update(false, false);
                if (idleSince < 0) idleSince = t0;
                // After a few idle seconds, sleep until wake() (a side view appearing).
                synchronized (this) {
                    try {
                        wait(t0 - idleSince > 3000 ? 2000 : PERIOD_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
                statStart = SystemClock.elapsedRealtime();
                continue;
            }
            idleSince = -1;
            // Both views up (rare): take turns.
            Side s = l && r ? (turn ? right : left) : l ? left : right;
            turn = !turn;
            long cost = analyse(s);
            if (cost >= 0) {
                detNs += cost;
                runs++;
                if (s.state.alert) alerts++;
            }
            beeper.update(sound(left), sound(right));
            long now = SystemClock.elapsedRealtime();
            if (now - statStart >= 5000 && runs > 0) {
                Log.i(TAG, String.format(Locale.US, "bsd %.1f Hz, detect %.0f ms, tracks %d (moving %d), alert in %d of %d",
                        runs * 1000f / (now - statStart), detNs / 1e6 / runs, statTracks, statMoving, alerts, runs));
                statStart = now;
                detNs = 0;
                runs = alerts = statTracks = statMoving = 0;
            }
            long wait = PERIOD_MS - (now - t0);
            if (wait > 0) SystemClock.sleep(wait);
        }
    }

    /** Whether this side may beep now: only during the first warning since the view appeared. */
    private static boolean sound(Side s) {
        boolean alert = s.state.alert;
        if (alert && s.soundStage == 0) s.soundStage = 1;
        if (!alert && s.soundStage == 1) s.soundStage = 2;
        return alert && s.soundStage == 1;
    }

    private static void clear(Side s) {
        s.soundStage = 0;
        s.state = State.NONE;
        s.prev = null;
        s.motionStreak = 0;
        s.alertUntil = 0;
        s.tracks.clear();
    }

    private static int group(int cls) {
        return cls == 0 ? 0 : cls == 1 || cls == 3 ? 1 : 2;
    }

    /** One look at one side. Returns the detector time in ns, or -1 if there was no new frame. */
    private long analyse(Side s) {
        ByteBuffer frame = recorder.latestFrame;
        long seq = recorder.frameSeq;
        if (frame == null || seq == s.lastSeq) return -1;
        s.lastSeq = seq;
        prepare(s);
        MirrorController.ViewMap m = s.map;
        if (m.cache == null) return -1;
        rgba.rewind();
        Native.remapOffsets(frame.duplicate(), m.map, m.frac, DW * DH, rgba, m.rx, m.ry, m.rw, m.rh, m.cache);
        rgba.rewind();

        long now = SystemClock.elapsedRealtime();
        // The car's own motion: forward speed (backwards in R) and turn rate from the steering angle.
        double v = vehicle.speed() / 3.6;
        if (vehicle.gear() == 14) v = -v;
        double w = v * Math.tan(Math.toRadians(vehicle.steer() / STEER_RATIO)) / WHEELBASE;

        long t0 = System.nanoTime();
        List<Detector.Box> boxes = Detector.detect(rgba, DW, DH, 0.35f);
        long detNs = System.nanoTime() - t0;
        for (Detector.Box b : boxes) {
            int g = group(b.cls);
            if (b.score < SCORE[g]) continue;
            // Where the object stands: the middle of the box's bottom edge, put on the ground.
            double[] p = s.rays.ground((b.x1 + b.x2) / 2, Math.min(DH - 1, b.y2), CAM_HEIGHT);
            if (p == null || p[0] > MAX_BEHIND + 4) continue;
            double x = s.sign * (CAM_X + p[1]), z = CAM_Z - p[0];
            Track t = match(s, g, x, z, now);
            if (t == null) {
                t = new Track(g);
                s.tracks.add(t);
            }
            t.samples.add(new Sample(now, x, z, v, w));
            t.name = Detector.name(b.cls);
            t.behind = p[0];
            t.out = p[1];
        }
        boolean threat = false;
        Track reason = null;
        double reasonSpeed = 0;
        for (Iterator<Track> it = s.tracks.iterator(); it.hasNext(); ) {
            Track t = it.next();
            while (t.samples.size() > 1 && now - t.samples.get(0).t > TRACK_WINDOW_MS) t.samples.remove(0);
            if (now - t.last().t > TRACK_LOST_MS) {
                it.remove();
                continue;
            }
            statTracks++;
            double own = ownSpeed(t);
            if (own < MOVING[t.group]) continue;
            statMoving++;
            boolean inZone = t.behind <= MAX_BEHIND && t.behind >= -1.0 && t.out >= MIN_OUT && t.out <= MAX_OUT;
            if (inZone && t.last().t == now) {
                threat = true;
                reason = t;
                reasonSpeed = own;
            }
        }
        boolean moving = motion(s);

        if (threat || moving) {
            if (now >= s.alertUntil) {
                String what = reason != null ? String.format(Locale.US, "%s moving %.1f m/s at %.1f m behind, %.1f m out",
                        reason.name, reasonSpeed, reason.behind, reason.out) : "movement nearby";
                Log.i(TAG, "bsd " + s.side + " alert: " + what + String.format(Locale.US, " (car %.0f km/h, steer %.0f)",
                        vehicle.speed(), vehicle.steer()));
            }
            s.alertUntil = now + HOLD_MS;
        }
        s.state = now < s.alertUntil ? new State(true) : State.NONE;
        return detNs;
    }

    /** The track of the same kind nearest to where this one should be now, within reach. */
    private static Track match(Side s, int group, double x, double z, long now) {
        Track best = null;
        double bestD = Double.MAX_VALUE;
        for (Track t : s.tracks) {
            if (t.group != group) continue;
            Sample l = t.last();
            if (l.t == now) continue; // already matched in this look
            double dt = (now - l.t) / 1000.0;
            // Where a still object would be now, given the car's motion since the last look.
            double px = l.x - l.w * l.z * dt, pz = l.z + (-l.v + l.w * l.x) * dt;
            double d = Math.hypot(x - px, z - pz);
            double reach = 1.5 + 6.0 * dt; // room for its own movement (up to about 20 km/h) and noise
            if (d < reach && d < bestD) {
                best = t;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * The object's own ground speed (m/s): how fast it moved in the car's frame, minus how a still object
     * would have moved there because of the car's speed and turning. 0 until there are enough looks.
     */
    private static double ownSpeed(Track t) {
        List<Sample> ss = t.samples;
        int n = ss.size();
        if (n < MIN_LOOKS || ss.get(n - 1).t - ss.get(0).t < MIN_SPAN_MS) return 0;
        double mt = 0, mx = 0, mz = 0, mv = 0, mw = 0;
        long t0 = ss.get(0).t;
        for (Sample s : ss) {
            mt += (s.t - t0) / 1000.0;
            mx += s.x;
            mz += s.z;
            mv += s.v;
            mw += s.w;
        }
        mt /= n;
        mx /= n;
        mz /= n;
        mv /= n;
        mw /= n;
        double stt = 0, stx = 0, stz = 0;
        for (Sample s : ss) {
            double dt = (s.t - t0) / 1000.0 - mt;
            stt += dt * dt;
            stx += dt * (s.x - mx);
            stz += dt * (s.z - mz);
        }
        if (stt <= 0) return 0;
        double vx = stx / stt, vz = stz / stt;
        // A still point seen from a car moving forward at v and turning right at w (rad/s).
        double sx = -mw * mz, sz = -mv + mw * mx;
        return Math.hypot(vx - sx, vz - sz);
    }

    /** Builds the analysis map, rays and the near-ground mask for the side's current custom-view adjustment. */
    private void prepare(Side s) {
        float[] t = config.sideTune(s.side.name());
        if (s.map != null && java.util.Arrays.equals(t, s.tune)) return;
        s.tune = t;
        s.map = MirrorController.sideMap(s.side, t[0], t[1], t[2], DW, DH);
        s.rays = MirrorController.sideRays(s.side, t[0], t[1], t[2], DW, DH);
        s.zone = new boolean[MW * MH];
        s.zoneCount = 0;
        for (int y = 0; y < MH; y++) {
            for (int x = 0; x < MW; x++) {
                double[] g = s.rays.ground(x * 4 + 1.5, y * 4 + 1.5, CAM_HEIGHT);
                boolean in = g != null && g[0] <= MOTION_BEHIND && g[0] >= -1.0 && g[1] >= MIN_OUT && g[1] <= MOTION_OUT;
                s.zone[y * MW + x] = in;
                if (in) s.zoneCount++;
            }
        }
        s.prev = null;
        s.tracks.clear();
        Log.i(TAG, "bsd " + s.side + " map ready, near ground " + s.zoneCount + " of " + (MW * MH) + " cells");
    }

    /**
     * Movement close by since the last look, only while the car is stopped (so nothing moves just because
     * the car does). A uniform brightness change (the turn signal lamp lighting the ground) is subtracted
     * first; a change must cover 6% of the area for three looks in a row (about a second).
     */
    private boolean motion(Side s) {
        byte[] cur = new byte[MW * MH];
        for (int y = 0; y < MH; y++) {
            int row = (y * 4 + 1) * DW;
            for (int x = 0; x < MW; x++) {
                int p = (row + x * 4 + 1) * 4;
                int g = (rgba.get(p + 1) & 0xff) + (rgba.get(p + 5) & 0xff) + (rgba.get(p + DW * 4 + 1) & 0xff)
                        + (rgba.get(p + DW * 4 + 5) & 0xff);
                cur[y * MW + x] = (byte) (g >> 2);
            }
        }
        byte[] prev = s.prev;
        s.prev = cur;
        if (prev == null || s.zoneCount == 0 || vehicle.speed() >= MOTION_MAX_KMH) {
            s.motionStreak = 0;
            return false;
        }
        long sum = 0;
        for (int i = 0; i < cur.length; i++) if (s.zone[i]) sum += (cur[i] & 0xff) - (prev[i] & 0xff);
        int shift = (int) (sum / s.zoneCount);
        int changed = 0;
        for (int i = 0; i < cur.length; i++) {
            if (!s.zone[i]) continue;
            int d = (cur[i] & 0xff) - (prev[i] & 0xff) - shift;
            if (d > 25 || d < -25) changed++;
        }
        boolean now = changed >= Math.max(10, s.zoneCount * 6 / 100);
        s.motionStreak = now ? s.motionStreak + 1 : 0;
        return s.motionStreak >= 3;
    }
}
