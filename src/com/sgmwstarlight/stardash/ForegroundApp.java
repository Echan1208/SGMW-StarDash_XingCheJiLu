package com.sgmwstarlight.stardash;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/** Tracks which app is in front from usage events (needs the GET_USAGE_STATS app-op). */
final class ForegroundApp {
    private final UsageStatsManager usage;
    private long lastQuery;
    private String current = "";

    ForegroundApp(Context context) {
        usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        lastQuery = System.currentTimeMillis() - 24 * 3600_000L; // first call finds whatever is in front now
    }

    /** Call about once a second. Returns the package most recently brought to the front. */
    String poll() {
        if (usage == null) return current;
        long now = System.currentTimeMillis();
        try {
            // Usage events can land slightly late, so look back over a short window each time.
            UsageEvents events = usage.queryEvents(Math.min(lastQuery, now - 15_000L), now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) current = e.getPackageName();
            }
            lastQuery = now;
        } catch (RuntimeException ignored) {
        }
        return current;
    }
}
