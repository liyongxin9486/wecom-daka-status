package com.daka.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_TICK = "com.daka.status.action.TICK";
    public static final String ACTION_REMIND_MORNING = "com.daka.status.action.REMIND_MORNING";
    public static final String ACTION_REMIND_EVENING = "com.daka.status.action.REMIND_EVENING";
    public static final String ACTION_SNOOZE = "com.daka.status.action.SNOOZE";

    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? "" : intent.getAction();
        android.util.Log.i(VdHelper.TAG, "alarm received action=" + a);
        if (RefreshService.ACTION_TIMINGTEST.equals(a) || RefreshService.ACTION_RINGTEST.equals(a)) {
            long at = intent.getLongExtra("scheduledAt", 0L);
            android.util.Log.i(VdHelper.TAG, "timing test delivered delayMs=" + (System.currentTimeMillis() - at));
            if (RefreshService.ACTION_RINGTEST.equals(a)) {
                Notifier.remind(ctx, intent.getStringExtra(RefreshService.EXTRA_SHIFT), Snapshot.load(ctx));
            }
        } else if (ACTION_TICK.equals(a)) {
            // Schedule next tick interval or target window
            AlarmScheduler.scheduleTick(ctx);

            // Background async holiday sync if needed
            HolidayHelper.syncHolidayOnlineAsync(ctx);

            // Re-render the widget so 02:00 rollover and rest day notices update immediately
            try { DakaWidgetProvider.updateAll(ctx); } catch (Throwable t) { }

            // Guard 1: Do not auto-refresh on weekends or statutory holidays
            if (HolidayHelper.isRestDay(ctx)) {
                return;
            }

            // Guard 2: Only refresh within 02:00-11:00 (morning) or 17:00-01:00 (evening)
            // Midday (11:00-17:00) and Night (01:00-02:00) pause auto-refresh
            if (!RefreshPolicy.isInRefreshWindow()) {
                return;
            }

            if (Prefs.autoBg(ctx)) {
                RefreshService.start(ctx, RefreshService.ACTION_REFRESH, "auto", null);
            }
        } else if (ACTION_REMIND_MORNING.equals(a)) {
            AlarmScheduler.scheduleReminder(ctx, true);
            if (Prefs.remindOn(ctx)) {
                Notifier.deliverScheduled(ctx, "morning", false, intent.getStringExtra("workDate"));
                RefreshService.start(ctx, RefreshService.ACTION_REFRESH, "auto", null);
            }
        } else if (ACTION_REMIND_EVENING.equals(a)) {
            AlarmScheduler.scheduleReminder(ctx, false);
            if (Prefs.remindOn(ctx)) {
                Notifier.deliverScheduled(ctx, "evening", false, intent.getStringExtra("workDate"));
                RefreshService.start(ctx, RefreshService.ACTION_REFRESH, "auto", null);
            }
        } else if (ACTION_SNOOZE.equals(a)) {
            if (Prefs.remindOn(ctx)) {
                Notifier.deliverScheduled(ctx, intent.getStringExtra(RefreshService.EXTRA_SHIFT), true, intent.getStringExtra("workDate"));
                RefreshService.start(ctx, RefreshService.ACTION_REFRESH, "auto", null);
            }
        } else if (RefreshService.ACTION_VDTEST.equals(a)) {
            RefreshService.start(ctx, RefreshService.ACTION_VDTEST, "auto", null);
        } else if (RefreshService.ACTION_ALERTTEST.equals(a)) {
            RefreshService.start(ctx, RefreshService.ACTION_ALERTTEST, "auto",
                    intent.getStringExtra(RefreshService.EXTRA_SHIFT));
        }
    }
}
