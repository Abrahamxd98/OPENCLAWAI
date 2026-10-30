package com.local.wacallrecorder;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private final Set<String> callKeys = new HashSet<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isSupportedCallNotification(sbn)) return;
        boolean looksLikeCall = looksLikeCall(sbn);
        logNotification("posted", sbn, looksLikeCall);
        if (!looksLikeCall) return;

        callKeys.add(sbn.getKey());
        Notification n = sbn.getNotification();
        Bundle extras = n == null || n.extras == null ? new Bundle() : n.extras;
        String title = stringOf(extras.getCharSequence(Notification.EXTRA_TITLE));
        String kind = AppState.callKindForPackage(sbn.getPackageName());
        String fallback = "CELULAR".equals(kind) ? "Llamada celular" : kind;
        String contact = title.trim().isEmpty() ? fallback : title.trim();
        long now = System.currentTimeMillis();

        AppState.prefs(this).edit()
                .putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, true)
                .putString(AppState.KEY_CONTACT, contact)
                .putString(AppState.KEY_CALL_PACKAGE, sbn.getPackageName())
                .putString(AppState.KEY_CALL_KIND, kind)
                .putLong(AppState.KEY_CALL_START_MS, now)
                .apply();
        sendStateBroadcast();
        CallPromptNotifier.show(this, contact, kind);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isSupportedCallNotification(sbn)) return;
        boolean looksLikeCall = callKeys.remove(sbn.getKey()) || looksLikeCall(sbn);
        logNotification("removed", sbn, looksLikeCall);
        if (!looksLikeCall) return;

        boolean anotherCall = false;
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification x : active) {
                if (x != null && isSupportedCallNotification(x) && looksLikeCall(x)) {
                    callKeys.add(x.getKey());
                    anotherCall = true;
                    break;
                }
            }
        }
        if (anotherCall) return;

        AppState.prefs(this).edit()
                .putBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false)
                .remove(AppState.KEY_CALL_PACKAGE)
                .apply();
        sendStateBroadcast();
        CallPromptNotifier.cancel(this);

        Intent stop = new Intent(this, RecorderForegroundService.class);
        stop.setAction(RecorderForegroundService.ACTION_STOP);
        stopService(stop);
    }

    private boolean isSupportedCallNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (AppState.isWhatsApp(pkg)) return true;
        Notification n = sbn.getNotification();
        if (n == null || !Notification.CATEGORY_CALL.equals(n.category)) return false;
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        return p.contains("dialer") || p.contains("incall") || p.contains("phone")
                || p.contains("telecom") || p.contains("contacts");
    }

    private boolean looksLikeCall(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        Bundle e = n.extras == null ? new Bundle() : n.extras;
        String title = stringOf(e.getCharSequence(Notification.EXTRA_TITLE));
        String text = stringOf(e.getCharSequence(Notification.EXTRA_TEXT));
        String big = stringOf(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String category = n.category == null ? "" : n.category;
        String combined = title + " " + text + " " + big;

        if (containsExcludedWords(combined)) return false;
        return Notification.CATEGORY_CALL.equals(category) || containsCallWords(combined);
    }

    private void logNotification(String event, StatusBarNotification sbn, boolean looksLikeCall) {
        Notification n = sbn.getNotification();
        Bundle e = n == null || n.extras == null ? new Bundle() : n.extras;
        String title = stringOf(e.getCharSequence(Notification.EXTRA_TITLE));
        String text = stringOf(e.getCharSequence(Notification.EXTRA_TEXT));
        String big = stringOf(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String category = n == null || n.category == null ? "" : n.category;
        boolean ongoing = n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        DebugLog.write(this, "CALL notification " + event + " pkg=" + sbn.getPackageName()
                + " key=" + sbn.getKey() + " category=" + category + " ongoing=" + ongoing
                + " title=" + title + " text=" + text + " big=" + big
                + " looksLikeCall=" + looksLikeCall);
    }

    private void sendStateBroadcast() {
        Intent i = new Intent(AppState.ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private static String stringOf(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static boolean containsExcludedWords(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String[] excluded = {
                "copia de seguridad", "backup", "restaurando", "restoring",
                "subiendo", "uploading", "descargando", "downloading",
                "verificando", "preparando copia", "preparing backup"
        };
        for (String w : excluded) if (s.contains(w)) return true;
        return false;
    }

    private static boolean containsCallWords(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String[] words = {
                "llamada", "llamando", "llamada de voz", "videollamada", "llamada en curso",
                "call", "calling", "voice call", "video call", "ongoing call", "ringing"
        };
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
