package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Friend Map background mode: keeps sending our position to friends while the app is closed.
 * Runs only when the user turned background mode on and has at least one friend.
 */
public class UMessageFriendLocationService extends Service {

    private static final String ACTION_STOP = "org.telegram.messenger.UMFRIEND_STOP";
    private static final int NOTIFICATION_ID = 0x75f1;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            UMessageFriendLocations.dispatchLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private boolean listening;
    private final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
    /** With an agreed zone, friends' positions are checked every minute so crossing alerts arrive while closed. */
    private final Runnable zonePoll = new Runnable() {
        @Override
        public void run() {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    UMessageFriendLocations c = UMessageFriendLocations.getInstance(a);
                    if (c.hasActiveZones()) c.refreshLocations();
                }
            }
            handler.postDelayed(this, 60_000);
        }
    };

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, UMessageFriendLocationService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, UMessageFriendLocationService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            UMessageFriendLocations.setBackgroundEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Intent open = new Intent(this, LaunchActivity.class);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent stop = new Intent(this, UMessageFriendLocationService.class).setAction(ACTION_STOP);
            PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationsController.checkOtherNotificationsChannel();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.live_loc)
                    .setContentTitle(LocaleController.getString(R.string.UMessageFriendMap))
                    .setContentText(LocaleController.getString(R.string.UMessageFriendMapBackgroundNotification))
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(0, LocaleController.getString(R.string.UMessageFriendMapBackgroundStop), stopIntent);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!listening && UMessageFriendLocations.hasPermission(this)) {
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 60_000, 30, listener, Looper.getMainLooper());
                    }
                }
                listening = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        handler.removeCallbacks(zonePoll);
        handler.postDelayed(zonePoll, 5_000);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(zonePoll);
        if (listening) {
            try {
                ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(listener);
            } catch (Throwable ignore) {
            }
            listening = false;
        }
    }
}
