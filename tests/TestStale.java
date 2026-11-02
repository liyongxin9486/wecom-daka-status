package com.daka.status;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

// 针对凌晨陈旧数据判定（isStalePreviousDay）的边界测试。
// 期望在凌晨 02:00-08:00 时段运行：A 归零(2/2) | B 归零(2/2) | C 保持完成(1/1) | D 归零(2/2)
public class TestStale {
    static PrintStream out;

    static void show(String name, List<String> texts) {
        Snapshot s = StatusParser.parse(texts, true);
        out.println("=== " + name + " ===");
        out.println("pageReady=" + s.pageReady
                + " | morning=" + s.morning + " | evening=" + s.evening
                + " | mTime='" + s.morningTime + "' | eTime='" + s.eveningTime + "'");
        out.println();
    }

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        java.util.Calendar c = java.util.Calendar.getInstance();
        out.println("run at " + String.format("%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
        out.println("期望(凌晨读到该测试时)：A 归零(2/2) | B 归零(2/2) | C 保持完成(1/1) | D 归零(2/2)");
        out.println();

        show("A 前一日 00:30 下班卡（02:00 后读，应归零）", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡", "你已在打卡范围内",
                "今日打卡已完成", "上班时间08:31", "下班时间00:30 更新",
                "上班08:30", "—", "下班18:00", "本月待处理异常", "1天"));

        show("B 前一日 18:02 完成（02:00 后读，应归零）", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡", "你已在打卡范围内",
                "今日打卡已完成", "下班时间18:02 更新",
                "上班08:30", "—", "下班18:00", "本月待处理异常", "1天"));

        show("C 新一日 02:50 下班卡（02:00 后属新一天，应保持完成）", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡", "你已在打卡范围内",
                "今日打卡已完成", "下班时间02:50 更新",
                "上班08:30", "—", "下班18:00"));

        show("D 仅上午 08:05（未来时间，凌晨读应归零）", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡", "你已在打卡范围内",
                "上班时间08:05", "下班打卡", "18:10",
                "上班08:30", "—", "下班18:00"));

        out.flush();
        out.close();
    }
}
