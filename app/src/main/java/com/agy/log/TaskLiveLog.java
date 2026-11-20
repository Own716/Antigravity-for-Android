package com.agy.log;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Breadcrumb log for the Live Updates chain (web observer -> bridge ->
 * notifier), written to files/logs/tasklive.log and included in the
 * diagnostics export, so on-device failures are visible without logcat.
 */
public final class TaskLiveLog {

    private static final long MAX_BYTES = 128 * 1024L;

    private TaskLiveLog() {
    }

    public static synchronized void append(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        try {
            File dir = new File(context.getFilesDir(), "logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, "tasklive.log");
            if (file.exists() && file.length() > MAX_BYTES) {
                // Keep only the tail by starting fresh; breadcrumbs are recent anyway.
                file.delete();
            }
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Diagnostics must never break the feature they observe.
        }
    }
}
