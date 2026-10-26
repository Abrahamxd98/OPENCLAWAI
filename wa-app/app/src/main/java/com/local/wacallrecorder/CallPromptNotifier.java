package com.local.wacallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class CallPromptNotifier {
    private static final String CHANNEL_ID = "wa_call_detected";
    private static final int NOTIFICATION_ID = 7001;
    private CallPromptNotifier() {}

    public static void show(Context c, String contact) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Llamadas WhatsApp detectadas", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Permite iniciar manualmente la grabación cuando Android no admite el inicio automático.");
            nm.createNotificationChannel(ch);
        }

        Intent record = new Intent(c, RecorderForegroundService.class);
        record.setAction(RecorderForegroundService.ACTION_START);
        record.putExtra("contact", contact);
        PendingIntent recordPi = PendingIntent.getService(c, 100, record,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(c, 101, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);

        Notification n = b
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Llamada de WhatsApp detectada")
                .setContentText((contact == null || contact.isEmpty() ? "WhatsApp" : contact) + " · usa altavoz para intentar captar ambas voces")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Grabar", recordPi).build())
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    public static void cancel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);
    }
}
