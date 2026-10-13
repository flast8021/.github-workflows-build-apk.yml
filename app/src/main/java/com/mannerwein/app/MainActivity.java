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
 * Hosts the Manner Wein calculator, risk management and trade log (app/src/main/assets/index.html)
 * inside a locked-down WebView. Export CSV is bridged to Android's "Save as" picker so the file
 * goes to a location the user chooses.
 */
public class MainActivity extends Activity {

    private WebView web;
    private String pendingText;
    private static final int REQ_SAVE = 41;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        AppWebClient.configure(web);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new AppWebClient(this, true));
        web.loadUrl(AppWebClient.HOME);
    }

    public class Bridge {
        @JavascriptInterface
        public void saveCsv(String name, String text) { saveFile(name, "text/csv", text); }

        @JavascriptInterface
        public void saveFile(String name, String mime, String text) {
            runOnUiThread(() -> {
                pendingText = text;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, REQ_SAVE);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingText != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                out.write("\uFEFF".getBytes("UTF-8"));   // BOM so Excel reads UTF-8 correctly
                out.write(pendingText.getBytes("UTF-8"));
                Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed", Toast.LENGTH_LONG).show();
            }
        }
        pendingText = null;
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
