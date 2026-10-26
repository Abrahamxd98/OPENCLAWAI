package com.local.wacallrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler handler = new Handler(Looper.getMainLooper());

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

        root.addView(text("Grabador WA · v0.1.4", 24, true));
        root.addView(text("Prueba directa del motor de audio. Durante una llamada de WhatsApp usa ALTAVOZ. Inicia y detén la grabación desde estos botones.", 15, false));

        status = text("", 14, false);
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status);

        addButton("▶ INICIAR GRABACIÓN", v -> startManualRecording());
        addButton("■ DETENER GRABACIÓN", v -> stopManualRecording());

        TextView setup = text("Configuración", 18, true);
        setup.setPadding(0, dp(18), 0, dp(6));
        root.addView(setup);
        addButton("Conceder micrófono y notificaciones", v -> requestRuntimePermissions());
        addButton("Activar acceso a notificaciones", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addButton("Actualizar estado", v -> { refreshStatus(); refreshRecordings(); });

        TextView h = text("Grabaciones", 19, true);
        h.setPadding(0, dp(20), 0, dp(8));
        root.addView(h);

        setContentView(scroll);
    }

    private void startManualRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            Toast.makeText(this, "Concede el permiso de micrófono y vuelve a pulsar INICIAR", Toast.LENGTH_LONG).show();
            return;
        }
        if (AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false)) {
            Toast.makeText(this, "Ya hay una grabación activa", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        Intent i = new Intent(this, RecorderForegroundService.class);
        i.setAction(RecorderForegroundService.ACTION_START);
        String contact = AppState.prefs(this).getString(AppState.KEY_CONTACT, "Prueba_WhatsApp");
        if (contact == null || contact.trim().isEmpty()) contact = "Prueba_WhatsApp";
        i.putExtra("contact", contact);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "Iniciando grabación…", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> { refreshStatus(); refreshRecordings(); }, 700);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopManualRecording() {
        Intent i = new Intent(this, RecorderForegroundService.class);
        i.setAction(RecorderForegroundService.ACTION_STOP);
        try {
            startService(i);
            Toast.makeText(this, "Deteniendo y guardando…", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> { refreshStatus(); refreshRecordings(); }, 1200);
        } catch (Exception e) {
            stopService(new Intent(this, RecorderForegroundService.class));
            handler.postDelayed(() -> { refreshStatus(); refreshRecordings(); }, 1200);
        }
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
        int count = Math.min(files.length, 20);
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

    private void refreshStatus() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notif = notificationListenerEnabled();
        boolean rec = AppState.prefs(this).getBoolean(AppState.KEY_RECORDING_ACTIVE, false);
        status.setText("Micrófono: " + mark(mic)
                + "\nAcceso a notificaciones: " + mark(notif)
                + "\nGrabando ahora: " + (rec ? "SÍ" : "NO")
                + "\n\nPrueba recomendada:\n1) Inicia una llamada de WhatsApp y pon ALTAVOZ.\n2) Vuelve a Grabador WA y pulsa INICIAR GRABACIÓN.\n3) Regresa a WhatsApp y hablen 20–30 s.\n4) Vuelve aquí y pulsa DETENER GRABACIÓN.\n5) Reproduce el archivo generado.");
    }

    private boolean notificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
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
