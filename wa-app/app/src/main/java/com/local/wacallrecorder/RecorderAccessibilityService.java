package com.local.wacallrecorder;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public class RecorderAccessibilityService extends AccessibilityService {
    private static final long POLL_MS = 300L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecorderEngine engine;
    private WindowManager wm;
    private TextView overlay;
    private boolean speakerRouteApplied;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try { evaluate(); }
            catch (Throwable t) { DebugLog.write(RecorderAccessibilityService.this, "AUTO poll error=" + t); }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppState.prefs(this).edit().putBoolean(AppState.KEY_ACCESSIBILITY_CONNECTED, true).apply();
        DebugLog.write(this, "Accessibility capture engine connected");
        sendStateBroadcast();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    private void evaluate() {
        boolean callActive = AppState.prefs(this).getBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, false);
        boolean recordingActive = AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false);

        if (callActive) {
            if (engine == null && !recordingActive) startAutomaticRecording();
        } else if (engine != null) {
            stopAutomaticRecording("call-ended");
        }
    }

    private void startAutomaticRecording() {
        String kind = AppState.prefs(this).getString(AppState.KEY_CALL_KIND, "LLAMADA");
        String contact = AppState.prefs(this).getString(AppState.KEY_CONTACT, "Llamada");
        if (kind == null || kind.trim().isEmpty()) kind = "LLAMADA";
        if (contact == null || contact.trim().isEmpty()) contact = "Llamada";
        final String fileLabel = kind + "_" + contact;

        if ("WHATSAPP".equals(kind) || "WHATSAPP_BUSINESS".equals(kind) || "VOIP".equals(kind)) {
            ensureSpeakerRoute();
        }

        RecorderEngine candidate = new RecorderEngine(this, "accessibility-auto", fileLabel, new RecorderEngine.Listener() {
            @Override public void onStarted(java.io.File wavFile) {
                DebugLog.write(RecorderAccessibilityService.this, "AUTO START " + wavFile.getName());
                showOverlay();
                CallPromptNotifier.cancel(RecorderAccessibilityService.this);
                sendStateBroadcast();
            }

            @Override public void onStopped(java.io.File wavFile, boolean audioDetected, int maxAmplitude, long durationMs) {
                DebugLog.write(RecorderAccessibilityService.this,
                        "AUTO STOP durationMs=" + durationMs + " audioDetected=" + audioDetected + " amp=" + maxAmplitude);
                AppState.prefs(RecorderAccessibilityService.this).edit()
                        .putLong(AppState.KEY_LAST_RECORD_DURATION_MS, durationMs)
                        .putString(AppState.KEY_LAST_RECORD_STOP_REASON, "call-ended")
                        .apply();
                hideOverlay();
                restoreAudioRoute();
                engine = null;
                sendStateBroadcast();
            }

            @Override public void onError(String error) {
                DebugLog.write(RecorderAccessibilityService.this, "AUTO ERROR " + error);
                AppState.prefs(RecorderAccessibilityService.this).edit()
                        .putString(AppState.KEY_LAST_RECORD_STOP_REASON, "error: " + error)
                        .apply();
                hideOverlay();
                restoreAudioRoute();
                engine = null;
                sendStateBroadcast();
            }
        });

        engine = candidate;
        if (!candidate.start()) {
            engine = null;
            restoreAudioRoute();
        }
    }

    private void stopAutomaticRecording(String reason) {
        RecorderEngine e = engine;
        engine = null;
        AppState.prefs(this).edit().putString(AppState.KEY_LAST_RECORD_STOP_REASON, reason).apply();
        if (e != null) e.stop();
        hideOverlay();
        restoreAudioRoute();
        sendStateBroadcast();
    }

    private void ensureSpeakerRoute() {
        if (speakerRouteApplied) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            boolean ok = false;
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        ok = am.setCommunicationDevice(d);
                        break;
                    }
                }
            } else {
                am.setSpeakerphoneOn(true);
                ok = true;
            }
            speakerRouteApplied = ok;
            DebugLog.write(this, "AUTO speaker route=" + ok);
        } catch (Throwable t) {
            DebugLog.write(this, "AUTO speaker route error=" + t);
        }
    }

    private void restoreAudioRoute() {
        if (!speakerRouteApplied) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else am.setSpeakerphoneOn(false);
            }
        } catch (Throwable ignored) {}
        speakerRouteApplied = false;
    }

    private void showOverlay() {
        if (overlay != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlay = new TextView(this);
            overlay.setText("● REC");
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
            DebugLog.write(this, "AUTO overlay error=" + t);
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay == null || wm == null) return;
        try { wm.removeView(overlay); } catch (Throwable ignored) {}
        overlay = null;
    }

    private void sendStateBroadcast() {
        android.content.Intent i = new android.content.Intent(AppState.ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        stopAutomaticRecording("accessibility-destroyed");
        AppState.prefs(this).edit().putBoolean(AppState.KEY_ACCESSIBILITY_CONNECTED, false).apply();
        DebugLog.write(this, "Accessibility capture engine destroyed");
        super.onDestroy();
    }
}
