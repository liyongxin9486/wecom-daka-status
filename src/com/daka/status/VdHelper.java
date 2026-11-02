package com.daka.status;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;

// Owns an offscreen virtual display. The WeWork check-in activity can be
// launched onto it with `am start --display <id>` (root), where it renders
// invisibly: no window ever appears on the phone screen and the screen does
// not need to be on.
public class VdHelper {
    public static final String TAG = "DakaVD";
    private static VirtualDisplay vd;
    private static ImageReader reader;
    private static HandlerThread ht;
    public static volatile int displayId = -1;
    public static volatile int w, hgt, dpi;
    public static volatile boolean debugEvents = false;

    public static synchronized int ensure(Context ctx) {
        if (vd != null && displayId > 0) return displayId;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1;
            DisplayMetrics m = new DisplayMetrics();
            android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) d.getRealMetrics(m);
            w = m.widthPixels > 0 ? Math.max(320, m.widthPixels / 2) : 540;
            hgt = m.heightPixels > 0 ? Math.max(480, m.heightPixels / 2) : 1170;
            dpi = m.densityDpi > 0 ? Math.max(120, m.densityDpi / 2) : 210;
            ht = new HandlerThread("daka-vd");
            ht.start();
            reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    try {
                        Image im = r.acquireLatestImage();
                        if (im != null) im.close();
                    } catch (Throwable t) { }
                }
            }, new Handler(ht.getLooper()));
            int[] flagSets = {
                    (1 << 10) | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, // TRUSTED
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            };
            for (int fs : flagSets) {
                try {
                    vd = dm.createVirtualDisplay("daka-silent", w, hgt, dpi, reader.getSurface(), fs, null, null);
                    Log.i(TAG, "vd flags ok=0x" + Integer.toHexString(fs));
                    break;
                } catch (Throwable t) {
                    Log.i(TAG, "vd flags 0x" + Integer.toHexString(fs) + " failed: " + t);
                }
            }
            if (vd == null) {
                release();
                return -1;
            }
            displayId = vd.getDisplay().getDisplayId();
            Log.i(TAG, "vd created id=" + displayId + " " + w + "x" + hgt + "@" + dpi);
            return displayId;
        } catch (Throwable t) {
            Log.w(TAG, "vd create failed", t);
            release();
            return -1;
        }
    }

    public static synchronized void release() {
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        try { if (reader != null) reader.close(); } catch (Throwable t) { }
        try { if (ht != null) ht.quitSafely(); } catch (Throwable t) { }
        vd = null;
        reader = null;
        ht = null;
        displayId = -1;
    }
}
