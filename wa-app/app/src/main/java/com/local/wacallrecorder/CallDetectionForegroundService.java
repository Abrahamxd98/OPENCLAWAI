package com.local.wacallrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class CallDetectionForegroundService extends Service {
    private static final String CHANNEL = "call_detector";
    private static final int ID = 7000;
    private static final long POLL_MS = 750L;
    private static final long START_DEBOUNCE_MS = 700L;
    private static final long END_DEBOUNCE_MS = 2200L;
    private static final long RECENT_WA_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private boolean audioCallActive;
    private long activeCandidateSince;
    private long normalSince;
    private int candidateMode = AudioManager.MODE_NORMAL;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            try { pollAudioMode(); }
            catch (Throwable t) { DebugLog.write(CallDetectionForegroundService.this, "Detector poll error: " + t); }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, notification);
        }
        AppState.prefs(this).edit().putBoolean(AppState.KEY_DETECTOR_ACTIVE, true).apply();
        sendStateBroadcast();
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        DebugLog.write(this, "Persistent audio-mode detector started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void pollAudioMode() {
        int mode = audioManager == null ? AudioManager.MODE_NORMAL : audioManager.getMode();
        boolean active = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        long now = System.currentTimeMillis();

        if (active) {
            normalSince = 0L;
            if (!audioCallActive) {
                if (activeCandidateSince == 0L || candidateMode != mode) {
                    activeCandidateSince = now;
                    candidateMode = mode;
                }
                if (now - activeCandidateSince >= START_DEBOUNCE_MS) beginCall(mode, now);
            } else {
                maybeUpgradeVoipClassification(now);
            }
            return;
        }

        activeCandidateSince = 0L;
        candidateMode = AudioManager.MODE_NORMAL;
        if (!audioCallActive) return;
        if (normalSince == 0L) normalSince = now;
        if (now - normalSince >= END_DEBOUNCE_MS) endCall(mode);
    }

    private void beginCall(int mode, long now) {
        audioCallActive = true;
        normalSince = 0L;
        SharedPreferences p = AppState.prefs(this);
        boolean notifActive = p.getBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false);
        String kind = p.getString(AppState.KEY_CALL_KIND, "");
        String contact = p.getString(AppState.KEY_CONTACT, "");

        if (!notifActive || kind == null || kind.trim().isEmpty()) {
            if (mode == AudioManager.MODE_IN_CALL) {
                kind = "CELULAR";
                contact = "Llamada celular";
            } else {
                String recentPkg = recentWhatsAppPackage(p, now);
                if (recentPkg != null) {
                    kind = AppState.callKindForPackage(recentPkg);
                    String title = p.getString(AppState.KEY_LAST_WA_TITLE, "");
                    contact = title == null || title.trim().isEmpty() ? labelForKind(kind) : title.trim();
                } else {
                    kind = "VOIP";
                    contact = "Llamada de internet";
                }
            }
        }

        p.edit()
                .putBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, true)
                .putString(AppState.KEY_CALL_KIND, kind)
                .putString(AppState.KEY_CONTACT, contact)
                .putInt(AppState.KEY_AUDIO_MODE, mode)
                .putLong(AppState.KEY_CALL_START_MS, now)
                .apply();
        DebugLog.write(this, "Audio mode call detected mode=" + mode + " kind=" + kind + " contact=" + contact);
        CallPromptNotifier.show(this, contact, kind);
        sendStateBroadcast();
    }

    private void maybeUpgradeVoipClassification(long now) {
        SharedPreferences p = AppState.prefs(this);
        String currentKind = p.getString(AppState.KEY_CALL_KIND, "");
        if (!"VOIP".equals(currentKind)) return;
        String recentPkg = recentWhatsAppPackage(p, now);
        if (recentPkg == null) return;

        String kind = AppState.callKindForPackage(recentPkg);
        String title = p.getString(AppState.KEY_LAST_WA_TITLE, "");
        String contact = title == null || title.trim().isEmpty() ? labelForKind(kind) : title.trim();
        p.edit().putString(AppState.KEY_CALL_KIND, kind).putString(AppState.KEY_CONTACT, contact).apply();
        DebugLog.write(this, "VOIP classification upgraded to " + kind + " contact=" + contact);
        CallPromptNotifier.show(this, contact, kind);
        sendStateBroadcast();
    }

    private String recentWhatsAppPackage(SharedPreferences p, long now) {
        long last = p.getLong(AppState.KEY_LAST_WA_NOTIFICATION_MS, 0L);
        String pkg = p.getString(AppState.KEY_LAST_WA_PACKAGE, null);
        return pkg != null && AppState.isWhatsApp(pkg) && now - last >= 0L && now - last <= RECENT_WA_MS ? pkg : null;
    }

    private void endCall(int mode) {
        audioCallActive = false;
        normalSince = 0L;
        SharedPreferences p = AppState.prefs(this);
        String kind = p.getString(AppState.KEY_CALL_KIND, "-");
        p.edit()
                .putBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, false)
                .putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false)
                .putInt(AppState.KEY_AUDIO_MODE, mode)
                .remove(AppState.KEY_CALL_PACKAGE)
                .apply();
        DebugLog.write(this, "Audio mode call ended kind=" + kind + " mode=" + mode);
        CallPromptNotifier.cancel(this);
        stopService(new Intent(this, RecorderForegroundService.class));
        sendStateBroadcast();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 7000, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Detector de llamadas activo")
                .setContentText("Vigilando llamadas celulares y de internet")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Detector de llamadas", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene activa la detección local del estado de llamadas.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private String labelForKind(String kind) {
        if ("WHATSAPP_BUSINESS".equals(kind)) return "WhatsApp Business";
        if ("WHATSAPP".equals(kind)) return "WhatsApp";
        if ("CELULAR".equals(kind)) return "Llamada celular";
        return "Llamada de internet";
    }

    private void sendStateBroadcast() {
        Intent i = new Intent(AppState.ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        AppState.prefs(this).edit().putBoolean(AppState.KEY_DETECTOR_ACTIVE, false).apply();
        sendStateBroadcast();
        DebugLog.write(this, "Persistent audio-mode detector stopped");
        super.onDestroy();
    }
}
