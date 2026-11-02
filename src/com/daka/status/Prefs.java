package com.daka.status;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("daka", Context.MODE_PRIVATE);
    }

    public static String startTime(Context c) { return get(c).getString("startTime", "08:30"); }
    public static String endTime(Context c) { return get(c).getString("endTime", "18:00"); }
    public static int leadMin(Context c) { return get(c).getInt("leadMin", 60); }
    public static boolean useRoot(Context c) { return get(c).getBoolean("useRoot", false); }
    public static synchronized void setReadMode(Context c, boolean root) {
        boolean changed = useRoot(c) != root;
        SharedPreferences.Editor e = get(c).edit().putBoolean("useRoot", root);
        if (changed) e.remove("s_raw").remove("s_note");
        e.apply();
    }
    public static boolean autoBg(Context c) { return get(c).getBoolean("autoBg", true); }
    public static boolean backAfter(Context c) { return get(c).getBoolean("backAfter", true); }
    public static boolean remindOn(Context c) { return get(c).getBoolean("remindOn", true); }
    public static int tickMin(Context c) { return get(c).getInt("tickMin", 3); }

    public static void put(Context c, String k, String v) { get(c).edit().putString(k, v).apply(); }
    public static void putB(Context c, String k, boolean v) { get(c).edit().putBoolean(k, v).apply(); }
    public static void putI(Context c, String k, int v) { get(c).edit().putInt(k, v).apply(); }
}
