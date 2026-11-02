package com.daka.status;

import android.app.KeyguardManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetch statistics on the virtual display; interpret only database protobufs. */
public final class LocalAttendanceReader {
    public static volatile boolean running;
    private static final String BASE="/data/user/0/com.tencent.wework";
    private static final Pattern PROFILE=Pattern.compile("^"+BASE+"/app_data/Profiles/[A-Fa-f0-9]{32}/attendance/attendance\\.db$");
    private static final Pattern VID=Pattern.compile("^(\\d{12,19})\\.db$");

    // Android app-data isolation hides other packages in the app mount namespace,
    // even for uid 0. Magisk's master namespace exposes the actual app files.
    private static CheckinReader.ER shell(String command,long timeout){
        return CheckinReader.exec(new String[]{"su","-mm","-c",command},timeout);
    }

    private static byte[] copy(String path)throws Exception{
        CheckinReader.ER r=shell("base64 "+path,8000);
        if(r.code!=0 || r.out.length()>12*1024*1024)throw new Exception("database copy failed");
        return Base64.decode(r.out,Base64.DEFAULT);
    }
    private static long account()throws Exception{
        CheckinReader.ER r=shell("ls "+BASE+"/databases",6000);long vid=0;
        for(String name:r.out.split("\\s+")){Matcher m=VID.matcher(name);if(m.matches()){
            long v=Long.parseLong(m.group(1));if(vid!=0 && vid!=v)throw new Exception("multiple accounts; refuse to guess");vid=v;
        }}
        if(vid==0)throw new Exception("account ID unavailable");return vid;
    }
    private static String path()throws Exception{
        CheckinReader.ER r=shell("find "+BASE+"/app_data/Profiles -maxdepth 3 -name attendance.db",6000);
        String found=null;
        for(String p:r.out.split("\\r?\\n")){p=p.trim();if(PROFILE.matcher(p).matches()){
            if(found!=null)throw new Exception("multiple profiles; refuse to guess");found=p;
        }}
        if(found==null)throw new Exception("attendance cache unavailable");return found;
    }
    private static Snapshot read(Context ctx,String path,long vid,long minimumFetch)throws Exception{
        byte[] raw=copy(path),wal=copy(path+"-wal");
        byte[] decoded=AttendanceCodec.database(raw,wal,vid);
        // A standalone snapshot needs no WAL or sidecars. The original stays untouched.
        decoded[18]=1;decoded[19]=1;
        File f=File.createTempFile("attendance-", ".sqlite",ctx.getCacheDir());SQLiteDatabase db=null;
        try{
            FileOutputStream out=new FileOutputStream(f);try{out.write(decoded);}finally{out.close();}
            db=SQLiteDatabase.openDatabase(f.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor integrity=db.rawQuery("PRAGMA quick_check",null);
            try{if(!integrity.moveToFirst()||!"ok".equals(integrity.getString(0)))throw new Exception("snapshot integrity");}finally{integrity.close();}
            long start=Snapshot.startOfAttendanceDay(),now=System.currentTimeMillis();
            Cursor c=db.rawQuery("SELECT CAST(data AS BLOB),fetch_time FROM Attendence_Furture4 WHERE time>=? AND time<? ORDER BY fetch_time DESC LIMIT 1",
                    new String[]{String.valueOf(start/1000),String.valueOf(start/1000+24*3600)});
            try{
                if(!c.moveToFirst()){Log.i(VdHelper.TAG,"local cache daily row missing start="+start);return null;}
                long fetch=c.getLong(1);
                Log.i(VdHelper.TAG,"local cache fetch="+fetch+" minimum="+minimumFetch);
                if(fetch<minimumFetch || fetch>now/1000+60)return null;
                Snapshot s=AttendanceRecord.parse(c.getBlob(0),vid,start,now);
                s.raw+=" · 缓存更新 "+new java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.US).format(new java.util.Date(fetch*1000));
                return s;
            }finally{c.close();}
        }finally{
            if(db!=null)db.close();f.delete();new File(f.getPath()+"-wal").delete();new File(f.getPath()+"-shm").delete();
        }
    }
    private static boolean available(Context ctx){
        PowerManager pm=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km=(KeyguardManager)ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return (pm==null||pm.isInteractive()) && (km==null||!km.isKeyguardLocked());
    }
    public static Snapshot refresh(Context ctx){
        if(!Prefs.useRoot(ctx)||!available(ctx)||DakaAccessibilityService.inst==null)return null;
        int display=-1;boolean opened=false;running=true;
        try{
            long vid=account();String path=path();
            // Verify account/key before navigating. Never interpret an old cache as fresh.
            byte[] first=copy(path);AttendanceCodec.page(first,1,AttendanceCodec.key(vid));
            long baseline=System.currentTimeMillis()/1000;
            display=VdHelper.ensure(ctx);if(display<=0)return null;
            CheckinReader.ER open=CheckinReader.su("timeout 10 am start --display "+display+" -n "+CheckinReader.WEWORK+"/"+CheckinReader.CHECKIN_ACT,14000);
            if(open.code!=0||open.out.contains("Error")||open.out.contains("Exception"))return null;
            opened=true;
            if(!available(ctx)||!CheckinReader.tapVd(display,"打卡",7000))return null;
            CheckinReader.sleep(350);
            if(!available(ctx)||!CheckinReader.tapVd(display,"统计",7000))return null;
            for(int attempt=0;attempt<4;attempt++){
                CheckinReader.sleep(attempt==0?1200:800);
                if(!available(ctx))return null;
                Snapshot s=read(ctx,path,vid,baseline);
                if(s!=null){Log.i(VdHelper.TAG,"local database read OK morning="+s.morningTime+" evening="+s.eveningTime);return s;}
            }
            Log.i(VdHelper.TAG,"local database cache not refreshed; use page fallback");
        }catch(Exception e){Log.i(VdHelper.TAG,"local database unavailable: "+e.getMessage());}
        finally{
            if(opened && available(ctx)){
                CheckinReader.tapVd(display,"打卡",1500);
                CheckinReader.sleep(350);
                CheckinReader.su("timeout 6 input -d "+display+" keyevent 4",9000);
                CheckinReader.sleep(350);
            }
            running=false;
        }
        return null;
    }
}
