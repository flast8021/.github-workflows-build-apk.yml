package com.mannerwein.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Background scanner settings and last status, shared by the app screen and the service. */
final class Prefs {
    private Prefs() {}
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("mw_bg", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return p(c).getBoolean("on", false); }
    static String ex(Context c) { return p(c).getString("ex", "mexc"); }
    static long last(Context c) { return p(c).getLong("last", 0); }
    static long next(Context c) { return p(c).getLong("next", 0); }
    static String msg(Context c) { return p(c).getString("msg", ""); }

    static void set(Context c, boolean on, String ex) {
        if (!"mexc".equals(ex) && !"binance".equals(ex) && !"bybit".equals(ex)) ex = "mexc";
        p(c).edit().putBoolean("on", on).putString("ex", ex).apply();
    }
    static void setOn(Context c, boolean on) { p(c).edit().putBoolean("on", on).apply(); }
    static void status(Context c, String msg) { p(c).edit().putString("msg", msg).apply(); }
    static void ran(Context c, long when, String msg) { p(c).edit().putLong("last", when).putString("msg", msg).apply(); }
    static void next(Context c, long when) { p(c).edit().putLong("next", when).apply(); }
}
