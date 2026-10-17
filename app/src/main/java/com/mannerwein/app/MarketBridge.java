package com.mannerwein.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only bridge for public futures market metadata. No API keys or trading access. */
final class MarketBridge {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "contract.mexc.com", "fapi.binance.com", "api.bybit.com",
            "mw-scanner.flast8021.workers.dev"));   // MEXC fallback proxy (Cloudflare worker)
    private final WebView web;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    MarketBridge(WebView web) { this.web = web; }

    @JavascriptInterface
    public void get(String requestId, String rawUrl) {
        pool.execute(() -> {
            boolean ok = false;
            String result;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(rawUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol()) || !HOSTS.contains(url.getHost()))
                    throw new SecurityException("Host is not allowed");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "MannerWein-Android/5.3");
                int code = conn.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                result = read(in);
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + result);
                ok = true;
            } catch (Exception e) {
                result = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String js = "window.MWMarketResult(" + JSONObject.quote(requestId) + "," + ok + "," + JSONObject.quote(result) + ")";
            MAIN.post(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
        });
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192]; int n;
            while ((n = r.read(buf)) >= 0) out.append(buf, 0, n);
        }
        return out.toString();
    }

    void shutdown() { pool.shutdownNow(); }
}
