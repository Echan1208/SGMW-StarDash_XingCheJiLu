package com.sgmwstarlight.stardash;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Reads the current wallpaper's status bar dark mode from the theme store's wallpaper service,
 * the same source SystemUI uses: 0 = dark icons, 1 = white icons, -1 = not set (follow day/night).
 * Talks to the service with raw Binder calls that match its published interface.
 */
final class WallpaperMode {
    private static final String TAG = "StarDash";
    private static final String PKG = "com.sgmw.themestore";
    private static final String CLS = "com.sgmw.themestore.main.wallpaper.WallpaperSdkService";
    private static final String ACTION = "sgmw.wallpapersdk";
    private static final String IWALLPAPER = "com.sgmw.themestore.main.wallpaper.IWallpaper";
    private static final int TX_GET_MINE_LIST = 6, TX_REGISTER_MINE_LIST = 19, TX_REGISTER_SWITCH = 22;

    private volatile int mode = -1;
    private IBinder remote;

    int mode() {
        return mode;
    }

    void start(Context context) {
        Intent i = new Intent(ACTION).setComponent(new ComponentName(PKG, CLS));
        try {
            context.bindService(i, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            Log.w(TAG, "wallpaper service bind failed: " + e);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            remote = service;
            try {
                call(TX_REGISTER_MINE_LIST, listener("com.sgmw.themestore.main.wallpaper.IMineWallpaperListChangeListener"), false);
                call(TX_REGISTER_SWITCH, listener("com.sgmw.themestore.main.wallpaper.IWallpaperSwitchChangeListener"), false);
                call(TX_GET_MINE_LIST, listener("com.sgmw.themestore.main.wallpaper.IMineWallpaperCallback"), true);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "wallpaper service call failed: " + e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
        }
    };

    private void call(int code, Binder callback, boolean oneway) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = oneway ? null : Parcel.obtain();
        try {
            data.writeInterfaceToken(IWALLPAPER);
            data.writeStrongBinder(callback);
            remote.transact(code, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0);
            if (reply != null) reply.readException();
        } finally {
            data.recycle();
            if (reply != null) reply.recycle();
        }
    }

    /** All three callbacks deliver List<WallpaperBean> as transaction 1; the first item is the current one. */
    private Binder listener(String descriptor) {
        return new Binder() {
            {
                attachInterface(null, descriptor);
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(descriptor);
                    return true;
                }
                if (code != 1) return false;
                try {
                    data.enforceInterface(descriptor);
                    int count = data.readInt();
                    if (count > 0 && data.readInt() != 0) {
                        // WallpaperBean field order: id, two strings, fileType, two strings, applyTime, statusBarDarkMode
                        int id = data.readInt();
                        data.readString();
                        data.readString();
                        data.readInt();
                        data.readString();
                        data.readString();
                        data.readLong();
                        int dark = data.readInt();
                        mode = id == -999 ? -1 : dark;
                        Log.i(TAG, "wallpaper status bar dark mode " + mode);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "wallpaper callback parse failed: " + e);
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }
}
