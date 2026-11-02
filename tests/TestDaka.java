package com.daka.status;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

// 离线解析回归：三张真机截图经 OCR 后的页面文本（无设备依赖）。
// 注意：涉及"陈旧归零"的用例结果与运行时刻有关（凌晨 02:00 后读才触发归零）。
public class TestDaka {
    static PrintStream out;

    static void show(String name, List<String> texts) {
        Snapshot s = StatusParser.parse(texts, true);
        out.println("=== " + name + " ===");
        out.println("pageReady=" + s.pageReady
                + " | morning=" + desc(s.morning) + " | evening=" + desc(s.evening)
                + " | mTime='" + s.morningTime + "' | eTime='" + s.eveningTime + "'"
                + " | schedA=" + s.schedA + " | schedB=" + s.schedB);
        out.println("note='" + s.note + "' err='" + s.err + "'");
        out.println();
    }

    static String desc(int st) {
        switch (st) {
            case 0: return "UNKNOWN(0)";
            case 1: return "DONE(1)";
            case 2: return "NOT_DONE(2)";
            case 3: return "NO_NEED(3)";
            default: return "?" + st;
        }
    }

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        java.util.Calendar c = java.util.Calendar.getInstance();
        out.println("run at " + String.format("%04d-%02d-%02d %02d:%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
        out.println();

        show("STATE 1 waiting 17:10 (morning done, not in range)", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "不在打卡范围内", "有疑问？查看帮助 >",
                "下班打卡", "17:10", "请18:00后打卡",
                "上班08:30", "—", "下班18:00",
                "本月待处理异常", "1天",
                "地图", "第1次拍照打卡"));

        show("STATE 2 ready 18:02 (in range)", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "你已在打卡范围内", "太原大船科技股份有限公司",
                "下班打卡", "18:02", "请18:00后打卡",
                "上班08:30", "—", "下班18:00",
                "本月待处理异常", "1天"));

        show("STATE 3 completed 02:30 (stale prev-day, should reset)", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "你已在打卡范围内", "太原大船科技股份有限公司",
                "03", ":", "13", "下班·范围外",
                "今日打卡已完成", "时间：", "18:02", "位置：", "备注：",
                "报告原因...", "更新下班卡",
                "今日打卡已完成，好好休息", "下班时间18:02 更新",
                "上班08:30", "—", "下班18:00",
                "本月待处理异常", "1天"));

        show("STATE 4 completed with today's time (should stay DONE)", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "今日打卡已完成", "更新下班卡", "下班时间02:50 更新",
                "上班08:30", "—", "下班18:00"));

        show("STATE 5 morning not punched yet (button 上班打卡)", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "不在打卡范围内", "上班打卡", "08:10", "请08:30前打卡",
                "上班08:30", "—", "下班18:00"));

        show("STATE 6 morning done with time, waiting evening", Arrays.asList(
                "打卡", "上下班打卡", "外出打卡",
                "你已在打卡范围内",
                "上班时间08:05", "下班打卡", "18:10",
                "上班08:30", "—", "下班18:00"));

        show("STATE 7 not the checkin page", Arrays.asList("微信", "通讯录", "发现", "我"));

        out.flush();
        out.close();
    }
}
