package com.daka.status;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public class Notifier {
    public static final String CH_RUN = "run";
    // Channels are immutable once created, so the audible version needs a new
    // id on devices that already have the old silent "alert" channel.
    public static final String CH_ALERT = "alert2";

    public static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel run = new NotificationChannel(CH_RUN, "刷新服务", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("后台刷新时的短时提示");
        nm.createNotificationChannel(run);
        NotificationChannel al = new NotificationChannel(CH_ALERT, "强提醒（铃声+弹窗+震动）",
                NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("未打卡时响铃、弹出全屏窗口并持续震动");
        android.net.Uri snd = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM);
        if (snd != null) {
            al.setSound(snd, new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
        al.enableVibration(false);
        nm.createNotificationChannel(al);
    }

    public static Notification runningNotification(Context c, String text) {
        ensureChannels(c);
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("打卡状态")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public static void deliverScheduled(Context c, String shift, boolean snooze) {
        deliverScheduled(c, shift, snooze, HolidayHelper.getAttendanceDateStr());
    }

    static Snapshot snapshotForDate(Context c, String workDate) {
        return workDate.equals(HolidayHelper.getAttendanceDateStr()) ? Snapshot.load(c) : new Snapshot();
    }

    public static synchronized void deliverScheduled(Context c, String shift, boolean snooze, String workDate) {
        if (workDate == null) workDate = HolidayHelper.getAttendanceDateStr();
        if (!Prefs.remindOn(c) || HolidayHelper.isRestDayForDate(workDate, c)) return;
        String sh = "evening".equals(shift) ? "evening" : "morning";
        Snapshot snap = snapshotForDate(c, workDate);
        int st = "morning".equals(sh) ? snap.morning : snap.evening;
        if (st == StatusParser.DONE || st == StatusParser.NO_NEED) return;
        String key = "morning".equals(sh) ? "remM" : "remE";
        String day = workDate.replace("-", "");
        if (!snooze && day.equals(Prefs.get(c).getString(key, ""))) return;
        remind(c, sh, snap, false, workDate);
        if (!snooze) Prefs.get(c).edit().putString(key, day).apply();
        android.util.Log.i(VdHelper.TAG, "reminder delivered shift=" + sh + " workDate=" + workDate);
    }

    // Use the same timestamps as AlarmScheduler. An evening catch-up cannot
    // ring before endTime + interval. Check the shift's date across midnight.
    public static synchronized void checkAndRemind(Context c) {
        if (!Prefs.remindOn(c)) return;
        long now = System.currentTimeMillis();
        for (int offset = -1; offset <= 1; offset++) {
            long day = ReminderTime.dayOffset(now, offset);
            String date = ReminderTime.date(day);
            if (HolidayHelper.isRestDayForDate(date, c)) continue;
            for (boolean morning : new boolean[] { true, false }) {
                int hm = MainActivity.parseHM(morning ? Prefs.startTime(c) : Prefs.endTime(c));
                if (ReminderTime.isDue(now, day, hm, Prefs.leadMin(c), morning)) {
                    deliverScheduled(c, morning ? "morning" : "evening", false, date);
                }
            }
        }
    }

    // True if a "HH:mm" time is later than the current "HHmm" value.
    static boolean isFutureHM(String hhmm, int now) {
        try {
            if (hhmm == null) return false;
            String t = hhmm.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) return false;
            int h = Integer.parseInt(t.substring(0, colon).trim());
            int m = Integer.parseInt(t.substring(colon + 1).trim());
            int hm = h * 100 + m;
            // Reminder windows only ever run after 02:00, so a punch time
            // before then can only belong to a previous attendance day.
            return hm > now || hm < Snapshot.DAY_HOUR * 100;
        } catch (Throwable t) {
            return false;
        }
    }

    static int nowHM() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 100 + cal.get(java.util.Calendar.MINUTE);
    }

    static String dayKey() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < Snapshot.DAY_HOUR) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        return String.format(java.util.Locale.US, "%04d%02d%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
    }

    public static void remind(Context c, String shift, Snapshot s) {
        remind(c, shift, s, false);
    }

    // late=false: pre-deadline reminder ("还有45分钟上班…")
    // late=true : catch-up reminder fired after the punch time itself
    public static void remind(Context c, String shift, Snapshot s, boolean late) {
        remind(c, shift, s, late, HolidayHelper.getAttendanceDateStr());
    }

    private static void remind(Context c, String shift, Snapshot s, boolean late, String workDate) {
        ensureChannels(c);
        boolean morning = "morning".equals(shift);
        int lead = Prefs.leadMin(c);
        String leadTxt = lead % 60 == 0 ? (lead / 60) + "小时" : (lead < 60 ? lead + "分钟" : (lead / 60) + "小时" + (lead % 60) + "分钟");
        String title = morning
                ? "上班打卡提醒（提前" + leadTxt + "）"
                : "下班打卡提醒（下班后" + leadTxt + "）";
        String st;
        if (s != null) {
            st = "当前状态：" + (morning ? StatusParser.label(s.morning) : StatusParser.label(s.evening))
                    + " · 立即处理";
        } else {
            st = "未能确认当前状态，请打开打卡页查看";
        }

        AlarmSoundService.start(c, shift);

        Intent ai = new Intent(c, AlertActivity.class);
        ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ai.putExtra(AlertActivity.EXTRA_SHIFT, shift);
        ai.putExtra("workDate", workDate);
        PendingIntent pFull = PendingIntent.getActivity(c, morning ? 24 : 25, ai,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Full-screen-intent notification: this is the fallback that makes the
        // system wake the screen and pop the alert over the keyguard on locked
        // devices. The activity itself cancels this notification when it shows.
        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(st)
                .setContentIntent(pFull)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pFull, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(morning ? 31 : 32, n);

        // Direct launch from the app (works when permitted) ...
        try { c.startActivity(ai); } catch (Throwable t) { }
        // ... plus a root `am start`: OEMs (ColorOS) block background activity
        // starts even with SYSTEM_ALERT_WINDOW, while root is never restricted.
        if (!Prefs.useRoot(c)) return;
        final String sh = morning ? "morning" : "evening";
        final String date = workDate;
        new Thread(new Runnable() {
            public void run() {
                try {
                    CheckinReader.su("am start -n com.daka.status/.AlertActivity --es shift " + sh + " --es workDate " + date, 15000);
                } catch (Throwable t) { }
            }
        }).start();
    }

    public static void cancelAlert(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(31);
                nm.cancel(32);
            }
        } catch (Throwable t) { }
    }
}
