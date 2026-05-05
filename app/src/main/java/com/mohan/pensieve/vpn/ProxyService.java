package com.mohan.pensieve.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ProxyService extends Service {
    private static final String CHANNEL_ID = "pensieve_proxy";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pensieve")
                .setContentText("Proxy running")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .build();
        startForeground(3, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Pensieve Proxy", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
