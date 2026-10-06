package com.mannerwein.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
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
 * every 5 minutes, outside quiet hours (22:00 to 06:00 phone time).
 * Posts a pop-up (heads-up) notification only when a setup turns Ready; nothing when there is none.
 * Each scan also re-checks tracked signals until their stop or target is hit (Scanner performance).
 */
public class ScanService extends Service implements NativeBridge.Listener {
    static final String ACT_START = "com.mannerwein.app.START";
    static final String ACT_STOP = "com.mannerwein.app.STOP";
    static final String ACT_TICK = "com.mannerwein.app.TICK";
    static final String EXTRA_ALERT = "fromAlert";

    private static final long INTERVAL = 5 * 60 * 1000L;
    private static final long STALL = 90 * 1000L;        // no progress for this long = stuck
    private static final long HARD_CAP = 6 * 60 * 1000L; // whole scan
    private static final int QUIET_FROM = 22, QUIET_TO = 6;
    private static final int NOTE_ID = 1, SUMMARY_ID = 2;
    // New channel ids: Android never lets an app raise the importance of an existing channel
    private static final String CH_RUN = "scanner_status", CH_ALERT = "ready_popup";
    private static final String GROUP = "mw_ready";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean pageReady, running;
    private long runStart, lastBeat;
    private int loadFails;
    private PowerManager.WakeLock wake;

    private final Runnable tick = this::tick;
    private final Runnable watchdog = this::watchdog;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        try { nm.deleteNotificationChannel("scanner"); nm.deleteNotificationChannel("alerts"); } catch (Exception ignored) {}
        NotificationChannel run = new NotificationChannel(CH_RUN, "Scanner status", NotificationManager.IMPORTANCE_MIN);
        run.setDescription("Silent notification Android requires while the background scanner is on");
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Ready setups", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Pop-up alert when a setup turns Ready");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{ 0, 250, 150, 250 });
        alert.enableLights(true);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
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
        if (!running) { h.removeCallbacks(tick); h.post(tick); }
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
        web.addJavascriptInterface(new DbBridge(this, null), "MWDb");
        web.setWebViewClient(new AppWebClient(this, false) {
            @Override public void onPageFinished(WebView view, String url) { pageReady = true; }
        });
        // Give the hidden page a size and keep it awake so its JavaScript is not paused
        web.layout(0, 0, 1080, 2340);
        web.onResume();
        web.resumeTimers();
        pageReady = false;
        web.loadUrl(AppWebClient.HOME + "?bg=1");
    }

    private void reloadPage() {
        pageReady = false;
        if (web != null) { web.destroy(); web = null; }
        ensureWeb();
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
        runStart = lastBeat = System.currentTimeMillis();
        final String ex = Prefs.ex(this);
        Prefs.status(this, "Scanning " + exName(ex) + "...");
        updateNote("Scanning " + exName(ex) + "...");
        web.evaluateJavascript("(window.MW&&MW.bg)?(MW.bg.run(" + JSONObject.quote(ex) + "),'started'):'missing'", r -> {
            if (r != null && r.contains("started")) { loadFails = 0; return; }
            // Page not ready yet: reload it and try again shortly
            running = false;
            h.removeCallbacks(watchdog);
            loadFails++;
            Prefs.status(this, "Scanner page not ready, retrying (" + loadFails + ")");
            reloadPage();
            h.postDelayed(tick, loadFails < 4 ? 5000 : INTERVAL);
        });
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 15000);
    }

    // Ends a scan that has stopped making progress, so the next one can run
    private void watchdog() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now - lastBeat < STALL && now - runStart < HARD_CAP) { h.postDelayed(watchdog, 15000); return; }
        running = false;
        Prefs.ran(this, now, "Last scan stalled (no data for " + ((now - lastBeat) / 1000) + "s). Check internet; retrying");
        reloadPage();
        scheduleNext();
    }

    @Override
    public void onProgress(final String msg) {
        h.post(() -> {
            lastBeat = System.currentTimeMillis();
            if (running) updateNote(exName(Prefs.ex(this)) + ": " + msg);
        });
    }

    @Override
    public void onScanDone(final String json) {
        h.post(() -> {
            h.removeCallbacks(watchdog);
            running = false;
            String status = "Scan finished";
            JSONArray ready = null;
            try {
                JSONObject o = new JSONObject(json);
                status = o.optString("status", status);
                ready = o.optJSONArray("ready");
            } catch (Exception ignored) {}
            int n = ready == null ? 0 : ready.length();
            if (n > 0) alertAll(ready);   // no Ready setup = no alert at all
            SignalDb.get(this).autoBackup();   // one rolling backup file per day
            Prefs.ran(this, System.currentTimeMillis(), exName(Prefs.ex(this)) + ": " + shorten(status)
                    + (n > 0 ? " \u00B7 " + n + " alert" + (n > 1 ? "s" : "") + " sent" : ""));
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
    private PendingIntent openApp(boolean fromAlert, int req) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ALERT, fromAlert);
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification runningNote(String text) {
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ScanService.class).setAction(ACT_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Background scanner on")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(false, 1))
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
    private void updateNote(String text) {
        if (!canNotify()) return;
        getSystemService(NotificationManager.class).notify(NOTE_ID, runningNote(text));
    }

    private void alertAll(JSONArray ready) {
        if (!canNotify()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < ready.length(); i++) {
            JSONObject s = ready.optJSONObject(i);
            if (s == null) continue;
            boolean lng = s.optInt("dir", 1) == 1;
            String head = s.optString("base") + " " + (lng ? "LONG" : "SHORT") + " Ready \u00B7 Grade " + s.optString("grade");
            String brief = "Limit " + s.optString("entry") + " \u00B7 SL " + s.optString("sl") + " \u00B7 TP " + s.optString("tp")
                    + " \u00B7 R:R 1:" + s.optString("rr");
            StringBuilder fb = new StringBuilder(brief);
            fb.append("\nSetup: ").append(s.optString("type")).append(", ").append(s.optString("tf"));
            if (!s.optString("level").isEmpty()) fb.append(" at ").append(s.optString("level"));
            if (!s.optString("zone").isEmpty()) fb.append("\nEntry zone: ").append(s.optString("zone"));
            if (!s.optString("t1").isEmpty()) fb.append("\nT1: ").append(s.optString("t1")).append(" \u00B7 T2: ").append(s.optString("t2"));
            if (!s.optString("ready").isEmpty()) fb.append("\nWhy Ready: ").append(s.optString("ready"));
            if (!s.optString("why").isEmpty()) fb.append("\nWhy this trade: ").append(s.optString("why"));
            if (!s.optString("inv").isEmpty()) fb.append("\nCancels if: ").append(s.optString("inv"));
            if (!s.optString("weak").isEmpty()) fb.append("\nStill weak: ").append(s.optString("weak"));
            if (!s.optString("weakest").isEmpty()) fb.append("\nWeakest rating: ").append(s.optString("weakest")).append(" (").append(s.optString("conf")).append("/100)");
            fb.append("\nValid until ").append(s.optString("exp")).append(". Tap to open the scan results. Confirm on your chart before entering.");
            String full = fb.toString();
            lines.append(head).append(": ").append(brief).append('\n');
            Notification n = new Notification.Builder(this, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(head)            // brief pop-up shows this line
                    .setContentText(brief)            // detailed pop-up shows this too
                    .setStyle(new Notification.BigTextStyle().setBigContentTitle(head).bigText(full))
                    .setTicker(head)
                    .setCategory(Notification.CATEGORY_EVENT)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setAutoCancel(true)
                    .setGroup(GROUP)
                    .setContentIntent(openApp(true, 100 + i))
                    .build();
            nm.notify((s.optString("base") + lng + s.optString("entry")).hashCode(), n);
        }
        if (ready.length() > 1) {
            Notification sum = new Notification.Builder(this, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(ready.length() + " setups Ready")
                    .setContentText("Tap to open the scan results")
                    .setStyle(new Notification.BigTextStyle().bigText(lines.toString().trim()))
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(true, 99))
                    .build();
            nm.notify(SUMMARY_ID, sum);
        }
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
