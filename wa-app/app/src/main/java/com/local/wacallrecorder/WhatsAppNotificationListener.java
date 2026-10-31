package com.local.wacallrecorder;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private final Set<String> callKeys = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanner = new Runnable() {
        @Override public void run() {
            try { scanActiveCalls(); }
            catch (Throwable t) { DebugLog.write(WhatsAppNotificationListener.this, "Active notification scan error=" + t); }
            handler.postDelayed(this, 1200L);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        handler.removeCallbacks(scanner);
        handler.post(scanner);
        DebugLog.write(this, "Notification listener connected; active-call scanner started");
    }

    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(scanner);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isSupportedCallNotification(sbn)) return;
        boolean call = looksLikeCall(sbn);
        logNotification("posted", sbn, call);
        if (!call) return;
        acceptCallNotification(sbn, true);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isSupportedCallNotification(sbn)) return;
        boolean call = callKeys.remove(sbn.getKey()) || looksLikeCall(sbn);
        logNotification("removed", sbn, call);
        if (!call) return;
        scanActiveCalls();
        boolean audioCallActive = AppState.prefs(this).getBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, false);
        if (!audioCallActive && callKeys.isEmpty()) {
            AppState.prefs(this).edit().putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false).remove(AppState.KEY_CALL_PACKAGE).apply();
            CallPromptNotifier.cancel(this);
            stopService(new Intent(this, RecorderForegroundService.class));
            sendStateBroadcast();
        }
    }

    private void scanActiveCalls() {
        StatusBarNotification[] active = getActiveNotifications();
        boolean found = false;
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (sbn == null || !isSupportedCallNotification(sbn) || !looksLikeCall(sbn)) continue;
                found = true;
                acceptCallNotification(sbn, false);
            }
        }
        if (!found && !AppState.prefs(this).getBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, false)) {
            callKeys.clear();
            AppState.prefs(this).edit().putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false).apply();
        }
    }

    private void acceptCallNotification(StatusBarNotification sbn, boolean showPrompt) {
        callKeys.add(sbn.getKey());
        Notification n = sbn.getNotification();
        Bundle extras = n == null || n.extras == null ? new Bundle() : n.extras;
        String title = stringOf(extras.getCharSequence(Notification.EXTRA_TITLE)).trim();
        String kind = AppState.callKindForPackage(sbn.getPackageName());
        String fallback = "CELULAR".equals(kind) ? "Llamada celular" : labelForKind(kind);
        String contact = title.isEmpty() ? fallback : title;
        long now = System.currentTimeMillis();

        android.content.SharedPreferences.Editor e = AppState.prefs(this).edit()
                .putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, true)
                .putString(AppState.KEY_CONTACT, contact)
                .putString(AppState.KEY_CALL_PACKAGE, sbn.getPackageName())
                .putString(AppState.KEY_CALL_KIND, kind)
                .putLong(AppState.KEY_CALL_START_MS, now);
        if (AppState.isWhatsApp(sbn.getPackageName())) {
            e.putLong(AppState.KEY_LAST_WA_NOTIFICATION_MS, now)
                    .putString(AppState.KEY_LAST_WA_PACKAGE, sbn.getPackageName())
                    .putString(AppState.KEY_LAST_WA_TITLE, title);
        }
        e.apply();
        if (showPrompt && !AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false)) {
            CallPromptNotifier.show(this, contact, kind);
        }
        sendStateBroadcast();
    }

    private boolean isSupportedCallNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (AppState.isWhatsApp(pkg)) return true;
        Notification n = sbn.getNotification();
        if (n == null || !Notification.CATEGORY_CALL.equals(n.category)) return false;
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        return p.contains("dialer") || p.contains("incall") || p.contains("phone") || p.contains("telecom") || p.contains("contacts");
    }

    private boolean looksLikeCall(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        Bundle e = n.extras == null ? new Bundle() : n.extras;
        String combined = stringOf(e.getCharSequence(Notification.EXTRA_TITLE)) + " "
                + stringOf(e.getCharSequence(Notification.EXTRA_TEXT)) + " "
                + stringOf(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (containsExcludedWords(combined)) return false;
        return Notification.CATEGORY_CALL.equals(n.category) || containsCallWords(combined);
    }

    private void logNotification(String event, StatusBarNotification sbn, boolean call) {
        Notification n = sbn.getNotification();
        Bundle e = n == null || n.extras == null ? new Bundle() : n.extras;
        DebugLog.write(this, "CALL notification " + event + " pkg=" + sbn.getPackageName()
                + " category=" + (n == null ? "" : n.category)
                + " title=" + stringOf(e.getCharSequence(Notification.EXTRA_TITLE))
                + " text=" + stringOf(e.getCharSequence(Notification.EXTRA_TEXT))
                + " looksLikeCall=" + call);
    }

    private void sendStateBroadcast() {
        Intent i = new Intent(AppState.ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private static String stringOf(CharSequence s) { return s == null ? "" : s.toString(); }
    private static String labelForKind(String kind) {
        if ("WHATSAPP_BUSINESS".equals(kind)) return "WhatsApp Business";
        if ("WHATSAPP".equals(kind)) return "WhatsApp";
        return "Llamada celular";
    }
    private static boolean containsExcludedWords(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String[] excluded = {"copia de seguridad", "backup", "restaurando", "restoring", "subiendo", "uploading", "descargando", "downloading", "verificando", "preparando copia", "preparing backup"};
        for (String w : excluded) if (s.contains(w)) return true;
        return false;
    }
    private static boolean containsCallWords(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String[] words = {"llamada", "llamando", "llamada de voz", "videollamada", "llamada en curso", "call", "calling", "voice call", "video call", "ongoing call", "ringing"};
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
