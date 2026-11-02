package com.daka.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    // Fired on boot, after app updates, and on clock/timezone changes: re-arms
    // the schedule and re-renders the widget so absolute-time alarms stay
    // aligned with wall-clock times.
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                && Prefs.get(ctx).getInt("parserSchema", 0) < 2) {
            Prefs.get(ctx).edit().remove("s_up").remove("s_m").remove("s_e")
                    .remove("s_mt").remove("s_et").remove("s_err").remove("s_raw")
                    .putInt("parserSchema", 2).apply();
        }
        // Each step is guarded on its own: clock/timezone broadcasts can arrive
        // in unusual states (e.g. before the first unlock), so one failing step
        // must not crash the receiver or block the other.
        try { AlarmScheduler.scheduleAll(ctx); } catch (Throwable t) { }
        try { DakaWidgetProvider.updateAll(ctx); } catch (Throwable t) { }
    }
}
