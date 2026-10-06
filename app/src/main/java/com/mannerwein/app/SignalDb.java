package com.mannerwein.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Permanent scanner history on the phone (SQLite). One shared instance is used by the app screen and the
 * background scanner, so they never overwrite each other's records.
 *
 * signals : one row per Ready signal. The trade plan (entry, SL, TP, snapshot) is written once and never changed.
 *           Only the outcome columns (MUTABLE) can be updated afterwards.
 * runs    : one row per scan (manual or background).
 *
 * Nothing is ever deleted automatically. Rows can be archived (hidden) or deleted only by the user.
 */
final class SignalDb extends SQLiteOpenHelper {
    private static final String NAME = "mannerwein.db";
    private static final int VERSION = 2;   // 2: shadow stop variants (v4.1)
    private static SignalDb inst;

    /** Plan columns, written once at insert. */
    private static final String[] PLAN = {
        "uid", "t", "ver", "src", "ex", "sym", "base", "dir", "type", "tf", "grade", "score",
        "entry", "sl", "tp", "mx", "exp", "rr", "lt", "level", "snap"
    };
    /** Outcome columns: the only ones sigUpdate may change. */
    private static final Set<String> MUTABLE = new HashSet<>(Arrays.asList(
        "status", "fillT", "endT", "exitPx", "R", "Rcons", "Ropt", "mfeR", "maeR", "mfePx", "maePx",
        "lastPx", "chkT", "resTf", "slType", "postUntil", "tpAfterSL", "postMfeR", "note", "archived", "shadow", "shadowOpen"
    ));
    private static final Set<String> TEXT_COLS = new HashSet<>(Arrays.asList(
        "uid", "ver", "src", "ex", "sym", "base", "type", "tf", "grade", "lt", "snap",
        "status", "resTf", "slType", "note", "shadow"
    ));

    static synchronized SignalDb get(Context c) {
        if (inst == null) inst = new SignalDb(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    private SignalDb(Context c) { super(c, NAME, null, VERSION); ctx = c; }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE signals (" +
            "uid TEXT PRIMARY KEY, t INTEGER, ver TEXT, src TEXT, ex TEXT, sym TEXT, base TEXT, dir INTEGER, type TEXT, tf TEXT," +
            "grade TEXT, score REAL, entry REAL, sl REAL, tp REAL, mx REAL, exp INTEGER, rr REAL, lt TEXT, level REAL, snap TEXT," +
            "status TEXT DEFAULT 'pending', fillT INTEGER, endT INTEGER, exitPx REAL, R REAL, Rcons REAL, Ropt REAL," +
            "mfeR REAL, maeR REAL, mfePx REAL, maePx REAL, lastPx REAL, chkT INTEGER, resTf TEXT, slType TEXT," +
            "postUntil INTEGER, tpAfterSL INTEGER, postMfeR REAL, note TEXT, archived INTEGER DEFAULT 0," +
            "shadow TEXT, shadowOpen INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX sig_t ON signals(t)");
        db.execSQL("CREATE INDEX sig_status ON signals(status)");
        db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, startT INTEGER, endT INTEGER, ver TEXT, src TEXT," +
            "ex TEXT, requested INTEGER, completed INTEGER, failed INTEGER, ready INTEGER, watching INTEGER, logged INTEGER, status TEXT)");
        db.execSQL("CREATE INDEX run_t ON runs(startT)");
    }

    /** Future schema changes must only ADD columns/tables. Never drop data here. */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE signals ADD COLUMN shadow TEXT");
            db.execSQL("ALTER TABLE signals ADD COLUMN shadowOpen INTEGER DEFAULT 0");
        }
    }

    // ------------------------------------------------------------------ signals

    /** Inserts a new signal. Returns its uid, or "" if an equivalent signal was logged in the last 6 hours. */
    synchronized String insert(JSONObject s) {
        SQLiteDatabase db = getWritableDatabase();
        long t = s.optLong("t", System.currentTimeMillis());
        double entry = s.optDouble("entry", 0);
        try (Cursor c = db.rawQuery("SELECT entry FROM signals WHERE ex=? AND sym=? AND dir=? AND t>?",
                new String[]{ s.optString("ex"), s.optString("sym"), String.valueOf(s.optInt("dir")), String.valueOf(t - 6 * 3600000L) })) {
            while (c.moveToNext()) if (entry > 0 && Math.abs(c.getDouble(0) - entry) / entry < 0.005) return "";
        }
        String uid = s.optString("uid", "");
        if (uid.isEmpty()) uid = t + "-" + s.optString("ex") + "-" + s.optString("sym") + "-" + s.optInt("dir");
        ContentValues v = new ContentValues();
        for (String k : PLAN) put(v, k, s, k);
        v.put("uid", uid);
        v.put("t", t);
        v.put("status", "pending");
        v.put("chkT", t);
        v.put("archived", 0);
        put(v, "shadow", s, "shadow");
        put(v, "shadowOpen", s, "shadowOpen");
        db.insertWithOnConflict("signals", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return uid;
    }

    /** Updates outcome columns only. Plan columns in the JSON are ignored. */
    synchronized boolean update(String uid, JSONObject s) {
        ContentValues v = new ContentValues();
        Iterator<String> it = s.keys();
        while (it.hasNext()) { String k = it.next(); if (MUTABLE.contains(k)) put(v, k, s, k); }
        if (v.size() == 0) return false;
        // Never let an older check overwrite a newer one (app screen and background can both track)
        if (v.containsKey("chkT"))
            return getWritableDatabase().update("signals", v, "uid=? AND (chkT IS NULL OR chkT<=?)",
                new String[]{ uid, String.valueOf(v.getAsLong("chkT")) }) > 0;
        return getWritableDatabase().update("signals", v, "uid=?", new String[]{ uid }) > 0;
    }

    /** Signals created between from and to (ms). archived: 0 = hide archived, 1 = only archived, 2 = all. */
    synchronized JSONArray list(long from, long to, int archived) {
        String where = "t>=? AND t<?" + (archived == 0 ? " AND archived=0" : archived == 1 ? " AND archived=1" : "");
        return query("SELECT * FROM signals WHERE " + where + " ORDER BY t DESC", new String[]{ String.valueOf(from), String.valueOf(to) });
    }

    /** Signals still being tracked: waiting for entry, open, inside the 24h after-stop window, or with stop variants still open. */
    synchronized JSONArray open(long now) {
        return query("SELECT * FROM signals WHERE status IN ('pending','open') OR (status='loss' AND postUntil>?) OR shadowOpen=1 ORDER BY t",
            new String[]{ String.valueOf(now) });
    }

    synchronized int archive(long from, long to, boolean on) {
        ContentValues v = new ContentValues();
        v.put("archived", on ? 1 : 0);
        return getWritableDatabase().update("signals", v,
            "t>=? AND t<? AND status NOT IN ('pending','open')", new String[]{ String.valueOf(from), String.valueOf(to) });
    }

    synchronized int deleteRange(long from, long to) {
        return getWritableDatabase().delete("signals", "t>=? AND t<?", new String[]{ String.valueOf(from), String.valueOf(to) });
    }

    synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM signals", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    // ------------------------------------------------------------------ runs

    synchronized void logRun(JSONObject r) {
        ContentValues v = new ContentValues();
        for (String k : new String[]{ "startT", "endT", "ver", "src", "ex", "requested", "completed", "failed", "ready", "watching", "logged", "status" })
            put(v, k, r, k);
        getWritableDatabase().insert("runs", null, v);
    }

    synchronized JSONArray runs(int limit) {
        return query("SELECT * FROM runs ORDER BY startT DESC LIMIT " + Math.max(1, Math.min(500, limit)), null);
    }

    // ------------------------------------------------------------------ backup

    synchronized String exportJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "MannerWein");
            o.put("kind", "signals-backup");
            o.put("schema", VERSION);
            o.put("exported", System.currentTimeMillis());
            o.put("signals", query("SELECT * FROM signals ORDER BY t", null));
            o.put("runs", query("SELECT * FROM runs ORDER BY startT", null));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Merges a backup. Existing signals (same uid) are kept as they are. Returns the number of signals added. */
    synchronized int importJson(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        if (!"signals-backup".equals(o.optString("kind"))) throw new Exception("Not a Manner Wein history backup");
        JSONArray a = o.optJSONArray("signals");
        int added = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                if (s.optString("uid").isEmpty()) continue;
                ContentValues v = new ContentValues();
                for (String k : PLAN) put(v, k, s, k);
                for (String k : MUTABLE) put(v, k, s, k);
                if (db.insertWithOnConflict("signals", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
            }
            JSONArray r = o.optJSONArray("runs");
            if (r != null) for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.getJSONObject(i);
                try (Cursor c = db.rawQuery("SELECT 1 FROM runs WHERE startT=? AND src=?",
                        new String[]{ String.valueOf(x.optLong("startT")), x.optString("src") })) { if (c.moveToFirst()) continue; }
                ContentValues v = new ContentValues();
                for (String k : new String[]{ "startT", "endT", "ver", "src", "ex", "requested", "completed", "failed", "ready", "watching", "logged", "status" })
                    put(v, k, x, k);
                db.insert("runs", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return added;
    }

    private File backupDir() {
        File d = new File(ctx.getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** One automatic backup per day inside the app, last 14 kept. Survives app updates. */
    synchronized void autoBackup() {
        try {
            if (count() == 0) return;
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            File f = new File(backupDir(), "history-" + day + ".json");
            try (FileOutputStream out = new FileOutputStream(f, false)) { out.write(exportJson().getBytes("UTF-8")); }
            File[] all = backupDir().listFiles();
            if (all != null && all.length > 14) {
                Arrays.sort(all, (x, y) -> x.getName().compareTo(y.getName()));
                for (int i = 0; i < all.length - 14; i++) all[i].delete();
            }
        } catch (Exception ignored) { }
    }

    synchronized JSONArray autoBackups() {
        JSONArray a = new JSONArray();
        File[] all = backupDir().listFiles();
        if (all == null) return a;
        Arrays.sort(all, (x, y) -> y.getName().compareTo(x.getName()));
        for (File f : all) {
            try { JSONObject o = new JSONObject(); o.put("name", f.getName()); o.put("size", f.length()); o.put("time", f.lastModified()); a.put(o); }
            catch (Exception ignored) { }
        }
        return a;
    }

    synchronized int restoreAuto(String name) throws Exception {
        if (name.contains("/") || name.contains("..")) throw new Exception("Bad name");
        File f = new File(backupDir(), name);
        byte[] b = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return importJson(new String(b, "UTF-8"));
    }

    // ------------------------------------------------------------------ helpers

    private static void put(ContentValues v, String col, JSONObject s, String key) {
        if (!s.has(key) || s.isNull(key)) return;
        Object o = s.opt(key);
        if (TEXT_COLS.contains(col)) { v.put(col, o instanceof String ? (String) o : String.valueOf(o)); return; }
        if (o instanceof Boolean) { v.put(col, ((Boolean) o) ? 1 : 0); return; }
        if (o instanceof Number) {
            Number n = (Number) o;
            if (o instanceof Integer || o instanceof Long) v.put(col, n.longValue()); else v.put(col, n.doubleValue());
            return;
        }
        try { v.put(col, Double.parseDouble(String.valueOf(o))); } catch (Exception ignored) { }
    }

    private JSONArray query(String sql, String[] args) {
        JSONArray a = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                for (int i = 0; i < cols.length; i++) {
                    try {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: o.put(cols[i], c.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: o.put(cols[i], c.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: o.put(cols[i], c.getString(i)); break;
                            default: break;   // NULL: left out
                        }
                    } catch (Exception ignored) { }
                }
                a.put(o);
            }
        }
        return a;
    }
}
