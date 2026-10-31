package com.local.wacallrecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            Intent service = new Intent(context, CallDetectionForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            DebugLog.write(context, "Detector start requested after " + action);
        } catch (Throwable t) {
            DebugLog.write(context, "Detector boot start failed: " + t);
        }
    }
}
