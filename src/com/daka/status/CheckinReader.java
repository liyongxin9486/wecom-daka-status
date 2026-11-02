package com.daka.status;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckinReader {

    public static final String WEWORK = "com.tencent.wework";
    public static final String CHECKIN_ACT = "com.tencent.wework.enterprise.attendance.controller.AttendanceActivity2";
    public static final String WEWORK_MAIN = "com.tencent.wework.launch.WwMainActivity";

    public static class Res {
        public boolean ok;
        public boolean skipped;
        public String err = "";
        public Snapshot snap;
    }

    public static class ER {
        public volatile int code = -1;
        public volatile String out = "";
    }

    public static ER exec(String[] cmd, long timeoutMs) {
        final ER r = new ER();
        // The process handle is published through an AtomicReference so the
        // timeout path reliably sees it once the worker has started it; the
        // timedOut flag covers the tiny window where the deadline expires
        // before pb.start() returned (such a process kills itself).
        final AtomicReference<Process> holder = new AtomicReference<Process>();
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    holder.set(p);
                    if (timedOut.get()) {
                        // The caller already gave up while this process was
                        // still being created: kill it right away.
                        try { p.destroyForcibly(); } catch (Throwable e2) { }
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (InputStream in = p.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    p.waitFor();
                    r.code = p.exitValue();
                    r.out = new String(bos.toByteArray(), "UTF-8");
                } catch (Throwable e) {
                    r.out = "ERR " + e;
                    r.code = -3;
                }
            }
        });
        t.start();
        try { t.join(timeoutMs); } catch (InterruptedException e) { }
        if (t.isAlive()) {
            timedOut.set(true);
            // Kill only THIS call's process: a global kill list would also take
            // down concurrent su calls of other threads.
            Process p = holder.get();
            if (p != null) {
                // Java 8 APIs only here (android.jar has no Process.descendants()):
                // kill the su process of THIS call; its children have their own
                // internal timeouts and the next attempt cleans up state.
                try { p.destroyForcibly(); } catch (Throwable e) { }
            }
            // Let the reader thread drain so it cannot overwrite r.out/r.code
            // after we return.
            try { t.join(1500); } catch (InterruptedException e) { }
            r.code = -2;
            r.out = (r.out == null ? "" : r.out) + "\nTIMEOUT";
        }
        return r;
    }

    public static ER su(String script, long timeoutMs) {
        return exec(new String[] { "su", "-c", script }, timeoutMs);
    }

    // Worker only: detect once, keeping explicit choices from previous versions.
    public static synchronized void ensureReadMode(Context c) {
        if (Prefs.get(c).contains("useRoot")) return;
        boolean root = isRootAvailable();
        if (!Prefs.get(c).contains("useRoot")) Prefs.setReadMode(c, root);
    }

    public static boolean isRootAvailable() {
        ER r = su("id", 6000);
        return r.out != null && r.out.contains("uid=0");
    }

    public static String topApp() {
        ER r = su("dumpsys activity activities | grep -e topResumedActivity -e mResumedActivity", 6000);
        Matcher m = Pattern.compile("(?:topResumedActivity|mResumedActivity)=ActivityRecord\\{[^ ]+ [^ ]+ ([^ /]+)/").matcher(r.out);
        if (m.find()) return m.group(1);
        return "";
    }

    public static String topActivity() {
        ER r = su("dumpsys activity activities | grep -e topResumedActivity -e mResumedActivity", 6000);
        Matcher m = Pattern.compile("(?:topResumedActivity|mResumedActivity)=ActivityRecord\\{[^ ]+ [^ ]+ ([^ ]+) ").matcher(r.out);
        if (m.find()) return m.group(1);
        return "";
    }

    public static boolean topAllowed(Context ctx, String top) {
        if (top == null || top.isEmpty()) return true;
        if (top.equals(ctx.getPackageName())) return true;
        if (top.equals(WEWORK)) return true;
        if (top.contains("launcher")) return true;
        if (top.contains("systemui")) return true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null && top.equals(ri.activityInfo.packageName)) return true;
        } catch (Throwable e) { }
        return false;
    }

    // Tap the node whose text equals the target on the given display, waiting
    // (and retrying) while the page is still rendering. Returns false when the
    // label never showed up.
    public static boolean tapVd(int vd, String text, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            DakaAccessibilityService svc = DakaAccessibilityService.inst;
            if (svc != null) {
                String xy = svc.findTextCenter(vd, text);
                if (xy != null) {
                    su("timeout 6 input -d " + vd + " tap " + xy, 9000);
                    return true;
                }
            }
            sleep(400);
        }
        return false;
    }

    public static Res refresh(Context ctx, boolean interactive) {
        Res res = new Res();
        res.snap = Snapshot.load(ctx);
        // Rest days (weekends / statutory holidays): never read the punch page
        // (the page still shows the previous day's card after 02:00). Pin the
        // snapshot to the rest state instead so every surface keeps showing
        // the rest label for the whole day.
        if (HolidayHelper.isRestDay(ctx)) {
            res.snap.morning = StatusParser.NO_NEED;
            res.snap.evening = StatusParser.NO_NEED;
            res.snap.morningTime = "";
            res.snap.eveningTime = "";
            res.snap.note = HolidayHelper.getRestDayLabel(ctx);
            res.snap.err = "";
            res.snap.updatedAt = System.currentTimeMillis();
            res.snap.save(ctx);
            res.ok = true;
            android.util.Log.i(VdHelper.TAG, "rest day: skip page read, pin rest state");
            return res;
        }
        CheckinReader.ensureReadMode(ctx);
        boolean useRoot = Prefs.useRoot(ctx);
        boolean rootOk = useRoot && isRootAvailable();
        if (useRoot && !rootOk) { Prefs.setReadMode(ctx, false); useRoot = false; }
        if (rootOk) {
            Snapshot local = LocalAttendanceReader.refresh(ctx);
            if (local != null) { res.snap = local; res.ok = true; }
            else doRootVd(ctx, res);
            // Visible fallback only for user-triggered refreshes; background
            // refreshes must never pop the WeWork page onto the screen.
            if (!res.ok && interactive) {
                doRoot(ctx, res, interactive);
            }
        } else if (DakaAccessibilityService.inst != null) {
            doA11y(ctx, res, interactive);
        } else {
            res.err = (useRoot ? "root 不可用；" : "") + "无障碍服务未开启";
        }
        if (Prefs.useRoot(ctx) != useRoot) { res.skipped = true; return res; }
        if (res.skipped) return res; // e.g. screen off: leave the snapshot untouched
        if (res.ok) {
            Snapshot prev = Snapshot.load(ctx);
            if (res.snap != null && prev != null) {
                if (res.snap.morning == StatusParser.DONE && res.snap.morningTime.isEmpty() && !prev.morningTime.isEmpty()) {
                    res.snap.morningTime = prev.morningTime;
                }
                if (res.snap.evening == StatusParser.DONE && res.snap.eveningTime.isEmpty() && !prev.eveningTime.isEmpty()) {
                    res.snap.eveningTime = prev.eveningTime;
                }
            }
            res.snap.err = "";
            res.snap.updatedAt = System.currentTimeMillis();
        } else {
            res.snap.err = res.err;
        }
        res.snap.save(ctx);
        return res;
    }

    // Silent read: launch the check-in activity on our offscreen virtual
    // display (root can target a display). Nothing appears on the phone
    // screen and the screen does not need to be on. The accessibility service
    // picks up the page's window events on that display and saves a snapshot.
    private static void doRootVd(Context ctx, Res res) {
        // Reading while the device is dozing is not possible: accessibility
        // blocks when fetching nodes of a sleeping display, and injected input
        // could wake the screen. Skip quickly (silently) and read again as soon
        // as the screen is on.
        final android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
        boolean interactive = true;
        try { interactive = pm == null || pm.isInteractive(); } catch (Throwable t) { }
        if (!interactive) {
            res.skipped = true;
            android.util.Log.i(VdHelper.TAG, "vd read skip: screen off");
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            res.skipped = true;
            android.util.Log.i(VdHelper.TAG, "vd read skip: locked; wait for unlock");
            return;
        }
        int vdId = VdHelper.ensure(ctx);
        if (vdId <= 0) {
            res.err = "静默屏不可用";
            return;
        }
        long base = Prefs.get(ctx).getLong("s_up", 0L);
        ER r1 = su("timeout 10 am start --display " + vdId + " -n " + WEWORK + "/" + CHECKIN_ACT, 14000);
        if (r1.out != null && (r1.out.contains("Error") || r1.out.contains("Exception"))) {
            res.err = "静默打开打卡页失败: " + trim(r1.out, 90);
            return;
        }
        android.util.Log.i(VdHelper.TAG, "vd read start base=" + base);

        long t0 = System.currentTimeMillis();
        int poke = 0;
        int pullTexts = -1;
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        while (System.currentTimeMillis() - t0 < 24000) {
            sleep(700);
            Snapshot s = Snapshot.load(ctx);
            if (s.updatedAt > base) {
                res.snap = s;
                res.ok = true;
                android.util.Log.i(VdHelper.TAG, "vd read ok after " + (System.currentTimeMillis() - t0) + "ms");
                break;
            }
            // Pull-based read: fetch the node tree of the virtual display
            // directly. Unlike window events (never delivered for offscreen
            // displays) it needs no active window - but the screen must still
            // be interactive (checked above).
            if (svc == null) svc = DakaAccessibilityService.inst;
            boolean nowInteractive = true;
            try { nowInteractive = pm == null || pm.isInteractive(); } catch (Throwable t) { }
            if (nowInteractive && svc != null) {
                try {
                    java.util.List<String> texts = svc.textsOnDisplay(vdId, false);
                    if (texts != null && !texts.isEmpty()) {
                        pullTexts = texts.size();
                        Snapshot p = StatusParser.parse(texts, true);
                        if (p.pageReady) {
                            p.err = "";
                            res.snap = p;
                            res.ok = true;
                            android.util.Log.i(VdHelper.TAG, "vd pull ok texts=" + texts.size()
                                    + " after " + (System.currentTimeMillis() - t0) + "ms");
                            break;
                        }
                    }
                } catch (Throwable t) { }
            }
            long el = System.currentTimeMillis() - t0;
            // Only nudge the page while the screen is on: injected swipes on a
            // dozing device can wake the display, which must never happen.
            if (nowInteractive && el > 7000 && poke == 0) {
                poke = 1;
                // Nudge the page so the accessibility tree emits fresh events.
                su("timeout 6 input -d " + vdId + " swipe 90 1050 90 800 200", 9000);
                android.util.Log.i(VdHelper.TAG, "vd poke 1");
            } else if (nowInteractive && el > 14000 && poke == 1) {
                poke = 2;
                su("timeout 6 input -d " + vdId + " swipe 90 800 90 1050 200", 9000);
                android.util.Log.i(VdHelper.TAG, "vd poke 2");
            }
        }
        if (!res.ok) {
            res.err = "静默读取超时（页面未就绪）";
            android.util.Log.i(VdHelper.TAG, "vd read timeout pullTexts=" + pullTexts);
        }
        // Close the page - only while the screen is on; injected keys are not
        // sent to a dozing display.
        boolean stillInteractive = true;
        try { stillInteractive = pm == null || pm.isInteractive(); } catch (Throwable t) { }
        if (stillInteractive) {
            su("timeout 6 input -d " + vdId + " keyevent 4", 9000);
        }
        sleep(200);
    }

    private static void doRoot(Context ctx, Res res, boolean interactive) {
        String top = topApp();
        if (!interactive && !topAllowed(ctx, top)) {
            res.skipped = true;
            res.err = "skip:busy(" + top + ")";
            return;
        }
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            res.err = "屏幕已锁定，解锁后会自动刷新";
            return;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            su("input keyevent 224", 5000);
            sleep(900);
            if (!pm.isInteractive()) { res.err = "屏幕未点亮"; return; }
        }

        File f = new File("/data/local/tmp/dk_status_ui.xml");
        try { if (f.exists()) f.delete(); } catch (Throwable e) { }

        ER r1 = su("am start -n " + WEWORK + "/" + CHECKIN_ACT, 10000);
        if (r1.out != null && r1.out.contains("Error")) {
            res.err = "无法打开打卡页: " + trim(r1.out, 100);
            return;
        }

        // Wait until the check-in activity is really on top (cold start can take seconds).
        for (int i = 0; i < 12; i++) {
            sleep(700);
            if (topActivity().contains("AttendanceActivity2")) break;
        }
        sleep(800);

        Snapshot parsed = null;
        Snapshot lastTry = null;
        final String tmp = f.getAbsolutePath();
        for (int i = 0; i < 3; i++) {
            // Dump as root into /data/local/tmp and read it back through the su pipe:
            // the app cannot read files created by root in its own external dir
            // (the file lands as root:ext_data_rw and the app is not in that group).
            ER rd = su("rm -f " + tmp + "; uiautomator dump " + tmp + " >/dev/null 2>&1; cat " + tmp, 24000);
            String xml = extractXml(rd.out);
            if (xml != null && xml.length() > 200) {
                Snapshot p = StatusParser.parse(extractTexts(xml), false);
                lastTry = p;
                if (p.pageReady) { parsed = p; break; }
            }
            sleep(1400);
        }
        su("rm -f " + tmp, 6000);

        if (Prefs.backAfter(ctx)) {
            for (int i = 0; i < 3; i++) {
                su("input keyevent 4", 5000);
                sleep(420);
                String t2 = topApp();
                if (t2.isEmpty() || !t2.equals(WEWORK)) break;
            }
        }

        if (parsed == null) {
            res.err = "未识别到打卡页内容（页面未加载或超时）";
            if (lastTry != null && lastTry.raw != null && !lastTry.raw.isEmpty()) {
                res.snap.raw = lastTry.raw + " [页面未就绪]";
            }
            return;
        }
        res.snap = parsed;
        res.ok = true;
    }

    private static void doA11y(Context ctx, Res res, boolean interactive) {
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        if (svc == null) { res.err = "无障碍服务未连接"; return; }
        if (!interactive) {
            // The a11y flow is visible - it launches WeWork on the main
            // display. Unattended auto-refreshes must not run while the screen
            // is off (node fetches block on a sleeping display) or while the
            // device is locked (the page would linger behind the keyguard).
            // The unlock receiver reads again right after the user returns.
            boolean screenOn = true;
            boolean locked = false;
            try {
                android.os.PowerManager pm = (android.os.PowerManager)
                        ctx.getSystemService(Context.POWER_SERVICE);
                screenOn = pm == null || pm.isInteractive();
            } catch (Throwable t) { }
            try {
                android.app.KeyguardManager km = (android.app.KeyguardManager)
                        ctx.getSystemService(Context.KEYGUARD_SERVICE);
                locked = km != null && km.isKeyguardLocked();
            } catch (Throwable t) { }
            if (!screenOn || locked) { res.skipped = true; return; }
        }
        String top = svc.currentForegroundPkg();
        if (!interactive && !topAllowed(ctx, top)) {
            res.skipped = true;
            res.err = "skip:busy";
            return;
        }
        long base = Prefs.get(ctx).getLong("s_up", 0L);
        boolean ok = svc.runRefreshFlow(ctx, base, 16000);
        if (!ok) { res.err = "无障碍流程未完成（页面未识别）"; return; }
        Snapshot s = Snapshot.load(ctx);
        if (s.updatedAt > base) { res.snap = s; res.ok = true; }
        else res.err = "未能读取打卡页";
    }

    public static String extractXml(String s) {
        if (s == null) return null;
        int i = s.indexOf("<?xml");
        if (i < 0) return null;
        return s.substring(i);
    }

    public static List<String> extractTexts(String xml) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("(?:text|content-desc)=\"([^\"]*)\"").matcher(xml);
        while (m.find()) {
            String v = m.group(1);
            if (v != null && !v.trim().isEmpty()) out.add(unescape(v));
        }
        return out;
    }

    public static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'");
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }

    static String trim(String s, int n) {
        s = s == null ? "" : s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) : s;
    }
}
