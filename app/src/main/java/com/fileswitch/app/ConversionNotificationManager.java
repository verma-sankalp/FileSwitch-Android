package com.fileswitch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class ConversionNotificationManager {
    private static final String CHANNEL_ID = "fileswitch_conversions";
    private static final int PROGRESS_ID = 1001;
    private static final int COMPLETE_ID = 1002;
    private final Context context;
    private final NotificationManager manager;

    ConversionNotificationManager(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "File Conversions",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows file conversion progress and results");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    private boolean canNotify() {
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    void showProgress(int current, int total, String filename) {
        if (!canNotify()) return;
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setContentTitle("Converting files")
                .setContentText("File " + current + " of " + total + " · " + filename)
                .setSmallIcon(R.drawable.fileswitch_icon)
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setProgress(total, current, false);

        manager.notify(PROGRESS_ID, builder.build());
    }

    void showComplete(int count) {
        if (!canNotify()) return;
        manager.cancel(PROGRESS_ID);
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setContentTitle("Conversion complete")
                .setContentText(count + " file" + (count == 1 ? "" : "s") + " converted successfully")
                .setSmallIcon(R.drawable.fileswitch_icon)
                .setContentIntent(contentIntent())
                .setAutoCancel(true);

        manager.notify(COMPLETE_ID, builder.build());
    }

    void showFailed(String reason) {
        if (!canNotify()) return;
        manager.cancel(PROGRESS_ID);
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setContentTitle("Conversion failed")
                .setContentText(reason)
                .setSmallIcon(R.drawable.fileswitch_icon)
                .setContentIntent(contentIntent())
                .setAutoCancel(true);

        manager.notify(COMPLETE_ID, builder.build());
    }

    void dismissAll() {
        if (manager != null) {
            manager.cancel(PROGRESS_ID);
        }
    }
}
