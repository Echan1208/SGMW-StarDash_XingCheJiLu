package android.car.bus;

import android.content.Context;

/** Compile-only stub; the real class is in /system/framework/android.car.jar on the head unit. */
public class SGMWBus {
    public SGMWBus(Context ctx) {
        throw new RuntimeException("Stub!");
    }

    public void subscribe(SGMWBusEventFilter filter, SGMWBusEventHandler handler) {
        throw new RuntimeException("Stub!");
    }

    public void unsubscribe(SGMWBusEventFilter filter) {
        throw new RuntimeException("Stub!");
    }

    public void publish(SGMWBusEvent event) {
        throw new RuntimeException("Stub!");
    }
}
