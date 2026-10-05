package com.mannerwein.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * window.MWNative inside both WebViews (the app screen and the hidden background page).
 * - httpGet: exchange requests made by the phone itself (no browser CORS, no proxy needed)
 * - bgSet / bgState / openBatterySettings: the Background scanner card
 * - bgDone: end of a background scan (only used by the service)
 */
class NativeBridge {
    interface Listener { void onScanDone(String json); void onProgress(String msg); }

    private static final String[] HOSTS = { "contract.mexc.com", "fapi.binance.com", "api.bybit.com" };
    private static final ExecutorService NET = Executors.newFixedThreadPool(4);
    // Main-thread handler. web.post() would never run for the background WebView: it is not attached
    // to a window, so Android parks posted work until it is attached (this caused the scan time-outs).
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context ctx;
    private final WebView web;
    private final Activity activity;   // null in the background service
    private final Listener listener;   // null on the app screen

    NativeBridge(Context ctx, WebView web, Activity activity, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
        this.activity = activity;
        this.listener = listener;
    }

    @JavascriptInterface
    public void httpGet(final String id, final String url) {
        NET.execute(() -> {
            int code = 0;
            String body;
            HttpURLConnection con = null;
            try {
                URL u = new URL(url);
                boolean ok = "https".equals(u.getProtocol());
                if (ok) {
                    ok = false;
                    for (String h : HOSTS) if (h.equals(u.getHost())) ok = true;
                }
                if (!ok) throw new SecurityException("Blocked host " + u.getHost());
                con = (HttpURLConnection) u.openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MannerWein");
                code = con.getResponseCode();
                InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                body = in == null ? "" : read(in);
                Thread.sleep(80);   // gentle on exchange rate limits
            } catch (Exception e) {
                code = 0;
                body = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (con != null) con.disconnect();
            }
            final String js = "window.MWNet&&MWNet.done(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
            MAIN.post(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
        });
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    @JavascriptInterface
    public void bgSet(boolean on, String ex) {
        Prefs.set(ctx, on, ex);
        if (on) {
            if (activity != null && Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.runOnUiThread(() -> activity.requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 7));
            }
            Intent i = new Intent(ctx, ScanService.class).setAction(ScanService.ACT_START);
            ctx.startForegroundService(i);
        } else {
            Prefs.next(ctx, 0);
            Prefs.status(ctx, "");
            ctx.startService(new Intent(ctx, ScanService.class).setAction(ScanService.ACT_STOP));
        }
    }

    @JavascriptInterface
    public String bgState() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean notif = Build.VERSION.SDK_INT < 33
                    || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            JSONObject o = new JSONObject();
            o.put("on", Prefs.on(ctx));
            o.put("ex", Prefs.ex(ctx));
            o.put("msg", Prefs.msg(ctx));
            o.put("last", Prefs.last(ctx));
            o.put("next", Prefs.next(ctx));
            o.put("notif", notif);
            o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void openBatterySettings() {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
                activity.startActivity(i);
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
    }

    @JavascriptInterface
    public void bgProgress(String msg) {
        if (listener != null) listener.onProgress(msg);
    }

    @JavascriptInterface
    public void bgDone(String json) {
        if (listener != null) listener.onScanDone(json);
    }
}
