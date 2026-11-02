package com.daka.status;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

// Pure formatters for the widget's freshness label. Kept free of Android
// imports so the offline test harness can execute them on a plain JVM.
public class WidgetFmt {

    // "尚未刷新" when there was never an update; "HH:mm" for same-day data;
    // "M-d HH:mm" when the update is from another day but still recent; and an
    // explicit "已过期 M-d" once the data is older than 26h, so a silently
    // stopped app is visible instead of a misleadingly fresh-looking status.
    public static String update(long updatedAt, long now) {
        if (updatedAt <= 0) return "尚未刷新";
        if (now - updatedAt > 26L * 3600 * 1000) {
            return "已过期 " + new SimpleDateFormat("M-d", Locale.getDefault())
                    .format(new Date(updatedAt));
        }
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(updatedAt);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now);
        boolean sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(sameDay ? "HH:mm" : "M-d HH:mm", Locale.getDefault())
                .format(new Date(updatedAt));
    }
}
