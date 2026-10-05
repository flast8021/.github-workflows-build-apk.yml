package com.mannerwein.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Serves app/src/main/assets from https://appassets.androidplatform.net/assets/ and keeps the WebView locked down. */
class AppWebClient extends WebViewClient {
    static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private final Context ctx;
    private final boolean openLinks;

    AppWebClient(Context ctx, boolean openLinks) { this.ctx = ctx; this.openLinks = openLinks; }

    static void configure(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    }

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
            return new WebResourceResponse(mime, name.endsWith(".html") ? "UTF-8" : null, ctx.getAssets().open(name));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
        if (openLinks && "https".equals(uri.getScheme())) {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }
        return true;
    }
}
