package com.sgmwstarlight.stardash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the recorder after boot, after the head unit wakes from its quick-off state, and from the keep-alive alarm. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Config config = new Config(context);
        boolean keepAlive = RecorderService.ACTION_KEEPALIVE.equals(action);
        if (!keepAlive && !config.autoStart()) return;
        if (keepAlive && RecorderService.instance != null) return;
        Log.i("StarDash", "start from " + action);
        RecorderService.start(context, null);
    }
}
