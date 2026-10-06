package com.mannerwein.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * window.MWDb: the scanner history database, available in both the app screen and the background page.
 * All methods are synchronous and return strings/numbers so the page can use them directly.
 */
class DbBridge {
    interface Picker { void pickRestoreFile(); }

    private final SignalDb db;
    private final Picker picker;   // null in the background service

    DbBridge(Context ctx, Picker picker) { this.db = SignalDb.get(ctx); this.picker = picker; }

    @JavascriptInterface public String insert(String json) {
        try { return db.insert(new JSONObject(json)); } catch (Exception e) { return ""; }
    }
    @JavascriptInterface public boolean update(String uid, String json) {
        try { return db.update(uid, new JSONObject(json)); } catch (Exception e) { return false; }
    }
    @JavascriptInterface public String list(double from, double to, int archived) {
        return db.list((long) from, (long) to, archived).toString();
    }
    @JavascriptInterface public String open() { return db.open(System.currentTimeMillis()).toString(); }
    @JavascriptInterface public void runLog(String json) {
        try { db.logRun(new JSONObject(json)); } catch (Exception ignored) { }
    }
    @JavascriptInterface public String runs(int limit) { return db.runs(limit).toString(); }
    @JavascriptInterface public int archive(double from, double to, boolean on) { return db.archive((long) from, (long) to, on); }
    @JavascriptInterface public int deleteRange(double from, double to) { return db.deleteRange((long) from, (long) to); }
    @JavascriptInterface public int count() { return db.count(); }
    @JavascriptInterface public String exportJson() { return db.exportJson(); }
    @JavascriptInterface public String importJson(String text) { return result(() -> db.importJson(text)); }
    @JavascriptInterface public String autoBackups() { return db.autoBackups().toString(); }
    @JavascriptInterface public String restoreAuto(String name) { return result(() -> db.restoreAuto(name)); }
    @JavascriptInterface public void backupNow() { db.autoBackup(); }
    @JavascriptInterface public boolean pickRestore() {
        if (picker == null) return false;
        picker.pickRestoreFile();
        return true;
    }

    private interface Job { int run() throws Exception; }
    private static String result(Job j) {
        try { return "{\"added\":" + j.run() + "}"; }
        catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }
}
