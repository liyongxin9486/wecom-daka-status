package com.daka.status;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Calendar;

// Offline checks for the widget freshness label (WidgetFmt.update).
public class TestWidget {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();

        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.OCTOBER, 17, 16, 30, 0);
        long t = now.getTimeInMillis();
        sb.append("now = 2026-10-17 16:30 (周六, 下午)\n\n");

        check(sb, "从未刷新 -> 尚未刷新", WidgetFmt.update(0, t), "尚未刷新");

        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 17, 14, 30, 0);
        check(sb, "当天 2 小时前 -> HH:mm", WidgetFmt.update(c.getTimeInMillis(), t), "14:30");

        c.set(2026, Calendar.OCTOBER, 16, 23, 50, 0);
        check(sb, "昨晚 23:50（<26h）-> M-d HH:mm",
                WidgetFmt.update(c.getTimeInMillis(), t), "10-16 23:50");

        c.set(2026, Calendar.OCTOBER, 15, 8, 5, 0);
        check(sb, "前天 08:05（>26h）-> 已过期 M-d",
                WidgetFmt.update(c.getTimeInMillis(), t), "已过期 10-15");

        PrintWriter w = new PrintWriter(new FileWriter(args[0]), true);
        w.print(sb.toString());
        w.close();
        System.out.println("TEST RUN OK -> " + args[0]);
    }

    static void check(StringBuilder sb, String label, String got, String want) {
        boolean ok = want.equals(got);
        sb.append(ok ? "[PASS] " : "[FAIL] ").append(label)
                .append("  got='").append(got).append("'");
        if (!ok) sb.append("  want='").append(want).append("'");
        sb.append("\n");
    }
}
