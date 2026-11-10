package android.car.hardware.property;

import android.car.hardware.CarPropertyValue;

/** Compile-only stub; the real class is on the head unit's boot classpath (/system/framework/android.car.jar). */
public class CarPropertyManager {
    public interface CarPropertyEventCallback {
        void onChangeEvent(CarPropertyValue value);

        void onErrorEvent(int propId, int zone);
    }

    public int getIntProperty(int prop, int area) {
        throw new RuntimeException("Stub!");
    }

    public float getFloatProperty(int prop, int area) {
        throw new RuntimeException("Stub!");
    }

    public boolean registerCallback(CarPropertyEventCallback callback, int propertyId, float updateRateHz) {
        throw new RuntimeException("Stub!");
    }

    public void unregisterCallback(CarPropertyEventCallback callback) {
        throw new RuntimeException("Stub!");
    }
}
