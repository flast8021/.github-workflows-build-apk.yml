package com.mannerwein.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hosts the Manner Wein scanner/checklist/calculator tool (app/src/main/assets/index.html)
 * inside a locked-down WebView. All calculator and risk logic is unchanged from the
 * original HTML/JS; the CSV export is bridged to Android's document picker so
 * files save to a location the user chooses, instead of relying on a browser download.
 * MWNative adds phone-side exchange requests and the Background scanner controls.
 */
public class MainActivity extends Activity implements DbBridge.Picker {

    private WebView web;
    private String pendingCsv;
    private String pendingMime = "text/csv";
    private static final int REQ_SAVE_CSV = 41;
    private static final int REQ_RESTORE = 42;
    private static final String HOME = AppWebClient.HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        AppWebClient.configure(web);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.addJavascriptInterface(new NativeBridge(this, web, this, null), "MWNative");
        web.addJavascriptInterface(new DbBridge(this, this), "MWDb");
        web.setWebViewClient(new AppWebClient(this, true));

        web.loadUrl(HOME);
    }

    public class Bridge {
        @JavascriptInterface
        public void saveCsv(String name, String text) { saveFile(name, "text/csv", text); }

        /** Saves any text file (CSV export, JSON history backup) to a place the user chooses. */
        @JavascriptInterface
        public void saveFile(String name, String mime, String text) {
            runOnUiThread(() -> {
                pendingCsv = text;
                pendingMime = mime == null || mime.isEmpty() ? "text/plain" : mime;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(pendingMime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, REQ_SAVE_CSV);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_CSV && resultCode == RESULT_OK && data != null && pendingCsv != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                out.write(pendingCsv.getBytes("UTF-8"));
                Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed", Toast.LENGTH_LONG).show();
            }
            pendingCsv = null;
        }
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String res;
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                res = "{\"added\":" + SignalDb.get(this).importJson(out.toString("UTF-8")) + "}";
            } catch (Exception e) {
                res = "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
            final String js = "window.MW&&MW.perf&&MW.perf.restored(" + res + ")";
            web.evaluateJavascript(js, null);
        }
    }

    @Override
    public void pickRestoreFile() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Show the latest background results and Scanner performance when the app comes back.
        // Opened from a Ready alert: also jump to the results.
        boolean fromAlert = getIntent() != null && getIntent().getBooleanExtra(ScanService.EXTRA_ALERT, false);
        if (fromAlert) getIntent().removeExtra(ScanService.EXTRA_ALERT);
        if (web != null) web.evaluateJavascript("window.MW&&MW.app&&MW.app.resume(" + fromAlert + ")", null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
