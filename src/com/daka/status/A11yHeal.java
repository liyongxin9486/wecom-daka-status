package com.daka.status;

import android.content.Context;

// Android keeps a crashed accessibility service in a "crashed" set and never
// re-binds it until the enabled-services setting changes. OEM ROMs that kill
// background processes therefore leave this app without its silent-read
// channel (and without the keep-alive binding) until the user toggles the
// service by hand. Re-writing the setting (remove our component, then restore
// the original list) forces AccessibilityManagerService to bind it again.
// Needs root; silently does nothing when root is unavailable.
public class A11yHeal {
    private static final String OUR_COMPONENT =
            "com.daka.status/com.daka.status.DakaAccessibilityService";
    private static volatile long lastTry = 0L;

    // Returns true when the service is connected. When it is not, requests a
    // healing rebind (throttled) and waits up to waitMs for it to come up.
    public static boolean ensure(Context ctx, long waitMs) {
        if (DakaAccessibilityService.inst != null) return true;
        if (!Prefs.useRoot(ctx)) return false;
        final Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        boolean tried = false;
        if (now - lastTry > 5 * 60 * 1000L) {
            lastTry = now;
            tried = true;
            new Thread(new Runnable() {
                public void run() { heal(app); }
            }).start();
        }
        long budget = waitMs <= 0 ? 0 : waitMs + (tried ? 2500L : 0L);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < budget) {
            if (DakaAccessibilityService.inst != null) return true;
        if (!Prefs.useRoot(ctx)) return false;
            CheckinReader.sleep(150);
        }
        return DakaAccessibilityService.inst != null;
    }

    private static void heal(Context ctx) {
        try {
            if (!Prefs.useRoot(ctx) || !CheckinReader.isRootAvailable()) return;
            CheckinReader.ER r = CheckinReader.su(
                    "settings get secure enabled_accessibility_services", 8000);
            String list = (r == null || r.out == null) ? "" : r.out.trim();
            if (list.isEmpty() || !list.contains("com.daka.status/")) return;
            StringBuilder without = new StringBuilder();
            for (String p : list.split(":")) {
                String c = p.trim();
                if (c.isEmpty() || c.contains("com.daka.status/")) continue;
                if (without.length() > 0) without.append(':');
                without.append(c);
            }
            // Never wipe the whole list (that would disable every service).
            // A sole enabled service can be toggled using an empty value too.
            CheckinReader.su("settings put secure enabled_accessibility_services "
                    + "\"" + without + "\"", 8000);
            CheckinReader.sleep(700);
            CheckinReader.su("settings put secure enabled_accessibility_services "
                    + list, 8000);
            android.util.Log.i(VdHelper.TAG, "a11y heal: rebind requested");
            Prefs.get(ctx).edit().putLong("a11yHealAt",
                    System.currentTimeMillis()).apply();
        } catch (Throwable t) { }
    }
}
