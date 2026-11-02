package com.daka.status;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

public class DakaAccessibilityService extends AccessibilityService {
    public static volatile DakaAccessibilityService inst;

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean flowRunning = false;
    private volatile boolean flowOk = false;
    private volatile long lastPassive = 0L;
    private volatile long lastTry = 0L;
    private volatile int passiveTries = 0;
    private volatile long lastDatabaseHint;

    private UserPresentReceiver userPresentReceiver = null;

    protected void onServiceConnected() {
        inst = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable t) { }
        try {
            if (userPresentReceiver == null) {
                userPresentReceiver = new UserPresentReceiver();
                android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_USER_PRESENT);
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(userPresentReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                } else registerReceiver(userPresentReceiver, f);
            }
        } catch (Throwable t) { }
        h.post(screenTick);
    }

    // Screen-on clock complements alarms batched by OEM power policies.
    // Handler uses uptime: it does not wake a sleeping device.
    private long nextScreenTick;
    private final Runnable screenTick = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            long due = Prefs.get(DakaAccessibilityService.this).getLong("nextTickAt", 0L);
            if (due > 0 && due <= now && now >= nextScreenTick) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    nextScreenTick = now + 15000L;
                    AlarmScheduler.scheduleTick(DakaAccessibilityService.this);
                    DakaWidgetProvider.updateAll(DakaAccessibilityService.this);
                    if (Prefs.autoBg(DakaAccessibilityService.this)
                            && !HolidayHelper.isRestDay(DakaAccessibilityService.this)
                            && RefreshPolicy.isInRefreshWindow()) {
                        android.util.Log.i(VdHelper.TAG, "screen timer refresh");
                        RefreshService.start(DakaAccessibilityService.this, RefreshService.ACTION_REFRESH, "auto", null);
                    }
                }
            }
            h.postDelayed(this, 1000L);
        }
    };

    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (inst == this) inst = null;
        if (userPresentReceiver != null) {
            try { unregisterReceiver(userPresentReceiver); } catch (Throwable t) { }
            userPresentReceiver = null;
        }
        if (vdRoot != null) {
            try { vdRoot.recycle(); } catch (Throwable t) { }
            vdRoot = null;
        }
        super.onDestroy();
    }

    public void onInterrupt() { }

    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (LocalAttendanceReader.running) return;
        if (e == null) return;
        CharSequence p = e.getPackageName();
        if (p == null || !CheckinReader.WEWORK.contentEquals(p)) return;
        int disp = 0;
        try { disp = e.getDisplayId(); } catch (Throwable t) { }
        if (VdHelper.debugEvents) {
            try {
                android.util.Log.i(VdHelper.TAG, "evt type=" + e.getEventType() + " disp=" + disp
                        + " cls=" + e.getClassName());
            } catch (Throwable t) { }
        }
        if (flowRunning) return;
        long now = System.currentTimeMillis();
        if (disp > 0) {
            if (!Prefs.useRoot(this)) return;
            // Event from our virtual display (silent read): capture the page's
            // node tree from the event source and parse it.
            if (now - lastVdEvt < 900L) return;
            lastVdEvt = now;
            captureVdRoot(e);
            if (vdRoot != null) {
                vdTries = 0;
                vdTick();
            }
            return;
        }
        if (now - lastTry < 1200L) return;
        lastTry = now;
        passiveTries = 0;
        passiveTick();
    }

    private volatile AccessibilityNodeInfo vdRoot = null;
    private volatile long lastVdEvt = 0L;
    private volatile int vdTries = 0;
    private volatile long lastVdSave = 0L;

    private void captureVdRoot(AccessibilityEvent e) {
        try {
            AccessibilityNodeInfo src = e.getSource();
            if (VdHelper.debugEvents) {
                android.util.Log.i(VdHelper.TAG, "vd src=" + (src != null));
            }
            if (src == null) return;
            AccessibilityNodeInfo root = src;
            int wid = src.getWindowId();
            for (int i = 0; i < 40; i++) {
                AccessibilityNodeInfo pp = root.getParent();
                if (pp == null || pp.getWindowId() != wid) {
                    if (pp != null) pp.recycle();
                    break;
                }
                if (root != src) root.recycle();
                root = pp;
            }
            if (vdRoot != null && vdRoot != root) {
                try { vdRoot.recycle(); } catch (Throwable t) { }
            }
            vdRoot = root;
        } catch (Throwable t) { }
    }

    private void vdTick() {
        if (!Prefs.useRoot(this) || LocalAttendanceReader.running) return;
        AccessibilityNodeInfo root = vdRoot;
        boolean ready = false;
        if (root != null) {
            try {
                List<String> texts = new ArrayList<String>();
                walk(root, texts, 0);
                if (!texts.isEmpty()) {
                    Snapshot s = StatusParser.parse(texts, true);
                    if (VdHelper.debugEvents) {
                        android.util.Log.i(VdHelper.TAG, "vdTick texts=" + texts.size()
                                + " ready=" + s.pageReady);
                    }
                    if (s.pageReady) {
                        ready = true;
                        long t = System.currentTimeMillis();
                        if (t - lastVdSave >= 2500L && contentChanged(s)) {
                            lastVdSave = t;
                            s.updatedAt = t;
                            preservePunchTimes(s);
                            s.save(this);
                            DakaWidgetProvider.updateAll(this);
                            android.util.Log.i(VdHelper.TAG, "vdTick saved");
                        }
                    }
                }
            } catch (Throwable t) { }
        }
        if (!ready && vdTries < 20) {
            vdTries++;
            h.postDelayed(new Runnable() {
                public void run() {
                    if (!flowRunning) vdTick();
                }
            }, 1400);
        }
    }

    // Page content keeps changing while it loads; a single read right after the
    // window event is usually too early, so retry a few times until it parses.
    private void passiveTick() {
        if (LocalAttendanceReader.running) return;
        boolean ready = false;
        try {
            List<String> texts = collectTexts();
            Snapshot s = StatusParser.parse(texts, true);
            if (s.pageReady) {
                ready = true;
                long t = System.currentTimeMillis();
                if (t - lastPassive >= 15000L) {
                    lastPassive = t;
                    if (contentChanged(s)) {
                        s.updatedAt = t;
                        preservePunchTimes(s);
                        s.save(this);
                        DakaWidgetProvider.updateAll(this);
                    }
                }
            }
        } catch (Throwable t) { }
        if (!ready && passiveTries < 6) {
            passiveTries++;
            h.postDelayed(new Runnable() {
                public void run() {
                    if (!flowRunning) passiveTick();
                }
            }, 1600);
        }
    }

    // Skip writes/redraws when the parsed status is identical to what is
    // already stored - the page keeps emitting events for a while after a
    // read and the repeated saves otherwise burn CPU and disk for nothing.
    private boolean contentChanged(Snapshot s) {
        try {
            Snapshot cur = Snapshot.load(this);
            // A page label is weaker evidence than dated database records.
            // Ask for fresh records when a new punch changes the visible state.
            if (Prefs.useRoot(this) && cur.raw.startsWith("本地考勤数据库")) {
                if ((s.morning != cur.morning || s.evening != cur.evening)
                        && System.currentTimeMillis() - lastDatabaseHint > 15000L) {
                    lastDatabaseHint = System.currentTimeMillis();
                    RefreshService.start(this, RefreshService.ACTION_REFRESH, "auto", null);
                }
                return false;
            }
            preservePunchTimes(s);
            return s.morning != cur.morning || s.evening != cur.evening
                    || !s.morningTime.equals(cur.morningTime)
                    || !s.eveningTime.equals(cur.eveningTime)
                    || !s.note.equals(cur.note)
                    || !s.err.equals(cur.err);
        } catch (Throwable t) {
            return true;
        }
    }

    private void preservePunchTimes(Snapshot s) {
        try {
            Snapshot prev = Snapshot.load(this);
            if (prev != null) {
                if (s.morning == StatusParser.DONE && s.morningTime.isEmpty() && !prev.morningTime.isEmpty()) {
                    s.morningTime = prev.morningTime;
                }
                if (s.evening == StatusParser.DONE && s.eveningTime.isEmpty() && !prev.eveningTime.isEmpty()) {
                    s.eveningTime = prev.eveningTime;
                }
            }
        } catch (Throwable t) { }
    }

    public String currentForegroundPkg() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.isActive()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        try {
                            if (r.getPackageName() != null) return r.getPackageName().toString();
                        } finally {
                            r.recycle();
                        }
                    }
                }
            }
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a != null) {
                try {
                    if (a.getPackageName() != null) return a.getPackageName().toString();
                } finally {
                    a.recycle();
                }
            }
        } catch (Throwable t) { }
        return "";
    }

    public List<String> collectTexts() {
        List<String> out = new ArrayList<String>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (!w.isActive()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    if (r.getPackageName() != null && CheckinReader.WEWORK.contentEquals(r.getPackageName())) {
                        walk(r, out, 0);
                        if (!out.isEmpty()) return out;
                    }
                } finally {
                    r.recycle();
                }
            }
        } catch (Throwable t) { }
        return out;
    }

    // Reads WeWork texts from the given (virtual) display. Does not require the
    // window to be active, so it also works for offscreen virtual displays.
    // Note: no window-type filter here - while the device is dozing the same
    // window can be reported as TYPE_SYSTEM, so we match by display and package.
    public List<String> textsOnDisplay(int displayId, boolean logWindows) {
        List<String> out = new ArrayList<String>();
        try {
            List<AccessibilityWindowInfo> ws = allWindows();
            for (AccessibilityWindowInfo w : ws) {
                int disp = -999;
                try { disp = w.getDisplayId(); } catch (Throwable t) { }
                if (displayId > 0 && disp != displayId) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    String pkg = (r.getPackageName() == null) ? null : r.getPackageName().toString();
                    if (logWindows) {
                        try {
                            android.util.Log.i(VdHelper.TAG, "win type=" + w.getType() + " disp=" + disp
                                    + " act=" + w.isActive()
                                    + " focus=" + w.isFocused() + " layer=" + w.getLayer()
                                    + " rootPkg=" + pkg);
                        } catch (Throwable t) { }
                    }
                    if (pkg != null && CheckinReader.WEWORK.equals(pkg)) {
                        walk(r, out, 0);
                        if (!out.isEmpty()) return out;
                    }
                } finally {
                    r.recycle();
                }
            }
        } catch (Throwable t) { }
        return out;
    }

    // Centre point ("x y") of the first node whose text/contentDescription
    // equals the target on the given display, or null when not found.
    public String findTextCenter(int displayId, String target) {
        try {
            List<AccessibilityWindowInfo> ws = allWindows();
            for (AccessibilityWindowInfo w : ws) {
                int disp = -999;
                try { disp = w.getDisplayId(); } catch (Throwable t) { }
                if (displayId > 0 && disp != displayId) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    String xy = findCenter(r, target, 0);
                    if (xy != null) return xy;
                } finally {
                    r.recycle();
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    private String findCenter(AccessibilityNodeInfo n, String target, int depth) {
        if (n == null || depth > 30) return null;
        String candidate = null;
        CharSequence t = n.getText();
        boolean hit = t != null && target.contentEquals(t);
        if (!hit) {
            CharSequence d = n.getContentDescription();
            hit = d != null && target.contentEquals(d);
        }
        if (hit && n.isVisibleToUser()) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                candidate = ((b.left + b.right) / 2) + " " + ((b.top + b.bottom) / 2);
            }
        }
        int cc = n.getChildCount();
        for (int i = 0; i < cc; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            try {
                String xy = findCenter(c, target, depth + 1);
                if (xy != null) candidate = xy;
            } finally {
                c.recycle();
            }
        }
        // Bottom navigation follows the identically named toolbar title.
        return candidate;
    }

    // Windows on all displays (API 30+); falls back to the default display.
    @SuppressWarnings("unchecked")
    public List<AccessibilityWindowInfo> allWindows() {
        List<AccessibilityWindowInfo> all = new ArrayList<AccessibilityWindowInfo>();
        try {
            android.util.SparseArray<List<AccessibilityWindowInfo>> by =
                    (android.util.SparseArray<List<AccessibilityWindowInfo>>) (Object)
                            getWindowsOnAllDisplays();
            if (by != null) {
                StringBuilder sb = new StringBuilder("allWindows displays:");
                for (int i = 0; i < by.size(); i++) {
                    List<AccessibilityWindowInfo> l = by.valueAt(i);
                    sb.append(' ').append(by.keyAt(i)).append('=').append(l == null ? -1 : l.size());
                    if (l != null) all.addAll(l);
                }
                if (VdHelper.debugEvents) android.util.Log.i(VdHelper.TAG, sb.toString());
            } else if (VdHelper.debugEvents) {
                android.util.Log.i(VdHelper.TAG, "allWindows null");
            }
        } catch (Throwable t) {
            if (VdHelper.debugEvents) android.util.Log.i(VdHelper.TAG, "allWindows threw: " + t);
        }
        if (all.isEmpty()) {
            try { all.addAll(getWindows()); } catch (Throwable t) { }
        }
        return all;
    }

    private void walk(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 30) return;
        // Traverse containers even if invisible; only visible node text counts.
        boolean visible = n.isVisibleToUser();
        CharSequence t = n.getText();
        if (visible && t != null && t.length() > 0) out.add(t.toString());
        CharSequence d = n.getContentDescription();
        if (visible && d != null && d.length() > 0) out.add(d.toString());
        int cc = n.getChildCount();
        for (int i = 0; i < cc; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                walk(c, out, depth + 1);
                c.recycle();
            }
        }
    }

    private boolean clickByText(String target) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                try {
                    if (root.getPackageName() == null) continue;
                    if (!CheckinReader.WEWORK.contentEquals(root.getPackageName())) continue;
                    if (tryClick(root, target, 0)) return true;
                } finally {
                    root.recycle();
                }
            }
        } catch (Throwable t) { }
        return false;
    }

    private boolean tryClick(AccessibilityNodeInfo n, String target, int depth) {
        if (n == null || depth > 30) return false;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        boolean hit = (t != null && target.contentEquals(t)) || (d != null && target.contentEquals(d));
        if (hit) {
            AccessibilityNodeInfo cur = n;
            for (int i = 0; i < 6 && cur != null; i++) {
                if (cur.isClickable()) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        if (cur != n) cur.recycle();
                        return true;
                    }
                    break;
                }
                AccessibilityNodeInfo parent = cur.getParent();
                if (cur != n) cur.recycle();
                cur = parent;
            }
            if (cur != null && cur != n) cur.recycle();
        }
        int cc = n.getChildCount();
        for (int i = 0; i < cc; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                boolean ok = tryClick(c, target, depth + 1);
                c.recycle();
                if (ok) return true;
            }
        }
        return false;
    }

    public boolean runRefreshFlow(final Context ctx, final long baseUpdatedAt, long timeoutMs) {
        if (!flowRunning) {
            h.post(new Runnable() {
                public void run() { startFlow(ctx, false); }
            });
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            CheckinReader.sleep(400);
            if (!flowRunning) break;
        }
        Snapshot s = Snapshot.load(ctx);
        return s.updatedAt > baseUpdatedAt;
    }

    public void beginNavigate(final Context ctx) {
        if (flowRunning) return;
        h.post(new Runnable() {
            public void run() { startFlow(ctx, true); }
        });
    }

    private void startFlow(final Context ctx, final boolean stayOpen) {
        flowRunning = true;
        flowOk = false;
        try {
            Intent i = new Intent();
            i.setClassName(CheckinReader.WEWORK, CheckinReader.WEWORK_MAIN);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) { }
        h.postDelayed(new Runnable() {
            public void run() { stepWorkbench(stayOpen); }
        }, 1300);
    }

    private void stepWorkbench(final boolean stayOpen) {
        clickByText("工作台");
        h.postDelayed(new Runnable() {
            public void run() { stepDaka(stayOpen); }
        }, 1600);
    }

    private void stepDaka(final boolean stayOpen) {
        clickByText("打卡");
        h.postDelayed(new Runnable() {
            public void run() { stepRead(stayOpen, 0); }
        }, 1600);
    }

    private void stepRead(final boolean stayOpen, final int tries) {
        try {
            List<String> texts = collectTexts();
            Snapshot s = StatusParser.parse(texts, true);
            if (s.pageReady) {
                s.updatedAt = System.currentTimeMillis();
                preservePunchTimes(s);
                s.save(this);
                DakaWidgetProvider.updateAll(this);
                flowOk = true;
                if (!stayOpen) {
                    h.postDelayed(new Runnable() {
                        public void run() {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            flowRunning = false;
                        }
                    }, 900);
                } else {
                    flowRunning = false;
                }
                return;
            }
        } catch (Throwable t) { }
        if (tries > 12 || !flowRunning) {
            flowRunning = false;
            if (!stayOpen) {
                // Give up: if the check-in page is still in front, put it back
                // so a failed read leaves nothing behind on the user's screen
                // (mirrors the BACK the successful path performs).
                h.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (CheckinReader.WEWORK.equals(currentForegroundPkg())) {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                            }
                        } catch (Throwable t) { }
                    }
                }, 600);
            }
            return;
        }
        if (tries == 4) clickByText("工作台");
        if (tries == 6) clickByText("打卡");
        h.postDelayed(new Runnable() {
            public void run() { stepRead(stayOpen, tries + 1); }
        }, 700);
    }
}
