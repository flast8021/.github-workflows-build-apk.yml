package com.mannerwein.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.webkit.JavascriptInterface;

/** Phone-local SQLite store for the complete trade log JSON. Rows remain until the user deletes them. */
final class TradeDbBridge extends SQLiteOpenHelper {
    TradeDbBridge(Context context) { super(context, "manner_wein_trades.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_state (id INTEGER PRIMARY KEY CHECK(id=1), trade_log TEXT NOT NULL)");
        db.execSQL("INSERT INTO app_state(id,trade_log) VALUES(1,'[]')");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    @JavascriptInterface public synchronized String getTradeLog() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT trade_log FROM app_state WHERE id=1", null)) {
            return c.moveToFirst() ? c.getString(0) : "[]";
        } catch (Exception e) { return "[]"; }
    }
    @JavascriptInterface public synchronized boolean setTradeLog(String json) {
        if (json == null || json.length() > 10_000_000) return false;
        try {
            getWritableDatabase().execSQL("UPDATE app_state SET trade_log=? WHERE id=1", new Object[]{json});
            return true;
        } catch (Exception e) { return false; }
    }
}
