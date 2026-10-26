package com.local.wacallrecorder;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugLog {
    private static final Object LOCK = new Object();
    private DebugLog() {}

    public static File baseDir(Context c) {
        File root = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File dir = new File(root, "WhatsAppCalls");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void write(Context c, String msg) {
        synchronized (LOCK) {
            try {
                File f = new File(baseDir(c), "events.log");
                FileWriter w = new FileWriter(f, true);
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                w.write(ts + " | " + msg.replace('\n', ' ') + "\n");
                w.close();
            } catch (Exception ignored) {}
        }
    }
}
