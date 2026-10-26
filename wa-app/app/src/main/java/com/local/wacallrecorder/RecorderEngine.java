package com.local.wacallrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecorderEngine {
    public interface Listener {
        void onStarted(File wavFile);
        void onStopped(File wavFile, boolean audioDetected, int maxAmplitude, long durationMs);
        void onError(String error);
    }

    private static final int SAMPLE_RATE = 16000;
    private final Context context;
    private final String owner;
    private final String contact;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private Thread thread;
    private File wavFile;
    private long startedAt;
    private int maxAmplitude = 0;
    private long nonSilentSamples = 0;

    public RecorderEngine(Context c, String owner, String contact, Listener listener) {
        this.context = c.getApplicationContext();
        this.owner = owner;
        this.contact = contact == null ? "WhatsApp" : contact;
        this.listener = listener;
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Falta permiso RECORD_AUDIO");
            return false;
        }
        if (AppState.prefs(context).getBoolean(AppState.KEY_RECORDING_ACTIVE, false)) {
            fail("Ya existe una captura activa");
            return false;
        }

        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min, SAMPLE_RATE * 2);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("AudioRecord no pudo inicializarse");
                releaseAudio();
                return false;
            }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String safe = contact.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ._-]", "_");
            if (safe.length() > 32) safe = safe.substring(0, 32);
            wavFile = new File(DebugLog.baseDir(context), stamp + "_" + safe + ".wav");
            startedAt = System.currentTimeMillis();
            maxAmplitude = 0;
            nonSilentSamples = 0;

            audioRecord.startRecording();
            running.set(true);
            AppState.prefs(context).edit()
                    .putBoolean(AppState.KEY_RECORDING_ACTIVE, true)
                    .putString(AppState.KEY_RECORDING_OWNER, owner)
                    .apply();
            DebugLog.write(context, "RecorderEngine START owner=" + owner + " file=" + wavFile.getName());
            if (listener != null) listener.onStarted(wavFile);

            final int finalBuffer = buffer;
            thread = new Thread(() -> captureLoop(finalBuffer), "wa-audio-capture");
            thread.start();
            return true;
        } catch (Throwable t) {
            fail("No se pudo iniciar captura: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            releaseAudio();
            AppState.prefs(context).edit().putBoolean(AppState.KEY_RECORDING_ACTIVE, false).remove(AppState.KEY_RECORDING_OWNER).apply();
            return false;
        }
    }

    private void captureLoop(int bufferSize) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] bytes = new byte[bufferSize];
        WavWriter writer = null;
        try {
            writer = new WavWriter(wavFile, SAMPLE_RATE, (short) 1, (short) 16);
            while (running.get()) {
                int read = audioRecord.read(bytes, 0, bytes.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    writer.write(bytes, 0, read);
                    analyze(bytes, read);
                } else if (read < 0) {
                    DebugLog.write(context, "AudioRecord read error=" + read);
                    break;
                }
            }
        } catch (Throwable t) {
            DebugLog.write(context, "captureLoop error=" + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (writer != null) writer.close();
        }
    }

    private void analyze(byte[] data, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            int a = Math.abs(sample);
            if (a > maxAmplitude) maxAmplitude = a;
            if (a > 250) nonSilentSamples++;
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { if (audioRecord != null) audioRecord.stop(); } catch (Exception ignored) {}
        try { if (thread != null) thread.join(1500); } catch (InterruptedException ignored) {}
        releaseAudio();

        long duration = Math.max(0, System.currentTimeMillis() - startedAt);
        boolean audioDetected = maxAmplitude >= 500 && nonSilentSamples >= 800;
        writeMetadata(duration, audioDetected);
        AppState.prefs(context).edit().putBoolean(AppState.KEY_RECORDING_ACTIVE, false).remove(AppState.KEY_RECORDING_OWNER).apply();
        DebugLog.write(context, "RecorderEngine STOP owner=" + owner + " file=" + (wavFile == null ? "" : wavFile.getName())
                + " durationMs=" + duration + " maxAmplitude=" + maxAmplitude + " audioDetected=" + audioDetected);
        if (listener != null) listener.onStopped(wavFile, audioDetected, maxAmplitude, duration);
    }

    private void writeMetadata(long durationMs, boolean audioDetected) {
        if (wavFile == null) return;
        try {
            File json = new File(wavFile.getParentFile(), wavFile.getName().replace(".wav", ".json"));
            FileWriter w = new FileWriter(json);
            w.write("{\n");
            w.write("  \"contact\": \"" + esc(contact) + "\",\n");
            w.write("  \"owner\": \"" + esc(owner) + "\",\n");
            w.write("  \"startedAtMs\": " + startedAt + ",\n");
            w.write("  \"durationMs\": " + durationMs + ",\n");
            w.write("  \"sampleRate\": " + SAMPLE_RATE + ",\n");
            w.write("  \"maxAmplitude\": " + maxAmplitude + ",\n");
            w.write("  \"audioDetected\": " + audioDetected + "\n");
            w.write("}\n");
            w.close();
        } catch (Exception ignored) {}
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void releaseAudio() {
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
        audioRecord = null;
    }

    private void fail(String s) {
        DebugLog.write(context, "RecorderEngine ERROR owner=" + owner + " " + s);
        if (listener != null) listener.onError(s);
    }
}
