package com.daka.status;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/** Sound is owned by a bounded foreground service, independent of popup policy. */
public class AlarmSoundService extends Service {
    private final Handler handler = new Handler();
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Vibrator vibrator;
    private int savedVolume = -1;
    private int raisedVolume = -1;
    private String shift;
    private boolean active;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();

    public static boolean start(Context c, String shift) {
        Intent i = new Intent(c, AlarmSoundService.class);
        i.putExtra("shift", "evening".equals(shift) ? "evening" : "morning");
        try {
            c.startForegroundService(i);
            return true;
        } catch (Throwable t) {
            Log.e(VdHelper.TAG, "alarm sound service start blocked", t);
            // Exact alarms normally have an exemption. Retain the existing
            // root-mode fallback for OEM background-start restrictions.
            if (Prefs.useRoot(c)) {
                final String sh = "evening".equals(shift) ? "evening" : "morning";
                new Thread(new Runnable() {
                    public void run() { CheckinReader.su("am start-foreground-service -n com.daka.status/.AlarmSoundService --es shift " + sh, 10000); }
                }, "alarm-start").start();
            }
            return false;
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, AlarmSoundService.class));
    }

    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String nextShift = intent.getStringExtra("shift");
        Notifier.ensureChannels(this);
        Intent open = new Intent(this, AlertActivity.class).putExtra("shift", nextShift);
        PendingIntent pi = PendingIntent.getActivity(this, 421, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 422,
                new Intent(this, AlarmSoundService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, Notifier.CH_RUN)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("打卡提醒正在响铃")
                .setContentText("点击查看提醒，或点停止响铃").setContentIntent(pi)
                .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止响铃", stop).build()).build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(110, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else startForeground(110, n);
        } catch (Throwable t) {
            Log.e(VdHelper.TAG, "alarm foreground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Notification, direct launch and root launch may all open the same
        // alert. Do not restart sound or extend the timeout for duplicates.
        if (active && java.util.Objects.equals(shift, nextShift)) return START_NOT_STICKY;
        releaseSound();
        shift = nextShift;
        active = true;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        public void onAudioFocusChange(int change) { }
                    }).build();
            Log.i(VdHelper.TAG, "alarm audio focus=" + audio.requestAudioFocus(focus));
            int cur = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            int want = Math.max(cur, (int) Math.ceil(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7));
            if (want > cur) {
                savedVolume = cur;
                audio.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
                raisedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            }
            Log.i(VdHelper.TAG, "alarm stream volume=" + audio.getStreamVolume(AudioManager.STREAM_ALARM));
        } catch (Throwable t) { Log.w(VdHelper.TAG, "alarm volume/focus", t); }
        Uri system = null;
        try { system = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM); }
        catch (Throwable t) { Log.w(VdHelper.TAG, "default alarm unavailable", t); }
        play(system, false);
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 700, 260, 700, 260, 900}, 0), attributes);
            }
        } catch (Throwable t) { Log.w(VdHelper.TAG, "alarm vibration", t); }
        handler.postDelayed(new Runnable() {
            public void run() { if (vibrator != null) vibrator.cancel(); }
        }, 90000L);
        handler.postDelayed(new Runnable() {
            public void run() { stopSelf(); }
        }, 120000L);
        return START_NOT_STICKY;
    }

    private void play(Uri uri, final boolean fallback) {
        if (player != null) { player.release(); player = null; }
        if (uri == null && !fallback) { play(null, true); return; }
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(attributes);
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            if (fallback) {
                android.content.res.AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.reminder_alarm);
                try { p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength()); }
                finally { fd.close(); }
            } else p.setDataSource(this, uri);
            p.setLooping(true);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                    Log.i(VdHelper.TAG, "alarm playback started fallback=" + fallback + " isPlaying=" + mp.isPlaying());
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(VdHelper.TAG, "alarm playback error=" + what + "/" + extra + " fallback=" + fallback);
                    if (!fallback) play(null, true); else stopSelf();
                    return true;
                }
            });
            p.prepareAsync();
        } catch (Throwable t) {
            Log.e(VdHelper.TAG, "alarm source failed fallback=" + fallback, t);
            if (!fallback) play(null, true); else stopSelf();
        }
    }

    private void releaseSound() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (vibrator != null) vibrator.cancel();
        if (audio != null) {
            try {
                if (focus != null) audio.abandonAudioFocusRequest(focus);
                // Preserve volume adjustments made by the user while ringing.
                if (savedVolume >= 0 && audio.getStreamVolume(AudioManager.STREAM_ALARM) == raisedVolume) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
                }
            } catch (Throwable t) { Log.w(VdHelper.TAG, "alarm audio cleanup", t); }
        }
        savedVolume = raisedVolume = -1;
        active = false;
    }

    public void onDestroy() {
        releaseSound();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(VdHelper.TAG, "alarm playback stopped");
        super.onDestroy();
    }
}
