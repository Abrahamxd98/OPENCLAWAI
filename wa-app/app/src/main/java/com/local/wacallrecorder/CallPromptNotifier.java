package com.local.wacallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class CallPromptNotifier {
    private static final String CHANNEL_ID = "call_detected";
    private static final int NOTIFICATION_ID = 7001;
    private CallPromptNotifier() {}

    public static void show(Context c, String contact, String kind) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Llamadas detectadas", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Permite iniciar la grabación de una llamada mediante una acción explícita.");
            nm.createNotificationChannel(ch);
        }

        String shownKind = labelForKind(kind);
        String shownContact = contact == null || contact.trim().isEmpty() ? shownKind : contact.trim();

        Intent record = new Intent(c, RecorderForegroundService.class);
        record.setAction(RecorderForegroundService.ACTION_START);
        record.putExtra("contact", kind + "_" + shownContact);
        PendingIntent recordPi = PendingIntent.getForegroundService(c, 100, record,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(c, 101, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);

        Notification n = b
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(shownKind + " detectada")
                .setContentText(shownContact + " · toca Grabar para iniciar")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(false)
                .setAutoCancel(false)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Grabar", recordPi).build())
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    private static String labelForKind(String kind) {
        if ("WHATSAPP_BUSINESS".equals(kind)) return "WhatsApp Business";
        if ("WHATSAPP".equals(kind)) return "WhatsApp";
        if ("VOIP".equals(kind)) return "Llamada de internet";
        return "Llamada celular";
    }

    public static void cancel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);
    }
}
