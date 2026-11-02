package com.daka.status;

import java.util.Calendar;
import java.util.Locale;

/** The work date belongs to the shift, even when its reminder crosses midnight. */
public final class ReminderTime {
    private ReminderTime() { }

    public static String date(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static long punch(long day, int hm) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        c.set(Calendar.HOUR_OF_DAY, hm / 100);
        c.set(Calendar.MINUTE, hm % 100);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long trigger(long day, int hm, int minutes, boolean morning) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(punch(day, hm));
        c.add(Calendar.MINUTE, morning ? -minutes : minutes);
        return c.getTimeInMillis();
    }

    public static long dayOffset(long now, int offset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, offset);
        return c.getTimeInMillis();
    }

    public static boolean isDue(long now, long day, int hm, int minutes, boolean morning) {
        long at = trigger(day, hm, minutes, morning);
        long until = Math.max(at, punch(day, hm)) + 60 * 60 * 1000L;
        return now >= at && now < until;
    }
}
