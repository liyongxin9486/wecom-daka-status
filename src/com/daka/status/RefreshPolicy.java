package com.daka.status;

import android.content.Context;

import java.util.Calendar;

public class RefreshPolicy {

    // Morning window: 02:00 - 11:00
    // Evening window: 17:00 - 01:00 (next day)
    // Midday pause:   11:00 - 17:00 (中间不刷新)
    // Night pause:    01:00 - 02:00 (夜间不刷新，等待02:00日切)
    public static boolean isInRefreshWindow() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour >= 2 && hour < 11) return true;
        if (hour >= 17 || hour < 1) return true;
        return false;
    }

    // Determine current phase description
    public static String getWindowStatusText() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour >= 2 && hour < 11) {
            return "早上打卡刷新期 (02:00-11:00)";
        } else if (hour >= 17 || hour < 1) {
            return "下班打卡刷新期 (17:00-01:00)";
        } else if (hour >= 11 && hour < 17) {
            return "日间非刷新期 (11:00-17:00 暂停自动刷新)";
        } else {
            return "夜间非刷新期 (01:00-02:00 等待日切)";
        }
    }

    // Calculates next timestamp for AlarmManager, jumping past pause periods and rest days
    public static long getNextTickTime(Context context) {
        return nextTick(System.currentTimeMillis(), Prefs.tickMin(context), HolidayHelper.isRestDay(context));
    }

    static long nextTick(long now, int tickMinutes, boolean restDay) {
        long tickMs = Math.max(1, tickMinutes) * 60000L;
        Calendar boundary = Calendar.getInstance();
        boundary.setTimeInMillis(now);
        boundary.set(Calendar.HOUR_OF_DAY, Snapshot.DAY_HOUR);
        boundary.set(Calendar.MINUTE, 0);
        boundary.set(Calendar.SECOND, 0);
        boundary.set(Calendar.MILLISECOND, 0);
        if (boundary.getTimeInMillis() <= now) boundary.add(Calendar.DAY_OF_MONTH, 1);
        long next = Math.min(now + tickMs, boundary.getTimeInMillis());

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(next);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        // If today is a rest day, jump directly to tomorrow 02:00
        if (restDay) {
            Calendar nextWork = Calendar.getInstance();
            nextWork.setTimeInMillis(now);
            if (nextWork.get(Calendar.HOUR_OF_DAY) >= Snapshot.DAY_HOUR) {
                nextWork.add(Calendar.DAY_OF_MONTH, 1);
            }
            nextWork.set(Calendar.HOUR_OF_DAY, Snapshot.DAY_HOUR);
            nextWork.set(Calendar.MINUTE, 0);
            nextWork.set(Calendar.SECOND, 0);
            nextWork.set(Calendar.MILLISECOND, 0);
            return nextWork.getTimeInMillis();
        }

        // If next tick lands in 11:00 - 17:00 (midday pause), jump directly to 17:00
        if (hour >= 11 && hour < 17) {
            cal.set(Calendar.HOUR_OF_DAY, 17);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }

        // If next tick lands in 01:00 - 02:00 (night pause), jump directly to 02:00
        if (hour >= 1 && hour < 2) {
            cal.set(Calendar.HOUR_OF_DAY, 2);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }

        return next;
    }
}
