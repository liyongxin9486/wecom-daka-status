package com.daka.status;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/** Text fixtures transcribed from the six supplied screenshots, plus conflicts
 * and fixed-time scheduling boundaries. No OCR or live device required. */
public class TestRegression {
    private static int passed;

    static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    static Snapshot parse(long now, String... text) {
        return StatusParser.parse(Arrays.asList(text), true, now);
    }

    static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }

    static void state(String label, Snapshot s, int morning, int evening) {
        check(label + " ready", s.pageReady);
        check(label + " state " + morning + "/" + evening,
                s.morning == morning && s.evening == evening);
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        state("image 1", parse(at(16,17,10), "上下班打卡", "不在打卡范围内",
                "下班打卡", "17:10", "请18:00后打卡", "上班08:30", "下班18:00"), 1, 2);
        state("image 2", parse(at(16,18,2), "上下班打卡", "你已在打卡范围内",
                "下班打卡", "18:02", "请18:00后打卡", "上班08:30", "下班18:00"), 1, 2);
        state("image 3 before rollover", parse(at(16,23,0), "上下班打卡",
                "今日打卡已完成，好好休息", "下班时间18:02 更新", "上班08:30", "下班18:00"), 1, 1);
        state("image 3 at captured 02:30 is previous day", parse(at(17,2,30), "上下班打卡",
                "今日打卡已完成，好好休息", "下班时间18:02 更新", "上班08:30", "下班18:00"), 2, 2);
        Snapshot fourth = parse(at(26,7,57), "上下班打卡", "上班自动打卡·正常",
                "18:00之后可打下班卡", "时间：07:55", "位置：公司范围内", "更新上班卡",
                "上班08:30", "下班18:00");
        state("image 4", fourth, 1, 2);
        check("image 4 recorded morning time", "07:55".equals(fourth.morningTime));
        check("image 4 prompt is not evening record", fourth.eveningTime.isEmpty());
        state("image 5", parse(at(26,8,52), "上下班打卡", "你已在打卡范围内",
                "下班打卡", "08:52", "请18:00后打卡", "上班08:30", "下班18:00"), 1, 2);
        state("image 6", parse(at(26,8,53), "上下班打卡", "不在打卡范围内",
                "下班打卡", "08:53", "请18:00后打卡", "上班08:30", "下班18:00"), 1, 2);
        Snapshot split = parse(at(26,7,57), "上下班打卡", "上班自动打卡·正常", "时间：", "07:55",
                "更新上班卡", "18:00之后可打下班卡");
        state("split time", split, 1, 2);
        check("split time record", "07:55".equals(split.morningTime));
        state("hidden completion loses to current morning result", parse(at(26,8,53),
                "上下班打卡", "今日打卡已完成", "下班时间18:02 更新", "更新下班卡",
                "上班自动打卡·正常", "时间：07:55", "更新上班卡"), 1, 2);
        state("hidden completion loses to current action", parse(at(26,8,53),
                "上下班打卡", "今日打卡已完成", "更新下班卡", "下班时间18:02 更新", "下班打卡"), 1, 2);
        state("morning not punched", parse(at(26,8,0), "上下班打卡", "上班打卡", "08:00"), 2, 2);
        check("update link alone is inconclusive", !parse(at(26,8,0), "上下班打卡", "更新下班卡").pageReady);
        check("location alone is inconclusive", !parse(at(26,8,0), "上下班打卡", "你已在打卡范围内").pageReady);
        check("completion without dated time is inconclusive", !parse(at(26,8,0),
                "上下班打卡", "今日打卡已完成").pageReady);
        check("wrong page", !parse(at(26,8,0), "通讯录", "微信").pageReady);
        Snapshot clock = parse(at(26,8,53), "上下班打卡", "下班打卡 08:53", "请18:00后打卡");
        state("live button clock", clock, 1, 2);
        check("live clock never a punch time", clock.eveningTime.isEmpty());
        state("previous day retained before 02:00", parse(at(27,1,30), "上下班打卡",
                "今日打卡已完成", "下班时间19:03 更新"), 1, 1);
        state("00:30 record stale after boundary", parse(at(27,2,30), "上下班打卡",
                "今日打卡已完成", "下班时间00:30 更新"), 2, 2);
        state("02:50 record current at 03:00", parse(at(27,3,0), "上下班打卡",
                "今日打卡已完成", "下班时间02:50 更新"), 1, 1);
        state("generic no need", parse(at(26,8,0), "上下班打卡", "今日无需打卡"), 3, 3);
        check("60 minute interval preserved", RefreshPolicy.nextTick(at(26,8,0),60,false) == at(26,9,0));
        check("3 minute interval supported", RefreshPolicy.nextTick(at(26,8,0),3,false) == at(26,8,3));
        check("midday pause", RefreshPolicy.nextTick(at(26,10,59),3,false) == at(26,17,0));
        check("night pause", RefreshPolicy.nextTick(at(26,0,59),3,false) == at(26,2,0));
        check("long interval never skips day boundary", RefreshPolicy.nextTick(at(26,23,30),180,false) == at(27,2,0));
        check("rest day next rollover", RefreshPolicy.nextTick(at(26,12,0),60,true) == at(27,2,0));
        check("rest day before rollover", RefreshPolicy.nextTick(at(26,1,0),60,true) == at(26,2,0));
        System.out.println("ALL " + passed + " CHECKS PASSED");
    }
}
