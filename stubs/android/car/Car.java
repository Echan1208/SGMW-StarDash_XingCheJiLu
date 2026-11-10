package android.car;

import android.content.Context;

/** Compile-only stub; the real class is on the head unit's boot classpath (/system/framework/android.car.jar). */
public final class Car {
    public static final String PROPERTY_SERVICE = "property";

    public static Car createCar(Context context) {
        throw new RuntimeException("Stub!");
    }

    public Object getCarManager(String serviceName) {
        throw new RuntimeException("Stub!");
    }

    public void disconnect() {
        throw new RuntimeException("Stub!");
    }
}
