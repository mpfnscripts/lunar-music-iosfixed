package com.mpfn.lunarmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps Lunar Music running while music plays, so it carries on after the window is closed.
 * Android requires a notification for this; it says what is playing.
 */
public class PlayerService extends Service {
    private static final String CHANNEL = "playback";
    private static PlayerService running;
    private static String title = "", text = "";

    /** Start or stop staying alive in the background (called on the main thread). */
    static void playing(Context ctx, boolean on, String songTitle, String artist) {
        title = songTitle == null ? "" : songTitle;
        text = artist == null ? "" : artist;
        try {
            if (on) {
                if (running == null) {
                    Intent i = new Intent(ctx, PlayerService.class);
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                } else {
                    running.show();
                }
            } else if (running != null) {
                PlayerService s = running;
                running = null;
                s.stopForeground(STOP_FOREGROUND_REMOVE);
                s.stopSelf();
            }
        } catch (Exception ignored) {
            // Android can refuse to start it from the background; the music still plays while the app is open.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = this;
        show();
        return START_NOT_STICKY;
    }

    private void show() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playing music", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(title.isEmpty() ? "Lunar Music" : title)
                .setContentText(text.isEmpty() ? "Lunar Music" : text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
