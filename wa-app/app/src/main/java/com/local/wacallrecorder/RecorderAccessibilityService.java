package com.local.wacallrecorder;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public class RecorderAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecorderEngine engine;
    private long noCallSince = 0L;
    private WindowManager wm;
    private TextView overlay;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try { evaluateCallState(); }
            catch (Throwable t) { DebugLog.write(RecorderAccessibilityService.this, "auto poll error=" + t.getMessage()); }
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        DebugLog.write(this, "Accessibility AUTO connected");
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence p = event.getPackageName();
        if (p != null && AppState.isWhatsApp(p.toString())) {
            AppState.prefs(this).edit().putLong(AppState.KEY_WA_LAST_VISIBLE_MS, System.currentTimeMillis()).apply();
            evaluateCallState();
        }
    }

    @Override public void onInterrupt() {}

    private void evaluateCallState() {
        boolean notifCall = AppState.prefs(this).getBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false);
        long lastWa = AppState.prefs(this).getLong(AppState.KEY_WA_LAST_VISIBLE_MS, 0L);
        boolean recentWa = System.currentTimeMillis() - lastWa < 30000L;

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
        boolean communicationMode = mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL;

        boolean shouldRecord = notifCall || (communicationMode && (recentWa || engine != null));

        if (shouldRecord) {
            noCallSince = 0L;
            if (engine == null) startAutomaticRecording();
        } else if (engine != null) {
            if (noCallSince == 0L) noCallSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - noCallSince >= 2500L) stopAutomaticRecording();
        }
    }

    private void startAutomaticRecording() {
        String contact = AppState.prefs(this).getString(AppState.KEY_CONTACT, "WhatsApp");
        if (contact == null || contact.trim().isEmpty()) contact = "WhatsApp";

        final RecorderEngine newEngine = new RecorderEngine(this, "accessibility-auto", contact, new RecorderEngine.Listener() {
            @Override public void onStarted(java.io.File wavFile) {
                DebugLog.write(RecorderAccessibilityService.this, "AUTO START " + wavFile.getName());
                showOverlay();
                CallPromptNotifier.cancel(RecorderAccessibilityService.this);
            }
            @Override public void onStopped(java.io.File wavFile, boolean audioDetected, int maxAmplitude, long durationMs) {
                DebugLog.write(RecorderAccessibilityService.this, "AUTO STOP audioDetected=" + audioDetected + " amp=" + maxAmplitude);
                hideOverlay();
                engine = null;
            }
            @Override public void onError(String error) {
                DebugLog.write(RecorderAccessibilityService.this, "AUTO ERROR " + error);
                hideOverlay();
                engine = null;
            }
        });

        engine = newEngine;
        if (!newEngine.start()) engine = null;
    }

    private void stopAutomaticRecording() {
        RecorderEngine e = engine;
        engine = null;
        noCallSince = 0L;
        if (e != null) e.stop();
        hideOverlay();
    }

    private void showOverlay() {
        if (overlay != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlay = new TextView(this);
            overlay.setText("● REC WA");
            overlay.setTextColor(Color.WHITE);
            overlay.setBackgroundColor(0xCCB00020);
            overlay.setTextSize(14f);
            overlay.setPadding(22, 12, 22, 12);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = 16;
            lp.y = 80;
            wm.addView(overlay, lp);
        } catch (Throwable t) {
            DebugLog.write(this, "overlay error=" + t.getMessage());
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay == null || wm == null) return;
        try { wm.removeView(overlay); } catch (Throwable ignored) {}
        overlay = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        stopAutomaticRecording();
        DebugLog.write(this, "Accessibility AUTO destroyed");
        super.onDestroy();
    }
}
