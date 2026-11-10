package com.sgmwstarlight.stardash;

import android.car.bus.SGMWBus;
import android.car.bus.SGMWBusEvent;
import android.car.bus.SGMWBusEventFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.content.Context;

/**
 * Drives the head unit's own dashcam (DVR) status bar icon through the SGMWBus, the same channel the
 * factory dashcam would use. SystemUI only shows the icon when persist.vendor.sgmw.support.dvr=1;
 * otherwise these messages are simply ignored.
 */
final class DvrStatusBar {
    static final int RECORDING = 1, EMERGENCY = 2, ABNORMAL = 3, CLOSED = 4, INIT = 5;
    private static final String EVENT_STATE = "DVR/State", KEY_STATE = "key_dvr_state";
    private static final String EVENT_BAR = "DVR/StatusBarState", KEY_BAR = "key_dvr_status_bar_state";
    private static final String TAG = "StarDash";

    private SGMWBus bus;
    private int last = -1;
    private long subscribedAt;

    /** @param onIconTapped runs when the user taps the status bar icon */
    void start(Context context, Runnable onIconTapped) {
        try {
            bus = new SGMWBus(context);
            SGMWBusEventFilter filter = new SGMWBusEventFilter();
            filter.addEventType(EVENT_BAR);
            subscribedAt = SystemClock.elapsedRealtime();
            bus.subscribe(filter, event -> {
                Bundle data = event.getData();
                int value = data == null ? -1 : data.getInt(KEY_BAR, -1);
                // Ignore the sticky value replayed when we subscribe; only react to a fresh tap (0 = open).
                if (value == 0 && SystemClock.elapsedRealtime() - subscribedAt > 3000) {
                    Log.i(TAG, "status bar dvr icon tapped");
                    onIconTapped.run();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "SGMWBus unavailable: " + t);
            bus = null;
        }
    }

    void update(int state) {
        if (bus == null || state == last) return;
        try {
            SGMWBusEvent event = new SGMWBusEvent();
            event.setEventType(EVENT_STATE);
            Bundle b = new Bundle();
            b.putInt(KEY_STATE, state);
            event.setData(b);
            event.setSticky(true); // SystemUI gets the latest state even if it starts after us
            bus.publish(event);
            last = state;
            Log.i(TAG, "dvr status bar state " + state);
        } catch (Throwable t) {
            Log.w(TAG, "dvr publish failed: " + t);
        }
    }
}
