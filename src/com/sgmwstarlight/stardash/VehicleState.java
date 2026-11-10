package com.sgmwstarlight.stardash;

import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

/**
 * Live vehicle signals from the head unit's VHAL (2025 Wuling Starlight). Read only; nothing is
 * ever written to the vehicle.
 */
final class VehicleState {
    // Vendor property IDs of this head unit, in decimal.
    static final int SPEED = 559940320;      // 0x216002e0 VEHICLE_SPEED_AVERAGE_DRIVEN_ABS, km/h
    static final int GEAR = 557842502;       // 0x21400046 VEHICLE_ACTUAL_GEAR_STATUS: P10 D12 N13 R14
    static final int STEER = 559940335;      // 0x216002ef STEERING_WHEEL_ANGLE, degrees
    static final int BRAKE = 557842565;      // 0x21400085 BRAKEPEDALAPPLIED
    static final int ACCEL = 559939637;      // 0x21600035 ACCELERATOR_ACTUAL_POSITION
    // AUTOMATIC_VEHICLE_HOLD_STATUS (557843161) stays 0 while AutoHold holds on this model, so AutoHold is
    // derived instead: D/R, standing still, pedal released, brake lights still on.
    static final int BRAKE_LIGHT = 557842642; // 0x214000d2 BRAKE_LIGHT_ACTIVE
    static final int LEFT_TURN = 557842640;  // 0x214000d0 LEFT_TURN_LAMP_ACTIVE
    static final int RIGHT_TURN = 557842639; // 0x214000cf RIGHT_TURN_LAMP_ACTIVE
    static final int POSITION = 557842631;   // 0x214000c7 POSITION_LIGHT_ACTIVE (side/position lights)
    static final int EPB = 557843178;        // 0x214002ea ELECTRIC_PARK_BRAKE_STATUS: 2 in P (applied), 1 while driving
    static final int LOW_BEAM = 557842627;   // 0x214000c3 LOW_BEAM_INDICATION_ON
    static final int HIGH_BEAM = 557842622;  // 0x214000be HIGH_BEAM_INDICATION_ON

    static final int[] IDS = {SPEED, GEAR, STEER, BRAKE, ACCEL, BRAKE_LIGHT, EPB, LEFT_TURN, RIGHT_TURN,
            POSITION, LOW_BEAM, HIGH_BEAM};
    static final String[] NAMES = {"车速", "档位", "方向盘角度", "刹车踏板", "油门开度", "刹车灯", "电子手刹",
            "左转向灯", "右转向灯", "示廓灯", "近光灯", "远光灯"};
    // Brake lights can stay on about 1 s after the pedal is released without AutoHold.
    private static final long AVH_DEBOUNCE_MS = 1500;
    private long avhCandidateSinceMs = -1;
    private static final long TURN_LATCH_MS = 1200; // the lamp signal blinks; hold it on between blinks

    private final SparseArray<Object> values = new SparseArray<>();
    private final SparseArray<Boolean> ok = new SparseArray<>();
    private volatile long leftSeenMs = -100000, rightSeenMs = -100000;
    private volatile int version;
    private Car car;
    private CarPropertyManager props;

    private final CarPropertyManager.CarPropertyEventCallback callback = new CarPropertyManager.CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value) {
            int id = value.getPropertyId();
            Object v = value.getValue();
            synchronized (values) {
                values.put(id, v);
            }
            if (id == LEFT_TURN && asInt(v) == 1) leftSeenMs = SystemClock.elapsedRealtime();
            if (id == RIGHT_TURN && asInt(v) == 1) rightSeenMs = SystemClock.elapsedRealtime();
            version++;
        }

        @Override
        public void onErrorEvent(int propId, int zone) {
        }
    };

    void start(Context context) {
        try {
            car = Car.createCar(context);
            props = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
        } catch (Throwable t) {
            Log.w("StarDash", "car service unavailable", t);
            return;
        }
        for (int id : IDS) {
            boolean continuous = id == SPEED || id == STEER || id == ACCEL;
            boolean registered;
            try {
                registered = props.registerCallback(callback, id, continuous ? 10f : 0f);
            } catch (Throwable t) {
                registered = false;
                Log.w("StarDash", "register failed 0x" + Integer.toHexString(id) + " " + t);
            }
            ok.put(id, registered);
            // On-change properties are not pushed until they change on this head unit, so read them once now.
            if (registered) {
                try {
                    Object v = (id & 0x00ff0000) == 0x00600000 ? (Object) props.getFloatProperty(id, 0)
                            : (Object) props.getIntProperty(id, 0);
                    synchronized (values) {
                        if (values.get(id) == null) values.put(id, v);
                    }
                } catch (Throwable t) {
                    Log.w("StarDash", "initial read failed 0x" + Integer.toHexString(id) + " " + t);
                }
            }
        }
    }

    void stop() {
        try {
            if (props != null) props.unregisterCallback(callback);
            if (car != null) car.disconnect();
        } catch (Throwable ignored) {
        }
    }

    int version() {
        return version;
    }

    boolean available(int id) {
        Boolean b = ok.get(id);
        return b != null && b;
    }

    Object raw(int id) {
        synchronized (values) {
            return values.get(id);
        }
    }

    boolean known(int id) {
        return raw(id) != null;
    }

    float speed() { return asFloat(raw(SPEED)); }
    int gear() { Object v = raw(GEAR); return v == null ? -1 : asInt(v); }
    float steer() { return asFloat(raw(STEER)); }
    boolean brake() { return asInt(raw(BRAKE)) == 1; }
    float accel() { return asFloat(raw(ACCEL)); }
    /** AutoHold holding the car: in D or R, standing still, pedal released, brake lights on, for 1.5 s. */
    synchronized boolean autoHold() {
        int g = gear();
        boolean candidate = (g == 12 || g == 14) && known(SPEED) && speed() < 0.5f && !brake()
                && asInt(raw(BRAKE_LIGHT)) == 1;
        long now = SystemClock.elapsedRealtime();
        if (!candidate) {
            avhCandidateSinceMs = -1;
            return false;
        }
        if (avhCandidateSinceMs < 0) avhCandidateSinceMs = now;
        return now - avhCandidateSinceMs >= AVH_DEBOUNCE_MS;
    }
    boolean positionLight() { return asInt(raw(POSITION)) == 1; }
    boolean epbApplied() { return asInt(raw(EPB)) == 2; }
    boolean lowBeam() { return asInt(raw(LOW_BEAM)) == 1; }
    boolean highBeam() { return asInt(raw(HIGH_BEAM)) == 1; }

    boolean leftTurn() {
        return asInt(raw(LEFT_TURN)) == 1 || SystemClock.elapsedRealtime() - leftSeenMs < TURN_LATCH_MS;
    }

    boolean rightTurn() {
        return asInt(raw(RIGHT_TURN)) == 1 || SystemClock.elapsedRealtime() - rightSeenMs < TURN_LATCH_MS;
    }

    static String gearName(int g) {
        switch (g) {
            case 10: return "P";
            case 12: return "D";
            case 13: return "N";
            case 14: return "R";
            default: return "-";
        }
    }

    static int asInt(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Float) return Math.round((Float) v);
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof Long) return (int) (long) (Long) v;
        return 0;
    }

    static float asFloat(Object v) {
        if (v instanceof Float) return (Float) v;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Double) return (float) (double) (Double) v;
        return 0f;
    }
}
