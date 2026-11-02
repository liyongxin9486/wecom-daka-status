package com.daka.status;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class DakaWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_WIDGET_REFRESH = "com.daka.status.action.WIDGET_REFRESH";

    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, buildViews(c));
    }

    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (i == null) return;
        String a = i.getAction();
        if (ACTION_WIDGET_REFRESH.equals(a)) {
            RefreshService.start(c, RefreshService.ACTION_REFRESH, "manual", null);
        }
        // APPWIDGET_UPDATE is already dispatched to onUpdate by super.onReceive,
        // so no extra updateAll here (that would update every widget twice).
    }

    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, DakaWidgetProvider.class));
        if (ids == null) return;
        for (int id : ids) m.updateAppWidget(id, buildViews(c));
    }

    static RemoteViews buildViews(Context c) {
        Snapshot s = Snapshot.load(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_status);
        boolean isRest = HolidayHelper.isRestDay(c);

        // On a rest day the widget always shows the rest label, regardless of
        // what the last page read stored (a manual refresh after 02:00 on a
        // weekend reads the previous day's completed card and would otherwise
        // flip the display to 未打卡).
        if (isRest) {
            String restLabel = HolidayHelper.getRestDayLabel(c);
            v.setTextViewText(R.id.wTvM, restLabel);
            v.setTextViewText(R.id.wTvE, restLabel);
            v.setTextColor(R.id.wTvM, 0xFF757575);
            v.setTextColor(R.id.wTvE, 0xFF757575);
            v.setTextViewText(R.id.wTvUpd, "今日休息");
        } else {
            String mtxt = StatusParser.label(s.morning);
            if (s.morning == StatusParser.DONE && !s.morningTime.isEmpty()) mtxt = mtxt + " " + s.morningTime;
            String etxt = StatusParser.label(s.evening);
            if (s.evening == StatusParser.DONE && !s.eveningTime.isEmpty()) etxt = etxt + " " + s.eveningTime;

            v.setTextViewText(R.id.wTvM, mtxt);
            v.setTextViewText(R.id.wTvE, etxt);
            v.setTextColor(R.id.wTvM, colorFor(s.morning));
            v.setTextColor(R.id.wTvE, colorFor(s.evening));
            v.setTextViewText(R.id.wTvUpd, WidgetFmt.update(s.updatedAt, System.currentTimeMillis()));
        }

        Intent ri = new Intent(c, DakaWidgetProvider.class);
        ri.setAction(ACTION_WIDGET_REFRESH);
        PendingIntent pr = PendingIntent.getBroadcast(c, 1, ri,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.wTvRefresh, pr);

        PendingIntent pmain = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.wRoot, pmain);
        return v;
    }

    static int colorFor(int st) {
        switch (st) {
            case StatusParser.DONE: return 0xFF2E7D32;
            case StatusParser.NOT_DONE: return 0xFFC62828;
            case StatusParser.NO_NEED: return 0xFF757575;
            default: return 0xFF616161;
        }
    }
}
