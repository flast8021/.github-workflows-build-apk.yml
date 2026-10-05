package com.mannerwein.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Background scanner. Runs the same scanner code as the app (a hidden copy of index.html)
 * every 5 minutes, outside quiet hours (22:00 to 06:00 phone time), and posts a notification
 * only when a setup turns Ready. Signals are saved to the same Scanner performance store the app shows.
 */
public class ScanService extends Service implements NativeBridge.Listener {
    static final String ACT_START = "com.mannerwein.app.START";
    static final String ACT_STOP = "com.mannerwein.app.STOP";
    static final String ACT_TICK = "com.mannerwein.app.TICK";

    private static final long INTERVAL = 5 * 60 * 1000L;
    private static final long RUN_TIMEOUT = 4 * 60 * 1000L;
    private static final int QUIET_FROM = 22, QUIET_TO = 6;
    private static final int NOTE_ID = 1;
    private static final String CH_RUN = "scanner", CH_ALERT = "alerts";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean pageReady, running;
    private PowerManager.WakeLock wake;

    private final Runnable tick = this::tick;
    private final Runnable timeout = () -> {
        running = false;
        Prefs.ran(this, System.currentTimeMillis(), "Last scan timed out, retrying");
        pageReady = false;
        if (web != null) web.loadUrl(AppWebClient.HOME + "?bg=1");   // reload() would drop ?bg=1
        scheduleNext();
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Scanner running", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("Shown while the background scanner is on");
        nm.createNotificationChannel(run);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Ready setups", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("A setup turned Ready");
        nm.createNotificationChannel(alert);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null || intent.getAction() == null ? ACT_START : intent.getAction();
        if (ACT_STOP.equals(a)) {
            Prefs.setOn(this, false);
            shutdown();
            return START_NOT_STICKY;
        }
        goForeground(Prefs.msg(this).isEmpty() ? "Starting..." : Prefs.msg(this));
        if (!Prefs.on(this)) { shutdown(); return START_NOT_STICKY; }
        ensureWeb();
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private void goForeground(String text) {
        Notification n = runningNote(text);
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTE_ID, n);
    }

    private void ensureWeb() {
        if (web != null) return;
        web = new WebView(this);
        AppWebClient.configure(web);
        web.addJavascriptInterface(new NativeBridge(this, web, null, this), "MWNative");
        web.setWebViewClient(new AppWebClient(this, false) {
            @Override public void onPageFinished(WebView view, String url) { pageReady = true; }
        });
        web.resumeTimers();
        pageReady = false;
        web.loadUrl(AppWebClient.HOME + "?bg=1");
    }

    private void tick() {
        h.removeCallbacks(tick);
        if (!Prefs.on(this)) { shutdown(); return; }
        if (quietNow()) {
            long end = quietEnd();
            releaseWake();
            Prefs.next(this, end);
            Prefs.status(this, "Paused for quiet hours (22:00 to 06:00)");
            updateNote("Quiet hours. Next scan at " + hhmm(end));
            alarmAt(end);
            h.postDelayed(tick, Math.max(60000, end - System.currentTimeMillis()));
            return;
        }
        acquireWake();
        if (running) return;
        if (!pageReady || web == null) { ensureWeb(); h.postDelayed(tick, 3000); return; }
        running = true;
        Prefs.status(this, "Scanning " + exName(Prefs.ex(this)) + "...");
        updateNote("Scanning " + exName(Prefs.ex(this)) + "...");
        web.evaluateJavascript("window.MW&&MW.bg&&MW.bg.run(" + JSONObject.quote(Prefs.ex(this)) + ")", null);
        h.postDelayed(timeout, RUN_TIMEOUT);
    }

    @Override
    public void onScanDone(final String json) {
        h.post(() -> {
            h.removeCallbacks(timeout);
            running = false;
            String status = "Scan finished";
            int n = 0;
            try {
                JSONObject o = new JSONObject(json);
                status = o.optString("status", status);
                JSONArray ready = o.optJSONArray("ready");
                if (ready != null) {
                    n = ready.length();
                    for (int i = 0; i < ready.length(); i++) alert(ready.getJSONObject(i));
                }
            } catch (Exception ignored) {}
            Prefs.ran(this, System.currentTimeMillis(), exName(Prefs.ex(this)) + ": " + shorten(status) + (n > 0 ? " \u00B7 " + n + " new alert" + (n > 1 ? "s" : "") : ""));
            scheduleNext();
        });
    }

    private void scheduleNext() {
        if (!Prefs.on(this)) return;
        long next = System.currentTimeMillis() + INTERVAL;
        Prefs.next(this, next);
        updateNote(Prefs.msg(this) + " \u00B7 next " + hhmm(next));
        h.removeCallbacks(tick);
        h.postDelayed(tick, INTERVAL);
        alarmAt(next + 60000);   // backup in case the phone sleeps through the timer
    }

    // ---------- quiet hours ----------
    private static boolean quietNow() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hr >= QUIET_FROM || hr < QUIET_TO;
    }
    private static long quietEnd() {
        Calendar c = Calendar.getInstance();
        if (c.get(Calendar.HOUR_OF_DAY) >= QUIET_FROM) c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, QUIET_TO);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 5); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ---------- alarms / wake lock ----------
    private PendingIntent tickIntent() {
        Intent i = new Intent(this, ScanService.class).setAction(ACT_TICK);
        return PendingIntent.getForegroundService(this, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void alarmAt(long when) {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, tickIntent());
    }
    private void acquireWake() {
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MannerWein:scanner");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire();
    }
    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }

    // ---------- notifications ----------
    private Notification runningNote(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ScanService.class).setAction(ACT_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Scanner running")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
    private void updateNote(String text) {
        if (!canNotify()) return;
        getSystemService(NotificationManager.class).notify(NOTE_ID, runningNote(text));
    }
    private void alert(JSONObject s) {
        if (!canNotify()) return;
        boolean lng = s.optInt("dir", 1) == 1;
        String title = s.optString("base") + " " + (lng ? "LONG" : "SHORT") + " is Ready \u00B7 Grade " + s.optString("grade");
        String text = "Limit " + s.optString("entry") + " \u00B7 SL " + s.optString("sl") + " \u00B7 TP " + s.optString("tp")
                + " \u00B7 R:R 1:" + s.optString("rr");
        String more = text + "\n" + s.optString("type") + ", " + s.optString("tf") + " trigger. Expires " + s.optString("exp")
                + ". Confirm on your chart before entering.";
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(more))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open)
                .build();
        getSystemService(NotificationManager.class).notify((s.optString("base") + lng).hashCode(), n);
    }
    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- helpers ----------
    private static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)); }
    private static String exName(String ex) { return "binance".equals(ex) ? "Binance" : "bybit".equals(ex) ? "Bybit" : "MEXC"; }
    private static String shorten(String s) {
        s = s.replace("Done. ", "").trim();
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }

    private void shutdown() {
        h.removeCallbacksAndMessages(null);
        running = false;
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.cancel(tickIntent());
        releaseWake();
        if (web != null) { web.destroy(); web = null; }
        Prefs.next(this, 0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releaseWake();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
