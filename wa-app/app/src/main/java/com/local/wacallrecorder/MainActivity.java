package com.local.wacallrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10;
    private TextView status;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestRuntimePermissions();
        ensureDetectorServiceIfPossible();
    }

    @Override protected void onResume() {
        super.onResume();
        ensureDetectorServiceIfPossible();
        refreshStatus();
        refreshRecordings();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            ensureDetectorServiceIfPossible();
            refreshStatus();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        root.addView(text("Grabador de llamadas · v0.2.1", 24, true));
        root.addView(text("La detección ya no depende únicamente de las notificaciones. Un detector persistente vigila el modo de audio del sistema para reconocer llamadas celulares y llamadas de internet aun con la app cerrada. El acceso a notificaciones se usa como apoyo para identificar WhatsApp y WhatsApp Business.", 15, false));

        status = text("", 15, false);
        status.setPadding(0, dp(16), 0, dp(16));
        root.addView(status);

        TextView setup = text("Configuración inicial", 18, true);
        setup.setPadding(0, dp(10), 0, dp(6));
        root.addView(setup);
        addButton("1. Conceder micrófono y notificaciones", v -> requestRuntimePermissions());
        addButton("2. Activar acceso a notificaciones", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addButton("3. Permitir funcionamiento en segundo plano", v -> openBatteryExemption());
        addButton("4. Abrir ajustes de la app / HyperOS", v -> openAppSettings());
        addButton("Activar detector ahora", v -> { ensureDetectorService(); refreshStatus(); });
        addButton("Actualizar estado", v -> { refreshStatus(); refreshRecordings(); });

        TextView info = text("Debe permanecer una notificación discreta: “Detector de llamadas activo”. Cuando el sistema entre en modo de llamada aparecerá otra notificación con Grabar. Toca Grabar para iniciar; la grabación continúa con la app cerrada y se detiene al finalizar la llamada.", 14, false);
        info.setPadding(0, dp(14), 0, dp(8));
        root.addView(info);

        TextView h = text("Grabaciones", 19, true);
        h.setPadding(0, dp(20), 0, dp(8));
        root.addView(h);

        setContentView(scroll);
    }

    private void refreshRecordings() {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if ("recording_row".equals(v.getTag())) root.removeViewAt(i);
        }
        File dir = DebugLog.baseDir(this);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
        if (files == null || files.length == 0) {
            TextView none = text("Aún no hay grabaciones. Archivos: " + dir.getAbsolutePath(), 13, false);
            none.setTag("recording_row");
            root.addView(none);
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        int count = Math.min(files.length, 30);
        for (int i = 0; i < count; i++) {
            File f = files[i];
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(f.getName() + "\n" + humanSize(f.length()));
            b.setTag("recording_row");
            b.setOnClickListener(v -> openRecording(f));
            root.addView(b);
        }
    }

    private void openRecording(File f) {
        try {
            final MediaPlayer player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(f.getName())
                    .setMessage("Reproduciendo grabación")
                    .setPositiveButton("Detener", (d, which) -> d.dismiss())
                    .create();
            dialog.setOnDismissListener(d -> {
                try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
                player.release();
            });
            player.setOnCompletionListener(mp -> dialog.dismiss());
            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo reproducir: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        else {
            ensureDetectorServiceIfPossible();
            Toast.makeText(this, "Permisos básicos concedidos", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureDetectorServiceIfPossible() {
        boolean notifAllowed = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (notifAllowed) ensureDetectorService();
    }

    private void ensureDetectorService() {
        try {
            Intent service = new Intent(this, CallDetectionForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Throwable t) {
            DebugLog.write(this, "Could not start detector service: " + t);
            Toast.makeText(this, "No se pudo activar el detector: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void openBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "La app ya está excluida de la optimización de batería", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception ignored) { openAppSettings(); }
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void refreshStatus() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notif = notificationListenerEnabled();
        boolean battery = batteryExempt();
        boolean detector = AppState.prefs(this).getBoolean(AppState.KEY_DETECTOR_ACTIVE, false);
        boolean rec = AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false);
        String kind = AppState.prefs(this).getString(AppState.KEY_CALL_KIND, "-");
        boolean notificationCall = AppState.prefs(this).getBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false);
        boolean audioCall = AppState.prefs(this).getBoolean(AppState.KEY_AUDIO_CALL_ACTIVE, false);
        boolean call = notificationCall || audioCall;
        boolean ready = mic && battery && detector;

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();

        status.setText("SEGUNDO PLANO: " + (ready ? "LISTO" : "FALTA CONFIGURAR")
                + "\n\nMicrófono: " + mark(mic)
                + "\nDetector persistente: " + mark(detector)
                + "\nClasificación WhatsApp: " + mark(notif)
                + "\nBatería sin restricción: " + mark(battery)
                + "\nModo de audio: " + audioModeLabel(mode)
                + "\nLlamada detectada: " + (call ? kind : "NO")
                + "\nGrabando ahora: " + (rec ? "SÍ" : "NO"));
    }

    private String audioModeLabel(int mode) {
        if (mode == AudioManager.MODE_IN_CALL) return "IN_CALL";
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return "IN_COMMUNICATION";
        if (mode == AudioManager.MODE_RINGTONE) return "RINGTONE";
        return "NORMAL";
    }

    private boolean notificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private boolean batteryExempt() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void addButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private String mark(boolean b) { return b ? "OK" : "PENDIENTE"; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String humanSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
