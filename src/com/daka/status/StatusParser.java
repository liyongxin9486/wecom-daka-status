package com.daka.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatusParser {
    public static final int UNKNOWN = 0;
    public static final int DONE = 1;
    public static final int NOT_DONE = 2;
    public static final int NO_NEED = 3;

    private static final Pattern P_TIME = Pattern.compile("(上班|下班)(?:时间|打卡时间)\\s*[:：]?\\s*(\\d{1,2}[:：]\\d{2})");
    private static final Pattern P_BTN = Pattern.compile("^(上班|下班)打卡(?:\\(|（|\\s|$)");
    private static final Pattern P_SCHED_A = Pattern.compile("^上班\\s*[:：]?\\s*(\\d{1,2}[:：]\\d{2})$");
    private static final Pattern P_SCHED_B = Pattern.compile("^下班\\s*[:：]?\\s*(\\d{1,2}[:：]\\d{2})$");
    private static final Pattern P_ANOMALY = Pattern.compile("^(\\d+(?:\\.\\d+)?\\s*(?:天|小时|分钟))$");

    public static String label(int st) {
        switch (st) {
            case DONE: return "已打卡";
            case NOT_DONE: return "未打卡";
            case NO_NEED: return "无需打卡";
            default: return "未知";
        }
    }

    public static Snapshot parse(List<String> texts) {
        return parse(texts, false);
    }

    public static Snapshot parse(List<String> texts, boolean requireTabs) {
        return parse(texts, requireTabs, System.currentTimeMillis());
    }

    // Explicit clock makes day-boundary regressions reproducible.
    public static Snapshot parse(List<String> texts, boolean requireTabs, long now) {
        Snapshot s = new Snapshot();
        StringBuilder raw = new StringBuilder();
        boolean completed = false;
        boolean morningSuccess = false;
        String successShift = null;
        boolean nextIsPunchTime = false;
        boolean noNeed = false;
        boolean readyMarker = false;
        boolean hasTabs = false;
        boolean mNoNeed = false, eNoNeed = false, mUndone = false, eUndone = false;
        Set<String> buttons = new HashSet<String>();
        String lastShift = null, lastTime = null;
        String anomaly = null;
        boolean nextIsAnomaly = false;

        for (String t0 : texts) {
            if (t0 == null) continue;
            String t = t0.replace('\u00a0', ' ').trim();
            if (t.isEmpty()) continue;
            raw.append(t).append(" | ");

            if (t.equals("上下班打卡") || t.equals("外出打卡")) hasTabs = true;

            if (t.startsWith("今日打卡已完成")) completed = true;
            if (t.matches("^上班(?:自动)?打卡[·・：:].*") || t.equals("更新上班卡")) {
                morningSuccess = true;
                successShift = "上班";
            }
            if (t.matches("^下班(?:自动)?打卡[·・：:].*")) successShift = "下班";
            if (successShift != null && t.matches("^时间\\s*[:：].*")) {
                Matcher tm = Pattern.compile("(\\d{1,2}[:：]\\d{2})").matcher(t);
                if (tm.find()) {
                    if ("上班".equals(successShift)) s.morningTime = tm.group(1).replace('：', ':');
                    else s.eveningTime = tm.group(1).replace('：', ':');
                } else nextIsPunchTime = true;
            } else if (nextIsPunchTime) {
                if (t.matches("\\d{1,2}[:：]\\d{2}")) {
                    if ("上班".equals(successShift)) s.morningTime = t.replace('：', ':');
                    else s.eveningTime = t.replace('：', ':');
                }
                nextIsPunchTime = false;
            }

            if (t.contains("你已在打卡范围内") || t.contains("不在打卡范围内")
                    || t.contains("本月待处理异常") || t.contains("上班时间") || t.contains("下班时间")
                    || t.equals("上班打卡") || t.equals("下班打卡")
                    || t.startsWith("上班打卡(") || t.startsWith("上班打卡（")
                    || t.startsWith("下班打卡(") || t.startsWith("下班打卡（")
                    || t.contains("无需打卡") || t.contains("不需要打卡")
                    || t.contains("打卡成功") || t.contains("当前时间不能打卡")
                    || t.contains("更新下班卡")
                    || t.startsWith("今日打卡")) readyMarker = true;

            if (t.contains("无需打卡") || t.contains("不需要打卡")) {
                noNeed = true;
                if (t.contains("上班")) mNoNeed = true;
                if (t.contains("下班")) eNoNeed = true;
            }
            if (t.contains("上班") && (t.contains("未打卡") || t.contains("缺卡"))) mUndone = true;
            if (t.contains("下班") && (t.contains("未打卡") || t.contains("缺卡"))) eUndone = true;

            if ((t.startsWith("你已在打卡范围内") || t.equals("不在打卡范围内") || t.contains("定位存在异常"))
                    && t.length() < 50 && s.note.isEmpty()) {
                s.note = t;
            }

            if (nextIsAnomaly) {
                Matcher am = P_ANOMALY.matcher(t);
                if (am.matches()) { anomaly = am.group(1); nextIsAnomaly = false; continue; }
                nextIsAnomaly = false;
            }
            if (t.contains("本月待处理异常")) { nextIsAnomaly = true; continue; }

            Matcher m = P_TIME.matcher(t);
            if (m.find()) {
                lastShift = m.group(1);
                lastTime = m.group(2).replace('：', ':');
                if ("上班".equals(lastShift)) s.morningTime = lastTime;
                else if ("下班".equals(lastShift)) s.eveningTime = lastTime;
                continue;
            }

            m = P_BTN.matcher(t);
            if (m.find()) { buttons.add(m.group(1)); continue; }

            m = P_SCHED_A.matcher(t);
            if (m.matches()) { s.schedA = m.group(1).replace('：', ':'); continue; }
            m = P_SCHED_B.matcher(t);
            if (m.matches()) { s.schedB = m.group(1).replace('：', ':'); continue; }
        }

        s.raw = raw.toString();
        s.pageReady = (readyMarker || morningSuccess || !buttons.isEmpty()) && (!requireTabs || hasTabs);
        if (!s.pageReady) return s;

        // Current actionable state outranks a stale completion label elsewhere
        // in WeCom's tree. A standalone update link is not proof of completion.
        if (morningSuccess || (buttons.contains("下班") && !buttons.contains("上班"))) {
            s.morning = mUndone ? NOT_DONE : (mNoNeed ? NO_NEED : DONE);
            s.evening = eNoNeed ? NO_NEED : NOT_DONE;
            s.eveningTime = "";
        } else if (buttons.contains("上班")) {
            s.morning = mNoNeed ? NO_NEED : NOT_DONE;
            s.evening = eNoNeed ? NO_NEED : NOT_DONE;
            s.morningTime = "";
            s.eveningTime = "";
        } else if (completed && !s.eveningTime.isEmpty()) {
            s.morning = mUndone ? NOT_DONE : DONE;
            s.evening = eUndone ? NOT_DONE : DONE;
        } else {
            s.morning = !s.morningTime.isEmpty() ? DONE : (mUndone ? NOT_DONE : UNKNOWN);
            s.evening = !s.eveningTime.isEmpty() ? DONE : (eUndone ? NOT_DONE : UNKNOWN);
            if (mNoNeed) s.morning = NO_NEED;
            if (eNoNeed) s.evening = NO_NEED;
            if (noNeed && !mNoNeed && !eNoNeed) {
                s.morning = NO_NEED;
                s.evening = NO_NEED;
            }
        }

        if (s.morning == UNKNOWN && s.evening == UNKNOWN) s.pageReady = false;

        if (anomaly != null) {
            s.note = (s.note.isEmpty() ? "" : s.note + " · ") + "本月异常 " + anomaly;
        }

        // Stale-day guard: after midnight the page can still show the previous
        // day's "今日打卡已完成" (with its punch times). Once we are past the
        // 02:00 boundary those flags must not count as the new day's.
        String latest = s.eveningTime.isEmpty() ? s.morningTime : s.eveningTime;
        if (isStalePreviousDay(latest, now)) {
            s.morning = NOT_DONE;
            s.evening = NOT_DONE;
            s.morningTime = "";
            s.eveningTime = "";
        }
        return s;
    }

    // True when an "HH:mm" punch time cannot belong to the current attendance
    // day: it either lies in the future on the clock, or before the 02:00 day
    // boundary. Only evaluated at/after the boundary (before 02:00 the previous
    // day is still the current attendance day), so a hit means the parsed data
    // still belongs to the previous day.
    private static boolean isStalePreviousDay(String hhmm, long now) {
        try {
            if (hhmm == null || hhmm.isEmpty()) return false;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(now);
            int nowHM = cal.get(java.util.Calendar.HOUR_OF_DAY) * 100 + cal.get(java.util.Calendar.MINUTE);
            if (nowHM < Snapshot.DAY_HOUR * 100) return false;
            String t = hhmm.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) return false;
            int h = Integer.parseInt(t.substring(0, colon).trim());
            int m = Integer.parseInt(t.substring(colon + 1).trim());
            int hm = h * 100 + m;
            // Punch times before 02:00 belong to the previous attendance day,
            // so after the day boundary they are stale too (not just future ones).
            return hm > nowHM || hm < Snapshot.DAY_HOUR * 100;
        } catch (Throwable t) {
            return false;
        }
    }
}
