package com.agy.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Android 16 Live Updates ("promoted ongoing") notification mirroring the
 * agent task state reported by the web UI: a status-bar live card while a
 * task runs, plus a short completion notification when it finishes.
 *
 * Notification.ProgressStyle and promotion are API 36 surface, reached
 * reflectively so the app still compiles against its older android.jar and
 * runs on older Android with a plain indeterminate-progress fallback.
 */
public final class TaskLiveNotifier {

    private static final String TAG = "TaskLiveNotifier";
    // v2 channel: the original channel was created at IMPORTANCE_DEFAULT,
    // which is immutable and too quiet (no heads-up, easy to miss entirely).
    private static final String CHANNEL_ID = "agy_task_live2";
    private static final int LIVE_ID = 7710;
    private static final int DONE_ID = 7711;

    private static volatile long runningSince = 0L;
    private static volatile long lastSignalAt = 0L;
    private static volatile String currentTitle = "";
    private static volatile boolean stalled = false;
    private static android.net.wifi.WifiManager.WifiLock wifiLock;

    private static final String PREFS = "agy_task_live";
    private static final String KEY_ENABLED = "enabled";

    public static boolean isRunning() {
        return runningSince > 0L;
    }

    /** Last time any web-side signal reaffirmed the run (start or keepalive). */
    public static long lastSignalAt() {
        return lastSignalAt;
    }

    /** When the current run started (0 when not running). */
    public static long runningSinceMillis() {
        return runningSince;
    }

    /**
     * Core-monitor driven start/refresh: the core's own cascade state says
     * a task is active. Starts the live notification if needed, refreshes
     * the title when it changes.
     */
    public static void monitorActive(Context context, String title) {
        if (context == null || !isEnabled(context)) {
            return;
        }
        Context ctx = context.getApplicationContext();
        String t = (title == null || title.trim().isEmpty())
                ? currentTitle : title.trim();
        if (t == null || t.isEmpty()) {
            t = "任务";
        }
        if (!isRunning()) {
            runningSince = System.currentTimeMillis();
            lastSignalAt = runningSince;
            currentTitle = t;
            stalled = false;
            acquireWifiLock(ctx);
            postLive(ctx, t);
            com.agy.log.TaskLiveLog.append(ctx, "notifier MONITOR started: " + t);
        } else if (!t.isEmpty() && !t.equals(currentTitle)) {
            currentTitle = t;
            lastSignalAt = System.currentTimeMillis();
            postLive(ctx, t);
        }
    }

    /**
     * Core-monitor driven completion: the core reports no active cascade,
     * so the task is genuinely over (finished, errored or canceled).
     */
    public static void monitorIdle(Context context) {
        if (context == null || !isRunning()) {
            return;
        }
        Context ctx = context.getApplicationContext();
        long started = runningSince;
        runningSince = 0L;
        stalled = false;
        releaseWifiLock(ctx);
        postCompleted(ctx, currentTitle, started);
        com.agy.log.TaskLiveLog.append(ctx, "notifier MONITOR completed");
    }

    public static String currentTitle() {
        return currentTitle;
    }

    public static boolean isStalled() {
        return stalled;
    }

    /**
     * Keep the WiFi radio awake while a task runs: the core's model calls
     * are long HTTPS streams, and a sleeping radio in the background is
     * what turned them into timeout errors. (A partial wake lock for the
     * CPU is already held by the core service for its whole lifetime.)
     */
    private static synchronized void acquireWifiLock(Context ctx) {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                return;
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            int mode = android.os.Build.VERSION.SDK_INT >= 29
                    ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : android.net.wifi.WifiManager.WIFI_MODE_FULL;
            wifiLock = wm.createWifiLock(mode, "agy:tasklive");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            com.agy.log.TaskLiveLog.append(ctx, "wifilock acquired");
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "wifilock FAILED: " + e);
        }
    }

    private static synchronized void releaseWifiLock(Context ctx) {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                com.agy.log.TaskLiveLog.append(ctx, "wifilock released");
            }
        } catch (Exception ignored) {
        }
    }

    private TaskLiveNotifier() {
    }

    public static boolean isEnabled(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        try {
            Context ctx = context.getApplicationContext();
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, enabled).apply();
            if (!enabled) {
                manager(ctx).cancel(LIVE_ID);
                runningSince = 0L;
                stalled = false;
                releaseWifiLock(ctx);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Send-hook entry: the user just pressed send. Post the live
     * notification immediately - the web observer's timers may be frozen
     * by the time its delayed scan would have run.
     */
    public static void taskStarted(Context context, String title) {
        if (context == null || !isEnabled(context)) {
            return;
        }
        Context ctx = context.getApplicationContext();
        String safeTitle = (title == null || title.trim().isEmpty())
                ? "Antigravity" : title.trim();
        try {
            runningSince = System.currentTimeMillis();
            lastSignalAt = runningSince;
            currentTitle = safeTitle;
            stalled = false;
            acquireWifiLock(ctx);
            postLive(ctx, safeTitle);
            com.agy.log.TaskLiveLog.append(ctx, "notifier taskStarted posted LIVE");
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "notifier taskStarted FAILED: " + e);
        }
    }

    /**
     * Heartbeat verdict from the core service: the core stopped answering
     * while a task is supposedly running. Tell the truth on the island
     * instead of leaving a stale "running" up.
     */
    public static void postStalled(Context context) {
        if (context == null || !isRunning() || stalled) {
            return;
        }
        stalled = true;
        Context ctx = context.getApplicationContext();
        try {
            ensureChannel(ctx);
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(appIcon(ctx))
                    .setContentTitle(currentTitle)
                    .setContentText("连接中断 · 任务可能已暂停")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(openAppIntent(ctx));
            applyPromotionOnly(b, "已中断");
            manager(ctx).notify(LIVE_ID, b.build());
            com.agy.log.TaskLiveLog.append(ctx, "notifier posted STALLED");
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "notifier STALLED FAILED: " + e);
        }
    }

    /** Heartbeat recovered: put the running state back on the island. */
    public static void clearStalled(Context context) {
        if (context == null || !isRunning() || !stalled) {
            return;
        }
        stalled = false;
        Context ctx = context.getApplicationContext();
        try {
            postLive(ctx, currentTitle);
            com.agy.log.TaskLiveLog.append(ctx, "notifier STALLED cleared, LIVE restored");
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "notifier clearStalled FAILED: " + e);
        }
    }

    /** Settings test: show the live state, then flip to completed ~6s later. */
    public static void test(final Context context) {
        if (context == null) {
            return;
        }
        final Context ctx = context.getApplicationContext();
        try {
            runningSince = System.currentTimeMillis();
            postLive(ctx, "灵动岛测试");
            com.agy.log.TaskLiveLog.append(ctx, "notifier TEST posted LIVE");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            long started = runningSince;
                            runningSince = 0L;
                            postCompleted(ctx, "灵动岛测试", started);
                        }
                    }, 6000L);
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "notifier TEST FAILED: " + e);
        }
    }

    public static void update(Context context, String state, String title) {
        if (context == null || state == null) {
            return;
        }
        if (!isEnabled(context)) {
            return;
        }
        Context ctx = context.getApplicationContext();
        String safeTitle = (title == null || title.trim().isEmpty())
                ? "Antigravity" : title.trim();
        com.agy.log.TaskLiveLog.append(ctx, "notifier update state=" + state
                + " sdk=" + Build.VERSION.SDK_INT
                + " notificationsEnabled=" + manager(ctx).areNotificationsEnabled());
        try {
            if ("running".equals(state)) {
                if (runningSince == 0L) {
                    runningSince = System.currentTimeMillis();
                    acquireWifiLock(ctx);
                }
                lastSignalAt = System.currentTimeMillis();
                currentTitle = safeTitle;
                stalled = false;
                postLive(ctx, safeTitle);
            } else if ("done".equals(state)) {
                if (runningSince == 0L) {
                    return; // already concluded (e.g. by the core monitor)
                }
                long started = runningSince;
                runningSince = 0L;
                stalled = false;
                releaseWifiLock(ctx);
                postCompleted(ctx, safeTitle, started);
            } else if ("idle".equals(state)) {
                runningSince = 0L;
                manager(ctx).cancel(LIVE_ID);
            }
        } catch (Exception e) {
            Log.w(TAG, "task live update failed", e);
            com.agy.log.TaskLiveLog.append(ctx, "notifier FAILED: " + e);
        }
    }

    private static NotificationManager manager(Context ctx) {
        ensureChannel(ctx);
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "任务进度", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("任务运行状态（Live Updates）");
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent openAppIntent(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 0, intent, flags);
    }

    private static int appIcon(Context ctx) {
        int icon = ctx.getApplicationInfo().icon;
        return icon != 0 ? icon : android.R.drawable.stat_notify_sync;
    }

    private static void postLive(Context ctx, String title) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(appIcon(ctx))
                .setContentTitle(title)
                .setContentText("任务进行中")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(runningSince)
                .setUsesChronometer(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent(ctx));
        boolean styled = applyProgressStyle(ctx, b, false);
        if (!styled) {
            b.setProgress(0, 0, true);
        }
        manager(ctx).notify(LIVE_ID, b.build());
        com.agy.log.TaskLiveLog.append(ctx,
                "notifier posted LIVE progressStyle=" + styled);
    }

    /**
     * Completion state: instead of cancelling the live notification the
     * moment the task ends (the island just vanished), the SAME live
     * notification is updated in place to a completed state - full progress,
     * "已完成" chip text - and lingers ~45s before timing out, so the end
     * state is actually seen.
     */
    private static void postCompleted(Context ctx, String title, long started) {
        String text = title;
        if (started > 0L) {
            long secs = Math.max(1L, (System.currentTimeMillis() - started) / 1000L);
            text = title + " · 用时 " + (secs >= 60
                    ? (secs / 60) + " 分 " + (secs % 60) + " 秒"
                    : secs + " 秒");
        }
        // The completed state must stay ONGOING: promotion to the live
        // island is only granted to ongoing notifications, so flipping to
        // ongoing=false demoted "任务完成" to a plain shade entry the
        // moment it posted (Qin's 2.8.1 test). Ongoing + timeoutAfter
        // keeps it promoted on the island and still self-dismisses.
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(appIcon(ctx))
                .setContentTitle("任务完成")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent(ctx));
        boolean styled = applyProgressStyle(ctx, b, true);
        if (!styled) {
            b.setProgress(100, 100, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setTimeoutAfter(45000L);
        }
        manager(ctx).notify(LIVE_ID, b.build());
        com.agy.log.TaskLiveLog.append(ctx,
                "notifier posted COMPLETED (lingering) progressStyle=" + styled);
    }

    /** Promoted-ongoing request + chip text only (no progress style). */
    private static void applyPromotionOnly(Notification.Builder b, String shortText) {
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
        } catch (Exception ignored) {
        }
        try {
            Notification.Builder.class
                    .getMethod("setShortCriticalText", CharSequence.class)
                    .invoke(b, shortText);
        } catch (Exception ignored) {
        }
    }

    /**
     * Applies Notification.ProgressStyle + promoted-ongoing request + the
     * status-bar chip text via reflection (API 36). Returns false when the
     * platform is too old, so the caller can fall back to a plain progress bar.
     */
    private static boolean applyProgressStyle(Context ctx, Notification.Builder b,
                                              boolean completed) {
        try {
            Class<?> psClass = Class.forName("android.app.Notification$ProgressStyle");
            Object style = psClass.getDeclaredConstructor().newInstance();
            if (completed) {
                psClass.getMethod("setProgress", int.class).invoke(style, 100);
            } else {
                try {
                    Method ind = psClass.getMethod("setProgressIndeterminate", boolean.class);
                    ind.invoke(style, true);
                } catch (NoSuchMethodException e) {
                    psClass.getMethod("setProgress", int.class).invoke(style, 0);
                }
            }
            // Actually SET the tracker icon (previously only probed for the
            // method's existence); some OEM promotion checks want it present.
            try {
                android.graphics.drawable.Icon tracker =
                        android.graphics.drawable.Icon.createWithResource(ctx, appIcon(ctx));
                psClass.getMethod("setProgressTrackerIcon",
                        android.graphics.drawable.Icon.class).invoke(style, tracker);
                com.agy.log.TaskLiveLog.append(ctx, "notifier trackerIcon=set");
            } catch (Exception e) {
                com.agy.log.TaskLiveLog.append(ctx, "notifier trackerIcon FAILED: " + e);
            }
            b.setStyle((Notification.Style) style);
            try {
                Notification.Builder.class
                        .getMethod("setRequestPromotedOngoing", boolean.class)
                        .invoke(b, true);
                com.agy.log.TaskLiveLog.append(ctx, "notifier promotedRequest=ok");
            } catch (Exception e) {
                com.agy.log.TaskLiveLog.append(ctx, "notifier promotedRequest FAILED: " + e);
            }
            try {
                Notification.Builder.class
                        .getMethod("setShortCriticalText", CharSequence.class)
                        .invoke(b, completed ? "已完成" : "运行中");
                com.agy.log.TaskLiveLog.append(ctx, "notifier shortCriticalText=ok");
            } catch (Exception e) {
                com.agy.log.TaskLiveLog.append(ctx, "notifier shortCriticalText FAILED: " + e);
            }
            return true;
        } catch (Exception e) {
            com.agy.log.TaskLiveLog.append(ctx, "notifier progressStyle FAILED: " + e);
            return false;
        }
    }
}
