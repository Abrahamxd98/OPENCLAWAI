package com.local.wacallrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshRecordings();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        root.addView(text("Grabador de llamadas · v0.2.0", 24, true));
        root.addView(text("Detecta en segundo plano llamadas celulares, WhatsApp y WhatsApp Business. Cuando detecta una llamada, muestra una notificación con el botón Grabar. La grabación continúa aunque cierres la app y termina al finalizar la llamada.", 15, false));

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
        addButton("Actualizar estado", v -> { refreshStatus(); refreshRecordings(); });

        TextView info = text("Al recibir o realizar una llamada compatible aparecerá una notificación: CELULAR, WhatsApp o WhatsApp Business. Toca Grabar para iniciar. Mientras graba habrá una notificación persistente con Detener.", 14, false);
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
        else Toast.makeText(this, "Permisos básicos concedidos", Toast.LENGTH_SHORT).show();
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
        boolean rec = AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false);
        String kind = AppState.prefs(this).getString(AppState.KEY_CALL_KIND, "-");
        boolean call = AppState.prefs(this).getBoolean(AppState.KEY_NOTIFICATION_CALL_ACTIVE, false);
        boolean ready = mic && notif && battery;

        status.setText("SEGUNDO PLANO: " + (ready ? "LISTO" : "FALTA CONFIGURAR")
                + "\n\nMicrófono: " + mark(mic)
                + "\nDetección de llamadas: " + mark(notif)
                + "\nBatería sin restricción: " + mark(battery)
                + "\nLlamada detectada: " + (call ? kind : "NO")
                + "\nGrabando ahora: " + (rec ? "SÍ" : "NO"));
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
