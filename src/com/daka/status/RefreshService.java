package com.daka.status;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RefreshService extends Service {
    public static final String ACTION_REFRESH = "com.daka.status.action.REFRESH";
    public static final String ACTION_OPEN = "com.daka.status.action.OPEN";
    public static final String ACTION_REMIND = "com.daka.status.action.REMIND";
    public static final String ACTION_SNOOZE = "com.daka.status.action.SNOOZE";
    public static final String ACTION_ALERTTEST = "com.daka.status.action.ALERTTEST";
    public static final String ACTION_TIMINGTEST = "com.daka.status.action.TIMINGTEST";
    public static final String ACTION_RINGTEST = "com.daka.status.action.RINGTEST";
    public static final String ACTION_VDTEST = "com.daka.status.action.VDTEST";
    // Debug helpers used to inspect/act on the virtual display (root trigger).
    public static final String ACTION_VDTEXTS = "com.daka.status.action.VDTEXTS";
    public static final String ACTION_VDCLICK = "com.daka.status.action.VDCLICK";
    public static final String ACTION_VDUMP = "com.daka.status.action.VDUMP";
    // Opens WeCom's worklog ("汇报") page on the phone screen. The report
    // status cannot be read from the virtual display: WeCom's worklog is a
    // single-task Flutter page that always jumps back to the primary display,
    // so the app offers a one-tap shortcut instead of a silent read.
    public static final String ACTION_REPORT = "com.daka.status.action.REPORT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SHIFT = "shift";

    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile int latestStartId;
    public static volatile boolean refreshing;

    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    public static void start(Context c, String action, String mode, String shift) {
        Intent i = new Intent(c, RefreshService.class);
        i.setAction(action);
        if (mode != null) i.putExtra(EXTRA_MODE, mode);
        if (shift != null) i.putExtra(EXTRA_SHIFT, shift);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            // Plain startService as fallback (allowed while the app still has
            // a foreground/visible context).
            boolean started = false;
            try { c.startService(i); started = true; } catch (Throwable t2) { }
            if (started) return;
            // Both API paths were blocked by the background-start restrictions.
            // Deliver the reminder directly right now (marking the dedupe key
            // first so the service cannot alert a second time if it still comes
            // up), then launch the service through the root shell, which is
            // immune to those restrictions on this device.
            if (ACTION_REMIND.equals(action) || ACTION_SNOOZE.equals(action)) {
                Notifier.deliverScheduled(c, shift, ACTION_SNOOZE.equals(action));
                return;
            }
            if (!Prefs.useRoot(c)) { Log.w(VdHelper.TAG, "service start blocked in normal mode", t); return; }
            final String fAction = action;
            final String fMode = mode;
            final String fShift = shift;
            final String cmd = "am start-foreground-service -n com.daka.status/.RefreshService -a "
                    + fAction
                    + (fMode == null ? "" : " --es mode " + fMode)
                    + (fShift == null ? "" : " --es shift " + fShift);
            new Thread(new Runnable() {
                public void run() {
                    try { CheckinReader.su(cmd, 15000); } catch (Throwable t) { }
                }
            }).start();
        }
    }

    public int onStartCommand(Intent intent, int flags, final int startId) {
        latestStartId = startId;
        final String action = (intent == null || intent.getAction() == null) ? ACTION_REFRESH : intent.getAction();
        final String mode = intent == null ? "manual"
                : (intent.getStringExtra(EXTRA_MODE) == null ? "manual" : intent.getStringExtra(EXTRA_MODE));
        final String shift = intent == null ? null : intent.getStringExtra(EXTRA_SHIFT);
        final String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);

        try {
            Notification n = Notifier.runningNotification(this, "正在刷新打卡状态…");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(101, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(101, n);
            }
        } catch (Throwable t) { }

        if (ACTION_REMIND.equals(action) || ACTION_SNOOZE.equals(action) || ACTION_ALERTTEST.equals(action)) {
            if (ACTION_ALERTTEST.equals(action)) {
                Notifier.remind(this, shift == null ? "morning" : shift, Snapshot.load(this));
            } else Notifier.deliverScheduled(this, shift, ACTION_SNOOZE.equals(action));
            if (pending.get() == 0) { stopFg(); stopSelfResult(startId); }
            return START_NOT_STICKY;
        }
        final boolean read = ACTION_REFRESH.equals(action);
        if (read && !refreshQueued.compareAndSet(false, true)) return START_NOT_STICKY;
        pending.incrementAndGet();
        worker.execute(new Runnable() {
            public void run() {
                if (read) { refreshQueued.set(false); refreshing = true; }
                try {
                    CheckinReader.ensureReadMode(RefreshService.this);
                    if (!Prefs.useRoot(RefreshService.this) && (ACTION_VDTEST.equals(action)
                            || ACTION_VDTEXTS.equals(action) || ACTION_VDCLICK.equals(action)
                            || ACTION_VDUMP.equals(action))) return;
                    // The silent read needs the accessibility service. OEM
                    // killers can leave it in the system's "crashed" set, so
                    // request a healing rebind before doing anything else.
                    if (DakaAccessibilityService.inst == null) {
                        A11yHeal.ensure(RefreshService.this, 2000);
                    }
                    if (ACTION_TIMINGTEST.equals(action) || ACTION_RINGTEST.equals(action)) {
                        long at = System.currentTimeMillis() + 15000L;
                        Intent test = AlarmScheduler.intentFor(RefreshService.this, action);
                        test.putExtra(EXTRA_SHIFT, "evening".equals(shift) ? "evening" : "morning");
                        test.putExtra("scheduledAt", at);
                        AlarmScheduler.setAlarmClock(RefreshService.this, 199, at, test);
                        Log.i(VdHelper.TAG, "timing test scheduledAt=" + at);
                    } else if (ACTION_OPEN.equals(action)) {
                        openCheckin(RefreshService.this);
                    } else if (ACTION_VDTEST.equals(action)) {
                        probeVd();
                    } else if (ACTION_VDTEXTS.equals(action)) {
                        probeVdTexts(text == null ? "page" : text);
                    } else if (ACTION_VDCLICK.equals(action)) {
                        probeVdClick(text);
                    } else if (ACTION_VDUMP.equals(action)) {
                        int id = VdHelper.ensure(RefreshService.this);
                        dumpVdTexts(id, text == null ? "dump" : text);
                    } else if (ACTION_REPORT.equals(action)) {
                        openReport();
                    } else {
                        boolean interactive = "manual".equals(mode);
                        CheckinReader.refresh(RefreshService.this, interactive);
                        DakaWidgetProvider.updateAll(RefreshService.this);
                        Notifier.checkAndRemind(RefreshService.this);
                    }
                } catch (Throwable t) {
                    Log.e(VdHelper.TAG, "refresh failed", t);
                } finally {
                    if (read) refreshing = false;
                    if (pending.decrementAndGet() == 0) {
                        final int completedId = latestStartId;
                        new android.os.Handler(getMainLooper()).post(new Runnable() {
                            public void run() {
                                if (pending.get() == 0 && latestStartId == completedId) {
                                    stopFg();
                                    stopSelfResult(completedId);
                                }
                            }
                        });
                    }
                }
            }
        });

        return START_NOT_STICKY;
    }

    static void openCheckin(Context ctx) {
        if (Prefs.useRoot(ctx) && CheckinReader.isRootAvailable()) {
            CheckinReader.su("am start -n " + CheckinReader.WEWORK + "/" + CheckinReader.CHECKIN_ACT, 10000);
            return;
        }
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        if (svc != null) {
            svc.beginNavigate(ctx);
            return;
        }
        try {
            Intent i = new Intent();
            i.setClassName(CheckinReader.WEWORK, CheckinReader.WEWORK_MAIN);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) { }
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopFg() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable t) { }
    }

    // One-tap shortcut: bring WeCom's worklog page to the front. Falls back to
    // the WeCom home page if the worklog activity cannot be started.
    private void openReport() {
        if (!Prefs.useRoot(this)) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(CheckinReader.WEWORK);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
            } catch (Throwable t) { }
            return;
        }
        try {
            CheckinReader.su("timeout 10 am start -n " + CheckinReader.WEWORK
                    + "/com.tencent.wework.enterprise.worklog.controller.LogTabActivity", 14000);
            CheckinReader.sleep(1500);
            String top = CheckinReader.topApp();
            if (!CheckinReader.WEWORK.equals(top)) {
                CheckinReader.su("timeout 10 am start -n " + CheckinReader.WEWORK
                        + "/" + CheckinReader.WEWORK_MAIN, 14000);
            }
        } catch (Throwable t) { }
    }

    // ---- Virtual-display text probe / click (debug) ----
    private void probeVdTexts(String tag) {
        int id = VdHelper.ensure(this);
        Log.i(VdHelper.TAG, "vdtexts vd=" + id);
        if (id <= 0) return;
        CheckinReader.su("timeout 10 am start --display " + id + " -n "
                + CheckinReader.WEWORK + "/" + CheckinReader.WEWORK_MAIN, 14000);
        CheckinReader.sleep(3500);
        dumpVdTexts(id, tag);
    }

    private void probeVdClick(String target) {
        int id = VdHelper.ensure(this);
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        if (id <= 0 || target == null || svc == null) {
            Log.i(VdHelper.TAG, "vdclick vd=" + id + " svc=" + (svc != null)
                    + " target=" + target);
            return;
        }
        boolean ok = CheckinReader.tapVd(id, target, 8000);
        Log.i(VdHelper.TAG, "vdclick '" + target + "' ok=" + ok);
        if (!ok) return;
        CheckinReader.sleep(3000);
        dumpVdTexts(id, "after-" + target);
    }

    private void dumpVdTexts(int id, String tag) {
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        if (svc == null) {
            Log.i(VdHelper.TAG, "vdtexts[" + tag + "] no a11y service");
            return;
        }
        List<String> texts = svc.textsOnDisplay(id, true);
        Log.i(VdHelper.TAG, "vdtexts[" + tag + "] n=" + texts.size());
        StringBuilder sb = new StringBuilder();
        for (String s : texts) {
            if (s == null) continue;
            sb.append('|').append(s.replace('\n', ' ').replace('\r', ' '));
            if (sb.length() >= 700) {
                Log.i(VdHelper.TAG, "vdtexts[" + tag + "] " + sb);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) Log.i(VdHelper.TAG, "vdtexts[" + tag + "] " + sb);
    }

    // ---- Virtual-display feasibility probe (debug) ----
    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 220 ? s.substring(0, 220) : s;
    }

    private void probeVd() {
        Log.i(VdHelper.TAG, "probe start");
        VdHelper.debugEvents = true;
        int id = VdHelper.ensure(this);
        Log.i(VdHelper.TAG, "vd id=" + id + " " + VdHelper.w + "x" + VdHelper.hgt + "@" + VdHelper.dpi);
        if (id <= 0) {
            Log.i(VdHelper.TAG, "VD CREATE FAILED");
            return;
        }
        CheckinReader.ER r = CheckinReader.su("am start --display " + id + " -n "
                + CheckinReader.WEWORK + "/" + CheckinReader.CHECKIN_ACT, 15000);
        Log.i(VdHelper.TAG, "am start rc=" + r.code + " out=" + oneLine(r.out));
        CheckinReader.sleep(1500);
        CheckinReader.ER rd = CheckinReader.su("dumpsys activity activities | grep AttendanceActivity2 | head -3", 8000);
        Log.i(VdHelper.TAG, "act=" + oneLine(rd.out));

        long t0 = System.currentTimeMillis();
        Snapshot parsed = null;
        DakaAccessibilityService svc = DakaAccessibilityService.inst;
        Log.i(VdHelper.TAG, "a11y svc=" + (svc != null));
        while (System.currentTimeMillis() - t0 < 25000) {
            CheckinReader.sleep(1200);
            List<String> texts = svc == null ? null : svc.textsOnDisplay(id, true);
            int n = texts == null ? -1 : texts.size();
            Snapshot p = texts == null ? null : StatusParser.parse(texts, true);
            Log.i(VdHelper.TAG, "poll+" + (System.currentTimeMillis() - t0) + " texts=" + n
                    + " ready=" + (p != null && p.pageReady));
            if (p != null && p.pageReady) {
                parsed = p;
                Log.i(VdHelper.TAG, "PARSE OK m=" + p.morning + " e=" + p.evening
                        + " mt=" + p.morningTime + " et=" + p.eveningTime
                        + " sa=" + p.schedA + " sb=" + p.schedB + " raw=" + oneLine(p.raw));
                break;
            }
        }
        if (parsed == null) Log.i(VdHelper.TAG, "PARSE FAILED (no page ready)");

        CheckinReader.ER r2 = CheckinReader.su(
                "uiautomator dump /data/local/tmp/vdp.xml >/dev/null 2>&1; grep -c id/bua /data/local/tmp/vdp.xml; wc -c /data/local/tmp/vdp.xml; rm -f /data/local/tmp/vdp.xml", 30000);
        Log.i(VdHelper.TAG, "uiautomator=" + oneLine(r2.out));

        CheckinReader.ER r3 = CheckinReader.su("input -d " + id + " keyevent 4", 8000);
        Log.i(VdHelper.TAG, "close rc=" + r3.code + " out=" + oneLine(r3.out));
        CheckinReader.sleep(1500);
        List<String> after = svc == null ? null : svc.textsOnDisplay(id, true);
        Log.i(VdHelper.TAG, "after close texts=" + (after == null ? -1 : after.size()));
        VdHelper.debugEvents = false;
        Log.i(VdHelper.TAG, "probe end");
    }
}
