package com.daka.status;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

public class AlarmScheduler {
    public static final int RC_TICK = 101;
    public static final int RC_MORN = 102;
    public static final int RC_EVEN = 103;
    public static final int RC_SNOOZE = 104;

    public static void scheduleAll(Context c) {
        // Setting the same PendingIntent replaces its alarm. Do not cancel a
        // pending snooze simply because the main screen was opened.
        long nextTick = RefreshPolicy.getNextTickTime(c);
        setExact(c, RC_TICK, nextTick, intentFor(c, AlarmReceiver.ACTION_TICK));

        if (Prefs.remindOn(c)) {
            scheduleReminder(c, true);
            scheduleReminder(c, false);
        } else {
            cancelReminder(c, RC_MORN, AlarmReceiver.ACTION_REMIND_MORNING);
            cancelReminder(c, RC_EVEN, AlarmReceiver.ACTION_REMIND_EVENING);
            cancelReminder(c, RC_SNOOZE, AlarmReceiver.ACTION_SNOOZE);
        }
    }

    private static void cancelReminder(Context c, int rc, String action) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, rc, intentFor(c, action),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) am.cancel(pi);
    }

    public static void scheduleTick(Context c) {
        long nextTick = RefreshPolicy.getNextTickTime(c);
        setExact(c, RC_TICK, nextTick, intentFor(c, AlarmReceiver.ACTION_TICK));
    }

    public static void scheduleSnooze(Context c, String shift) {
        scheduleSnooze(c, shift, HolidayHelper.getAttendanceDateStr());
    }

    public static void scheduleSnooze(Context c, String shift, String workDate) {
        Intent i = intentFor(c, AlarmReceiver.ACTION_SNOOZE);
        i.putExtra(RefreshService.EXTRA_SHIFT, shift == null ? "morning" : shift);
        i.putExtra("workDate", workDate);
        setAlarmClock(c, RC_SNOOZE, System.currentTimeMillis() + 5 * 60 * 1000L, i);
    }

    public static void scheduleReminder(Context c, boolean morning) {
        int hm = MainActivity.parseHM(morning ? Prefs.startTime(c) : Prefs.endTime(c));
        long now = System.currentTimeMillis();
        // Check the shift's date, not the offset reminder's calendar date.
        for (int offset = -1; offset <= 370; offset++) {
            long day = ReminderTime.dayOffset(now, offset);
            long at = ReminderTime.trigger(day, hm, Prefs.leadMin(c), morning);
            String date = ReminderTime.date(day);
            if (at <= now || HolidayHelper.isRestDayForDate(date, c)) continue;
            Intent i = intentFor(c, morning ? AlarmReceiver.ACTION_REMIND_MORNING : AlarmReceiver.ACTION_REMIND_EVENING);
            i.putExtra("workDate", date);
            i.putExtra("scheduledAt", at);
            setAlarmClock(c, morning ? RC_MORN : RC_EVEN, at, i);
            android.util.Log.i(VdHelper.TAG, "reminder scheduled shift=" + (morning ? "morning" : "evening")
                    + " workDate=" + date + " at=" + at);
            return;
        }
    }

    static long nextDaily(int hm, int minusMinutes) {
        Calendar cal = Calendar.getInstance();
        int h = hm / 100, m = hm % 100;
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MINUTE, -minusMinutes);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    // Finds the next daily trigger timestamp on a WORKDAY (skipping weekends & holidays)
    static long nextDailyWorkday(int hm, int minusMinutes, Context c) {
        long trigger = nextDaily(hm, minusMinutes);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(trigger);
        for (int i = 0; i < 14; i++) {
            String dateStr = String.format(Locale.US, "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            if (!HolidayHelper.isRestDayForDate(dateStr, c)) {
                return cal.getTimeInMillis();
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return trigger;
    }

    static void setExact(Context c, int rc, long at, Intent i) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean ok = false;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            ok = true;
        } catch (Throwable t) {
            try { am.set(AlarmManager.RTC_WAKEUP, at, pi); ok = true; } catch (Throwable t2) { }
        }
        // Remember when the next tick was due so the UI can notice that the
        // system dropped our alarms (e.g. a force-stop) and re-arm on open.
        if (ok && rc == RC_TICK) Prefs.get(c).edit().putLong("nextTickAt", at).apply();
    }

    // Reminders use setAlarmClock: delivered exactly even in doze (no 1-hour
    // allow-while-idle window), shown on the lock screen as the next alarm,
    // and they grant the app the foreground-service start exemption when they
    // fire. Falls back to plain exact / allow-while-idle alarms when the
    // exact-alarm permission is missing.
    static void setAlarmClock(Context c, int rc, long at, Intent i) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = PendingIntent.getActivity(c, 300 + rc,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            }
        } catch (Throwable t) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Throwable t2) { }
        }
    }

    static Intent intentFor(Context c, String action) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        return i;
    }

    public static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(PendingIntent.getBroadcast(c, RC_TICK, intentFor(c, AlarmReceiver.ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        am.cancel(PendingIntent.getBroadcast(c, RC_MORN, intentFor(c, AlarmReceiver.ACTION_REMIND_MORNING),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        am.cancel(PendingIntent.getBroadcast(c, RC_EVEN, intentFor(c, AlarmReceiver.ACTION_REMIND_EVENING),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        am.cancel(PendingIntent.getBroadcast(c, RC_SNOOZE, intentFor(c, AlarmReceiver.ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
