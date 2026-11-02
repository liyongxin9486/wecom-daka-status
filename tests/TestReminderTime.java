package com.daka.status;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

public class TestReminderTime {
    static int checks;
    static long at(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s).getTime();
    }
    static void eq(String name, long actual, String expected) throws Exception {
        if (actual != at(expected)) throw new AssertionError(name + " actual=" + actual);
        checks++;
    }
    static void due(String now, String day, int hm, int lead, boolean morning, boolean expected) throws Exception {
        if (ReminderTime.isDue(at(now), at(day + " 12:00:00"), hm, lead, morning) != expected)
            throw new AssertionError("due " + now + " " + morning);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        long day = at("2026-11-03 12:00:00");
        eq("morning", ReminderTime.trigger(day, 830, 30, true), "2026-11-03 08:00:00");
        eq("evening", ReminderTime.trigger(day, 1800, 30, false), "2026-11-03 18:30:00");
        eq("hour borrow", ReminderTime.trigger(day, 830, 60, true), "2026-11-03 07:30:00");
        eq("hour carry", ReminderTime.trigger(day, 1845, 45, false), "2026-11-03 19:30:00");
        eq("prior day", ReminderTime.trigger(day, 30, 60, true), "2026-11-02 23:30:00");
        eq("next day", ReminderTime.trigger(day, 2330, 60, false), "2026-11-04 00:30:00");
        eq("seconds cleared", ReminderTime.trigger(at("2026-11-03 10:22:59"), 830, 30, true), "2026-11-03 08:00:00");
        for (int lead : new int[]{30,45,60,90,120}) {
            eq("morning option " + lead, ReminderTime.trigger(day,830,lead,true),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(at("2026-11-03 08:30:00")-lead*60000L));
            eq("evening option " + lead, ReminderTime.trigger(day,1800,lead,false),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(at("2026-11-03 18:00:00")+lead*60000L));
        }
        due("2026-11-03 07:59:59","2026-11-03",830,30,true,false);
        due("2026-11-03 08:00:00","2026-11-03",830,30,true,true);
        due("2026-11-03 09:29:59","2026-11-03",830,30,true,true);
        due("2026-11-03 09:30:00","2026-11-03",830,30,true,false);
        for (String time : new String[]{"17:30:00","18:00:00","18:29:59"})
            due("2026-11-03 " + time,"2026-11-03",1800,30,false,false);
        due("2026-11-03 18:30:00","2026-11-03",1800,30,false,true);
        due("2026-11-03 19:29:59","2026-11-03",1800,30,false,true);
        due("2026-11-03 19:30:00","2026-11-03",1800,30,false,false);
        due("2026-11-04 00:30:00","2026-11-03",2330,60,false,true);
        due("2026-11-02 23:30:00","2026-11-03",30,60,true,true);
        System.out.println("ALL " + checks + " REMINDER TIME CHECKS PASSED");
    }
}
