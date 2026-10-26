package com.local.wacallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class RecorderForegroundService extends Service {
    public static final String ACTION_START = "com.local.wacallrecorder.START_RECORDING";
    public static final String ACTION_STOP = "com.local.wacallrecorder.STOP_RECORDING";
    private static final String CHANNEL = "wa_recording";
    private static final int ID = 7002;
    private RecorderEngine engine;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (engine != null) return START_NOT_STICKY;

        createChannel();
        Notification n = buildNotification("Preparando grabación…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(ID, n);

        String contact = intent == null ? "WhatsApp" : intent.getStringExtra("contact");
        engine = new RecorderEngine(this, "notification-manual", contact, new RecorderEngine.Listener() {
            @Override public void onStarted(java.io.File wavFile) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(ID, buildNotification("Grabando · usa altavoz para intentar captar ambas voces"));
                CallPromptNotifier.cancel(RecorderForegroundService.this);
            }
            @Override public void onStopped(java.io.File wavFile, boolean audioDetected, int maxAmplitude, long durationMs) {
                stopSelf();
            }
            @Override public void onError(String error) {
                stopSelf();
            }
        });
        if (!engine.start()) stopSelf();
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, RecorderForegroundService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 201, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 202, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Grabador WA")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Detener", stopPi).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Grabación activa", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private void stopCapture() {
        RecorderEngine e = engine;
        engine = null;
        if (e != null) e.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}
