package com.daka.status;

import android.content.Context;
import android.content.SharedPreferences;

public class Snapshot {
    public int morning = StatusParser.UNKNOWN;
    public int evening = StatusParser.UNKNOWN;
    public String morningTime = "";
    public String eveningTime = "";
    public String schedA = "";
    public String schedB = "";
    public String note = "";
    public String err = "";
    public long updatedAt = 0L;
    public String raw = "";
    public boolean pageReady = false;

    public void save(Context c) {
        SharedPreferences.Editor e = Prefs.get(c).edit();
        e.putInt("s_m", morning);
        e.putInt("s_e", evening);
        e.putString("s_mt", morningTime);
        e.putString("s_et", eveningTime);
        e.putString("s_sa", schedA);
        e.putString("s_sb", schedB);
        e.putString("s_note", note);
        e.putString("s_err", err);
        e.putLong("s_up", updatedAt);
        e.putString("s_raw", raw.length() > 1800 ? raw.substring(0, 1800) : raw);
        e.apply();
    }

    // The attendance day runs from 02:00 to 02:00: after 2am yesterday's
    // check-ins no longer count (the new day starts as 未打卡 / 休息), while a late
    // night punch before 2am still belongs to the previous day.
    public static final int DAY_HOUR = 2;

    public static Snapshot load(Context c) {
        SharedPreferences p = Prefs.get(c);
        Snapshot s = new Snapshot();
        long up = p.getLong("s_up", 0L);
        boolean isRest = HolidayHelper.isRestDay(c);

        // Day rollover: yesterday's check-ins no longer count.
        if (up > 0 && up < startOfAttendanceDay()) {
            if (isRest) {
                s.morning = StatusParser.NO_NEED;
                s.evening = StatusParser.NO_NEED;
                s.note = HolidayHelper.getRestDayLabel(c);
            } else {
                s.morning = StatusParser.NOT_DONE;
                s.evening = StatusParser.NOT_DONE;
                // A rest-day note ("周末休息"/"节假日休息") from yesterday must
                // not leak into the new workday; other notes refresh on read.
                String n = p.getString("s_note", "");
                s.note = n.contains("休息") ? "" : n;
            }
            s.schedA = p.getString("s_sa", "");
            s.schedB = p.getString("s_sb", "");
            s.updatedAt = up;
            return s;
        }

        if (up == 0 && isRest) {
            s.morning = StatusParser.NO_NEED;
            s.evening = StatusParser.NO_NEED;
            s.note = HolidayHelper.getRestDayLabel(c);
            s.schedA = p.getString("s_sa", "");
            s.schedB = p.getString("s_sb", "");
            return s;
        }

        s.morning = p.getInt("s_m", StatusParser.UNKNOWN);
        s.evening = p.getInt("s_e", StatusParser.UNKNOWN);
        s.morningTime = p.getString("s_mt", "");
        s.eveningTime = p.getString("s_et", "");
        s.schedA = p.getString("s_sa", "");
        s.schedB = p.getString("s_sb", "");
        s.note = p.getString("s_note", "");
        s.err = p.getString("s_err", "");
        s.updatedAt = up;
        s.raw = p.getString("s_raw", "");
        return s;
    }

    public static long startOfAttendanceDay() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < DAY_HOUR) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, DAY_HOUR);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
