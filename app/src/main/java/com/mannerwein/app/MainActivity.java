package com.mannerwein.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * Hosts the Manner Wein scanner/checklist/calculator tool (app/src/main/assets/index.html)
 * inside a locked-down WebView. All calculator and risk logic is unchanged from the
 * original HTML/JS; the CSV export is bridged to Android's document picker so
 * files save to a location the user chooses, instead of relying on a browser download.
 * MWNative adds phone-side exchange requests and the Background scanner controls.
 */
public class MainActivity extends Activity {

    private WebView web;
    private String pendingCsv;
    private static final int REQ_SAVE_CSV = 41;
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
        web.setWebViewClient(new AppWebClient(this, true));

        web.loadUrl(HOME);
    }

    public class Bridge {
        @JavascriptInterface
        public void saveCsv(String name, String text) {
            runOnUiThread(() -> {
                pendingCsv = text;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
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
                Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "CSV save failed", Toast.LENGTH_LONG).show();
            }
            pendingCsv = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Show the latest background results and Scanner performance when the app comes back
        if (web != null) web.evaluateJavascript("window.MW&&MW.app&&MW.app.resume()", null);
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
