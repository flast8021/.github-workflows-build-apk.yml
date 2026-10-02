package com.mannerwein.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * Hosts the Manner Wein scanner/checklist/calculator tool (app/src/main/assets/index.html)
 * inside a locked-down WebView. All calculator and risk logic is unchanged from the
 * original HTML/JS; only the CSV export is bridged to Android's document picker so
 * files save to a location the user chooses, instead of relying on a browser download.
 */
public class MainActivity extends Activity {

    private WebView web;
    private String pendingCsv;
    private static final int REQ_SAVE_CSV = 41;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"appassets.androidplatform.net".equals(uri.getHost()) || uri.getPath() == null
                        || !uri.getPath().startsWith("/assets/")) {
                    return null;
                }
                String name = uri.getPath().substring(8);
                if (name.contains("..")) return null;
                try {
                    String mime = name.endsWith(".png") ? "image/png"
                            : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                            : "text/html";
                    return new WebResourceResponse(mime, name.endsWith(".html") ? "UTF-8" : null,
                            getAssets().open(name));
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return true;
            }
        });

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
