package com.daka.status;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TimePicker;
import android.widget.Toast;

public class SettingsActivity extends android.app.Activity {

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        final TimePicker tpStart = (TimePicker) findViewById(R.id.tpStart);
        final TimePicker tpEnd = (TimePicker) findViewById(R.id.tpEnd);
        final Spinner spLead = (Spinner) findViewById(R.id.spLead);
        final Switch swRemind = (Switch) findViewById(R.id.swRemind);
        final Switch swAuto = (Switch) findViewById(R.id.swAuto);
        final Switch swBack = (Switch) findViewById(R.id.swBack);
        final android.widget.RadioGroup readMode = (android.widget.RadioGroup) findViewById(R.id.readMode);

        tpStart.setIs24HourView(Boolean.TRUE);
        tpEnd.setIs24HourView(Boolean.TRUE);

        int[] hmS = split(Prefs.startTime(this));
        int[] hmE = split(Prefs.endTime(this));
        tpStart.setHour(hmS[0]);
        tpStart.setMinute(hmS[1]);
        tpEnd.setHour(hmE[0]);
        tpEnd.setMinute(hmE[1]);

        final int[] leadVals = { 30, 45, 60, 90, 120 };
        String[] leadNames = { "30 分钟", "45 分钟", "1 小时", "1.5 小时", "2 小时" };
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, leadNames);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLead.setAdapter(ad);
        int lead = Prefs.leadMin(this);
        for (int i = 0; i < leadVals.length; i++) if (leadVals[i] == lead) spLead.setSelection(i);

        final Spinner spTick = (Spinner) findViewById(R.id.spTick);
        final int[] tickVals = { 1, 2, 3, 5, 10, 15, 30, 60 };
        String[] tickNames = { "1 分钟", "2 分钟", "3 分钟", "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时" };
        ArrayAdapter<String> ad2 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, tickNames);
        ad2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTick.setAdapter(ad2);
        int tk = Prefs.tickMin(this);
        for (int i = 0; i < tickVals.length; i++) if (tickVals[i] == tk) spTick.setSelection(i);

        swRemind.setChecked(Prefs.remindOn(this));
        swAuto.setChecked(Prefs.autoBg(this));
        swBack.setChecked(Prefs.backAfter(this));
        readMode.check(Prefs.useRoot(this) ? R.id.modeRoot : R.id.modeNormal);

        ((Button) findViewById(R.id.btnSave)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String st = two(tpStart.getHour()) + ":" + two(tpStart.getMinute());
                String et = two(tpEnd.getHour()) + ":" + two(tpEnd.getMinute());
                Prefs.put(SettingsActivity.this, "startTime", st);
                Prefs.put(SettingsActivity.this, "endTime", et);
                int lPos = spLead.getSelectedItemPosition();
                if (lPos >= 0 && lPos < leadVals.length) Prefs.putI(SettingsActivity.this, "leadMin", leadVals[lPos]);
                int tPos = spTick.getSelectedItemPosition();
                if (tPos >= 0 && tPos < tickVals.length) Prefs.putI(SettingsActivity.this, "tickMin", tickVals[tPos]);
                Prefs.putB(SettingsActivity.this, "remindOn", swRemind.isChecked());
                Prefs.putB(SettingsActivity.this, "autoBg", swAuto.isChecked());
                Prefs.putB(SettingsActivity.this, "backAfter", swBack.isChecked());
                final boolean wantRoot = readMode.getCheckedRadioButtonId() == R.id.modeRoot;
                v.setEnabled(false);
                new Thread(new Runnable() {
                    public void run() {
                        final boolean root = wantRoot && CheckinReader.isRootAvailable();
                        Prefs.setReadMode(SettingsActivity.this, root);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                AlarmScheduler.scheduleAll(SettingsActivity.this);
                                Toast.makeText(SettingsActivity.this, wantRoot && !root
                                        ? "Root 不可用，已保存为普通模式" : "已保存", Toast.LENGTH_LONG).show();
                                RefreshService.start(SettingsActivity.this, RefreshService.ACTION_REFRESH, "auto", null);
                                finish();
                            }
                        });
                    }
                }, "save-read-mode").start();

            }
        });
    }

    static int[] split(String hm) {
        try {
            String[] p = hm.split(":");
            return new int[] { Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
        } catch (Throwable t) {
            return new int[] { 8, 30 };
        }
    }

    static String two(int v) {
        return v < 10 ? ("0" + v) : ("" + v);
    }
}
