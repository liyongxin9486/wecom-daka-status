package com.daka.status;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends android.app.Activity {

    private TextView tvUpdated, tvMorningStatus, tvMorningTime, tvEveningStatus, tvEveningTime;
    private TextView tvInfo, tvEnv, tvDiag;
    private Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean refreshing = false;
    private volatile boolean resumed = false;
    private volatile boolean alarmLost = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        AlarmScheduler.scheduleAll(this);
        Notifier.ensureChannels(this);
        tvUpdated = (TextView) findViewById(R.id.tvUpdated);
        tvMorningStatus = (TextView) findViewById(R.id.tvMorningStatus);
        tvMorningTime = (TextView) findViewById(R.id.tvMorningTime);
        tvEveningStatus = (TextView) findViewById(R.id.tvEveningStatus);
        tvEveningTime = (TextView) findViewById(R.id.tvEveningTime);
        tvInfo = (TextView) findViewById(R.id.tvInfo);
        tvEnv = (TextView) findViewById(R.id.tvEnv);
        tvDiag = (TextView) findViewById(R.id.tvDiag);

        ((Button) findViewById(R.id.btnRefresh)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doRefresh(); }
        });
        ((Button) findViewById(R.id.btnOpen)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { RefreshService.start(MainActivity.this, RefreshService.ACTION_OPEN, "manual", null); }
        });
        findViewById(R.id.cardReport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在打开企业微信「汇报」…", Toast.LENGTH_SHORT).show();
                RefreshService.start(MainActivity.this, RefreshService.ACTION_REPORT, "manual", null);
            }
        });
        ((Button) findViewById(R.id.btnSettings)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        ((Button) findViewById(R.id.btnRoot)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { testRoot(); }
        });
        ((Button) findViewById(R.id.btnA11y)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Throwable t) { }
            }
        });
        ((Button) findViewById(R.id.btnNotif)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    try { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 9); } catch (Throwable t) { }
                } else {
                    try {
                        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                        startActivity(i);
                    } catch (Throwable t) { }
                }
            }
        });
        ((Button) findViewById(R.id.btnAlarm)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 31) {
                    try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); } catch (Throwable t) { }
                } else {
                    Toast.makeText(MainActivity.this, "该安卓版本无需此设置", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected void onResume() {
        super.onResume();
        resumed = true;
        // Self-heal: if the next tick is well overdue, the system dropped our
        // alarms (a force-stop or an OEM killer). Re-arm and tell the user.
        long nt = Prefs.get(this).getLong("nextTickAt", 0L);
        alarmLost = nt > 0 && System.currentTimeMillis() > nt + 15 * 60 * 1000L;
        if (alarmLost) AlarmScheduler.scheduleAll(this);
        // OEM killers can leave the accessibility service unbound; ask for a
        // healing rebind in the background (no UI blocking).
        A11yHeal.ensure(this, 0);
        ui.postDelayed(new Runnable() {
            public void run() { if (resumed) renderEnv(); }
        }, 3000);
        HolidayHelper.syncHolidayOnlineAsync(this);
        render();
        renderEnv();
        Snapshot s = Snapshot.load(this);
        long now = System.currentTimeMillis();
        long lastTry = Prefs.get(this).getLong("lastResumeTry", 0L);
        if (!HolidayHelper.isRestDay(this) && RefreshPolicy.isInRefreshWindow()) {
            if (s.updatedAt == 0 || (now - s.updatedAt > 10 * 60 * 1000L && now - lastTry > 60 * 1000L)) {
                Prefs.get(this).edit().putLong("lastResumeTry", now).apply();
                startServiceRefresh();
            }
        }
        ui.removeCallbacks(uiLoop);
        ui.postDelayed(uiLoop, 1500);
    }

    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(uiLoop);
    }

    // Single UI refresh loop: removed in onPause so it can never run (or stack
    // up) in the background; it only updates texts - no root/env probes here.
    private final Runnable uiLoop = new Runnable() {
        public void run() {
            if (!resumed || isFinishing() || isDestroyed()) return;
            render();
            ui.postDelayed(this, 1500);
        }
    };

    private void startServiceRefresh() {
        refreshing = true;
        RefreshService.start(this, RefreshService.ACTION_REFRESH, "manual", null);
    }

    private void doRefresh() {
        refreshing = true;
        tvUpdated.setText("刷新中…");
        startServiceRefresh();
        ui.postDelayed(new Runnable() {
            public void run() { refreshing = false; render(); }
        }, 30000);
    }

    private void testRoot() {
        Toast.makeText(this, "正在请求 Root，请留意 Magisk 弹窗…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = CheckinReader.isRootAvailable();
                ui.post(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, ok ? "Root 可用 ✓" : "Root 不可用（未授权？）", Toast.LENGTH_LONG).show();
                        renderEnv();
                    }
                });
            }
        }).start();
    }

    private void render() {
        Snapshot s = Snapshot.load(this);
        boolean isRest = HolidayHelper.isRestDay(this);

        // Rest day: always show the rest label, even if a stored snapshot
        // still carries 未打卡 states from a refresh done earlier today.
        if (isRest) {
            String restLabel = HolidayHelper.getRestDayLabel(this);
            tvMorningStatus.setText(restLabel);
            tvMorningStatus.setTextColor(0xFF757575);
            tvMorningTime.setText("");
            tvEveningStatus.setText(restLabel);
            tvEveningStatus.setTextColor(0xFF757575);
            tvEveningTime.setText("");
        } else {
            tvMorningStatus.setText(StatusParser.label(s.morning));
            tvMorningStatus.setTextColor(colorFor(s.morning));
            tvMorningTime.setText(s.morning == StatusParser.DONE && !s.morningTime.isEmpty() ? ("打卡 " + s.morningTime) : "");
            tvEveningStatus.setText(StatusParser.label(s.evening));
            tvEveningStatus.setTextColor(colorFor(s.evening));
            tvEveningTime.setText(s.evening == StatusParser.DONE && !s.eveningTime.isEmpty() ? ("打卡 " + s.eveningTime) : "");
        }

        refreshing = RefreshService.refreshing;
        if (!refreshing) {
            if (s.updatedAt == 0) tvUpdated.setText("尚未刷新");
            else tvUpdated.setText("更新于 " + fmt(s.updatedAt) + (s.err.isEmpty() ? "" : " · " + s.err));
        }

        String schedA = s.schedA.isEmpty() ? Prefs.startTime(this) : s.schedA;
        String schedB = s.schedB.isEmpty() ? Prefs.endTime(this) : s.schedB;
        StringBuilder in = new StringBuilder();
        in.append("班次 ").append(schedA).append(" - ").append(schedB);
        if (isRest) {
            in.append("\n今日为 ").append(HolidayHelper.getRestDayLabel(this)).append("，无需打卡");
            in.append("\n定时自动刷新已暂停");
        } else {
            in.append("\n").append(RefreshPolicy.getWindowStatusText());
        }
        boolean restNoteDup = isRest && s.note.equals(HolidayHelper.getRestDayLabel(this));
        if (!s.note.isEmpty() && !restNoteDup) in.append("\n").append(s.note);
        if (!s.err.isEmpty()) in.append("\n⚠ ").append(s.err);
        if (alarmLost) {
            in.append("\n⚠ 检测到定时闹钟曾被系统清除，本次已自动重排（建议在系统设置中允许“打卡状态”自启动/后台运行）");
        }
        tvInfo.setText(in.toString());

        if (refreshing) tvUpdated.setText("刷新中…");
        tvDiag.setText(s.raw.isEmpty() ? "" : ("读取到的页面文字：\n" + s.raw));

    }

    // Environment probes spawn root shells, so they run on demand (screen open,
    // manual test) instead of every UI refresh tick.
    private void renderEnv() {
        new Thread(new Runnable() {
            public void run() {
                CheckinReader.ensureReadMode(MainActivity.this);
                final boolean root = Prefs.useRoot(MainActivity.this) && CheckinReader.isRootAvailable();
                final boolean a11y = isA11yEnabled();
                final boolean a11yUp = DakaAccessibilityService.inst != null;
                final boolean notif = isNotifEnabled();
                final boolean alarm = isExactAlarmOk();
                final boolean overlay = Settings.canDrawOverlays(MainActivity.this);
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        tvEnv.setText(
                                (Prefs.useRoot(MainActivity.this) ? (root ? "✓ Root 模式：本地考勤记录" : "△ Root 不可用，刷新时切换普通模式") : "✓ 普通模式：无障碍页面识别") + "\n"
                                + (a11yUp ? "✓ 无障碍服务（已连接）"
                                        : (a11y ? "△ 无障碍已启用但未连接（已尝试自动修复，可手动开关一次服务）"
                                                : "✗ 无障碍服务未开启（点“无障碍”开启）")) + "\n"
                                + (notif ? "✓" : "✗") + " 通知权限" + (notif ? "" : "（点“通知”允许）") + "\n"
                                + (alarm ? "✓" : "✗") + " 精确闹钟" + (alarm ? "" : "（点“闹钟”允许）") + "\n"
                                + (overlay ? "✓" : "✗") + " 悬浮窗（无障碍方案需要）");
                    }
                });
            }
        }).start();
    }

    private boolean isA11yEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName() + "/" + DakaAccessibilityService.class.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isNotifEnabled() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isExactAlarmOk() {
        try {
            if (Build.VERSION.SDK_INT < 31) return true;
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return true;
        }
    }

    static int colorFor(int st) {
        switch (st) {
            case StatusParser.DONE: return 0xFF2E7D32;
            case StatusParser.NOT_DONE: return 0xFFC62828;
            case StatusParser.NO_NEED: return 0xFF757575;
            default: return 0xFF616161;
        }
    }

    static String fmt(long t) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    public static int parseHM(String hm) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0].trim()) * 100 + Integer.parseInt(p[1].trim());
        } catch (Throwable t) {
            return 900;
        }
    }
}
