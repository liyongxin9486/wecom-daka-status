package com.daka.status;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HolidayHelper {

    // Built-in statutory holiday dates (YYYY-MM-DD) for 2025 and 2026
    private static final Set<String> STATUTORY_HOLIDAYS = new HashSet<String>();
    // Built-in swapped workdays (周末调休补班)
    private static final Set<String> SWAPPED_WORKDAYS = new HashSet<String>();

    static {
        // --- 2025 Statutory Holidays ---
        addHolidays("2025-01-01", "2025-01-28", "2025-01-29", "2025-01-30", "2025-01-31",
                "2025-02-01", "2025-02-02", "2025-02-03", "2025-02-04",
                "2025-04-04", "2025-04-05", "2025-04-06",
                "2025-05-01", "2025-05-02", "2025-05-03", "2025-05-04", "2025-05-05",
                "2025-05-31", "2025-06-01", "2025-06-02",
                "2025-10-01", "2025-10-02", "2025-10-03", "2025-10-04", "2025-10-05",
                "2025-10-06", "2025-10-07", "2025-10-08");

        // 2025 Swapped Workdays (Weekend -> Work)
        addWorkdays("2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11");

        // --- 2026 Statutory Holidays ---
        addHolidays("2026-01-01", "2026-01-02", "2026-01-03",
                "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
                "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
                "2026-04-04", "2026-04-05", "2026-04-06",
                "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
                "2026-06-19", "2026-06-20", "2026-06-21",
                "2026-09-25", "2026-09-26", "2026-09-27",
                "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05",
                "2026-10-06", "2026-10-07");

        // 2026 Swapped Workdays (Weekend -> Work)
        addWorkdays("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10");
    }

    private static void addHolidays(String... dates) {
        for (String d : dates) STATUTORY_HOLIDAYS.add(d);
    }

    private static void addWorkdays(String... dates) {
        for (String d : dates) SWAPPED_WORKDAYS.add(d);
    }

    // Attendance date string (YYYY-MM-DD), respects 02:00 AM day boundary
    public static String getAttendanceDateStr() {
        Calendar cal = Calendar.getInstance();
        if (cal.get(Calendar.HOUR_OF_DAY) < Snapshot.DAY_HOUR) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        return String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
    }

    // Check if the current attendance day is a rest day (weekend or statutory holiday)
    public static boolean isRestDay(Context context) {
        return isRestDayForDate(getAttendanceDateStr(), context);
    }

    public static boolean isRestDayForDate(String dateStr, Context context) {
        if (context != null) {
            // Check local cached online response first
            SharedPreferences sp = Prefs.get(context);
            int cached = sp.getInt("h_type_" + dateStr, -1);
            if (cached >= 0) {
                // 1 = 周末, 2 = 节日
                return cached == 1 || cached == 2;
            }
        }

        // Check explicit swapped workdays (e.g. Saturday swap to workday)
        if (SWAPPED_WORKDAYS.contains(dateStr)) {
            return false;
        }

        // Check explicit statutory holidays
        if (STATUTORY_HOLIDAYS.contains(dateStr)) {
            return true;
        }

        // Standard weekend check (Saturday or Sunday)
        try {
            String[] parts = dateStr.split("-");
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]) - 1;
            int d = Integer.parseInt(parts[2]);
            Calendar c = Calendar.getInstance();
            c.set(y, m, d);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getRestDayLabel(Context context) {
        String dateStr = getAttendanceDateStr();
        if (STATUTORY_HOLIDAYS.contains(dateStr)) {
            return "节假日休息";
        }
        // 在线同步数据（内置表之外的年份），类型 2 = 节日休息
        if (context != null && Prefs.get(context).getInt("h_type_" + dateStr, -1) == 2) {
            return "节假日休息";
        }
        return "周末休息";
    }

    // 主源：自有服务器全年节假日表（服务器侧每周从官方公告数据重新生成）
    private static final String HOLIDAY_MAP_URL = "http://81.70.63.171/downloads/holiday_days.json";
    // 备源：timor.tech 单日接口
    private static final String TIMOR_INFO_URL = "https://timor.tech/api/holiday/info/";

    // 后台联网同步节假日数据，完全在子线程执行。
    // 成功一次（写入 h_sync）后 24 小时内不再拉取；失败（h_try）至少间隔 1 小时重试。
    // 无论成败都不影响内置表 + 周末规则的兜底判定。
    // 节流闸用静态锁保护：两个入口（tick 闹钟 + App 恢复）几乎同时到达时，
    // 只允许其中一个通过并启动同步线程。
    private static final Object SYNC_GATE = new Object();

    public static void syncHolidayOnlineAsync(final Context context) {
        if (context == null) return;
        final SharedPreferences sp = Prefs.get(context);
        long now = System.currentTimeMillis();
        synchronized (SYNC_GATE) {
            if (now - sp.getLong("h_sync", 0L) < 24 * 3600 * 1000L) return;
            if (now - sp.getLong("h_try", 0L) < 3600 * 1000L) return;
            sp.edit().putLong("h_try", now).apply();
        }

        new Thread(new Runnable() {
            public void run() {
                // 1) 主源：整张节假日表（节日 2 / 调休补班 3），一次拿到全部调休安排
                try {
                    String body = httpGet(HOLIDAY_MAP_URL, 5000, 8000);
                    if (body != null && body.indexOf('{') >= 0) {
                        Matcher m = Pattern.compile("\"(\\d{4}-\\d{2}-\\d{2})\"\\s*:\\s*([0-3])").matcher(body);
                        SharedPreferences.Editor ed = sp.edit();
                        int n = 0;
                        while (m.find()) {
                            ed.putInt("h_type_" + m.group(1), Integer.parseInt(m.group(2)));
                            n++;
                        }
                        if (n > 0) {
                            ed.putLong("h_sync", System.currentTimeMillis()).apply();
                            Log.i(VdHelper.TAG, "holiday sync ok: self n=" + n);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    Log.i(VdHelper.TAG, "holiday sync self err: " + t);
                }
                // 2) 备源：timor.tech，仅同步当天
                try {
                    String ds = getAttendanceDateStr();
                    String body = httpGet(TIMOR_INFO_URL + ds, 5000, 8000);
                    if (body != null) {
                        Matcher m = Pattern.compile("\"type\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*(\\d+)").matcher(body);
                        if (m.find()) {
                            sp.edit().putInt("h_type_" + ds, Integer.parseInt(m.group(1)))
                                    .putLong("h_sync", System.currentTimeMillis()).apply();
                            Log.i(VdHelper.TAG, "holiday sync ok: timor type=" + m.group(1) + " d=" + ds);
                            return;
                        }
                    }
                    Log.i(VdHelper.TAG, "holiday sync failed: self+timor");
                } catch (Throwable t) {
                    Log.i(VdHelper.TAG, "holiday sync failed: " + t);
                }
            }
        }).start();
    }

    // 简单 HTTP GET：200 时返回 UTF-8 文本（上限 512KB），否则 null
    private static String httpGet(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 512 * 1024) throw new IllegalStateException("response too large");
                    bos.write(buf, 0, n);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
