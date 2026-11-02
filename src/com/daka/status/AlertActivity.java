package com.daka.status;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

// Strong reminder: a full-screen window that pops over the lock screen or over
// whatever app is open, with continuous strong vibration until acknowledged.
public class AlertActivity extends Activity {
    public static final String EXTRA_SHIFT = "shift";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable stopRingR = new Runnable() {
        public void run() { stopRing(); }
    };
    private volatile boolean stopped = false;
    private String shift = "morning";
    private String workDate;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        } catch (Throwable t) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_alert);

        renderUI();
        Notifier.cancelAlert(this);

        ((Button) findViewById(R.id.alOpen)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopAll();
                RefreshService.start(AlertActivity.this, RefreshService.ACTION_OPEN, "manual", null);
                finish();
            }
        });
        ((Button) findViewById(R.id.alSnooze)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopAll();
                AlarmScheduler.scheduleSnooze(AlertActivity.this, shift, workDate);
                finish();
            }
        });
        ((Button) findViewById(R.id.alOk)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopAll();
                finish();
            }
        });

        startRing();
        h.postDelayed(stopRingR, 120000L);
        h.postDelayed(new Runnable() {
            public void run() { if (!stopped) { stopAll(); finish(); } }
        }, 300000L);
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        stopped = false;
        renderUI();
        h.removeCallbacksAndMessages(null);
        startRing();
        h.postDelayed(stopRingR, 120000L);
        h.postDelayed(new Runnable() {
            public void run() { if (!stopped) { stopAll(); finish(); } }
        }, 300000L);
    }

    private void renderUI() {
        if (getIntent() != null && getIntent().getStringExtra(EXTRA_SHIFT) != null) {
            shift = getIntent().getStringExtra(EXTRA_SHIFT);
        }
        final boolean morning = "morning".equals(shift);

        workDate = getIntent().getStringExtra("workDate");
        if (workDate == null) workDate = HolidayHelper.getAttendanceDateStr();
        Snapshot s = Notifier.snapshotForDate(this, workDate);
        int st = morning ? s.morning : s.evening;
        ((TextView) findViewById(R.id.alTitle)).setText((morning ? "上班" : "下班") + "还没打卡！");
        ((TextView) findViewById(R.id.alSub)).setText("当前状态：" + StatusParser.label(st));
        ((TextView) findViewById(R.id.alTime)).setText("现在时间 " + nowStr()
                + " · 班次 " + (s.schedA.isEmpty() ? Prefs.startTime(this) : s.schedA)
                + " - " + (s.schedB.isEmpty() ? Prefs.endTime(this) : s.schedB));
    }

    protected void onResume() {
        super.onResume();
        h.postDelayed(autoDismiss, 5000L);
    }

    protected void onPause() {
        super.onPause();
        h.removeCallbacks(autoDismiss);
    }

    // If the punch comes in while the alert is up, close it automatically.
    private final Runnable autoDismiss = new Runnable() {
        public void run() {
            if (stopped) return;
            Snapshot s = Notifier.snapshotForDate(AlertActivity.this, workDate);
            int st = "morning".equals(shift) ? s.morning : s.evening;
            if (st == StatusParser.DONE || st == StatusParser.NO_NEED) {
                stopAll();
                finish();
                return;
            }
            h.postDelayed(this, 5000L);
        }
    };

    private void startRing() { AlarmSoundService.start(this, shift); }
    private void stopRing() { AlarmSoundService.stop(this); }

    private void stopAll() {
        stopped = true;
        stopRing();
        Notifier.cancelAlert(this);
    }

    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // Keep ringing/vibrating when the alert loses focus (another app pushed in
    // front) - the reminder must not become silent just because the window was
    // covered. onDestroy/stopAll still stop it, and the ring is capped at 2 min.
    protected void onStop() {
        super.onStop();
    }

    private String nowStr() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
