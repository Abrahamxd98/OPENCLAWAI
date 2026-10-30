package com.local.wacallrecorder;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppState {
    public static final String PREFS = "wa_recorder_state";
    public static final String ACTION_STATE_CHANGED = "com.local.wacallrecorder.STATE_CHANGED";
    public static final String KEY_NOTIFICATION_CALL_ACTIVE = "notification_call_active";
    public static final String KEY_CELLULAR_CALL_ACTIVE = "cellular_call_active";
    public static final String KEY_CONTACT = "contact";
    public static final String KEY_CALL_PACKAGE = "call_package";
    public static final String KEY_CALL_KIND = "call_kind";
    public static final String KEY_CALL_START_MS = "call_start_ms";
    public static final String KEY_RECORDING_ACTIVE = "recording_active";
    public static final String KEY_RECORDING_OWNER = "recording_owner";

    private AppState() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isWhatsApp(String pkg) {
        return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
    }

    public static String callKindForPackage(String pkg) {
        if ("com.whatsapp.w4b".equals(pkg)) return "WHATSAPP_BUSINESS";
        if ("com.whatsapp".equals(pkg)) return "WHATSAPP";
        return "CELULAR";
    }
}
