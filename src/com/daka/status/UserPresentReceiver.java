package com.daka.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UserPresentReceiver extends BroadcastReceiver {
    private static long screenOnAt;

    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_SCREEN_OFF.equals(action)) { screenOnAt = 0L; return; }
        if (!Intent.ACTION_SCREEN_ON.equals(action) && !Intent.ACTION_USER_PRESENT.equals(action)) return;
        if (!Prefs.autoBg(ctx)) return;
        DakaWidgetProvider.updateAll(ctx);
        long now = System.currentTimeMillis();
        if (Intent.ACTION_SCREEN_ON.equals(action)) screenOnAt = now;
        if (HolidayHelper.isRestDay(ctx) || !RefreshPolicy.isInRefreshWindow()) return;
        if (Intent.ACTION_USER_PRESENT.equals(action)
                && Prefs.get(ctx).getLong("s_up", 0L) >= screenOnAt && screenOnAt > 0) return;
        // Unlock must retry even immediately after a locked SCREEN_ON skip.
        // The service coalesces overlapping requests without dropping this read.
        android.util.Log.i(VdHelper.TAG, "screen refresh action=" + action);
        AlarmScheduler.scheduleTick(ctx);
        RefreshService.start(ctx, RefreshService.ACTION_REFRESH, "screen", null);
    }
}
