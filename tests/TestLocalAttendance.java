package com.daka.status;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

public class TestLocalAttendance {
    private static int count;
    static long at(int day,int h,int m){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,h,m);return c.getTimeInMillis();}
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);count++;System.out.println("PASS "+label);}
    static byte[] v(long n){ByteArrayOutputStream b=new ByteArrayOutputStream();while((n&~127L)!=0){b.write(((int)n&127)|128);n>>>=7;}b.write((int)n);return b.toByteArray();}
    static byte[] number(int tag,long n)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(v(tag*8));b.write(v(n));return b.toByteArray();}
    static byte[] block(int tag,byte[] p)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(v(tag*8+2));b.write(v(p.length));b.write(p);return b.toByteArray();}
    static byte[] join(byte[]...parts)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    static byte[] punched(int shift,int day,int h,int m)throws Exception{long t=at(day,h,m)/1000;return join(number(2,42),number(6,shift),number(7,0),number(11,t),number(12,t),number(23,20261000+day));}
    static byte[] daily(byte[] m,byte[] e)throws Exception{return join(block(1,m),number(2,at(26,8,30)/1000),block(11,e),number(12,at(26,18,0)/1000));}
    static void rejects(byte[] payload,String label)throws Exception{boolean threw=false;try{AttendanceRecord.parse(payload,42,at(26,2,0),at(26,21,0));}catch(Exception e){threw=true;}check(threw,label);}
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        byte[] morning=punched(1,26,7,55),evening=punched(2,26,19,3);
        Snapshot s=AttendanceRecord.parse(daily(morning,evening),42,at(26,2,0),at(26,21,0));
        check(s.morning==1&&s.evening==1,"recorded shifts completed");
        check(s.morningTime.equals("07:55")&&s.eveningTime.equals("19:03"),"actual times, not schedule");
        check(s.schedA.equals("08:30")&&s.schedB.equals("18:00"),"schedule separate from punch times");
        byte[] pending=join(number(6,2),number(7,64));
        s=AttendanceRecord.parse(daily(morning,pending),42,at(26,2,0),at(26,9,0));
        check(s.morning==1&&s.evening==2&&s.eveningTime.isEmpty(),"morning punch does not complete evening");
        rejects(daily(punched(1,25,7,55),evening),"reject previous day");
        rejects(daily(punched(1,27,7,55),evening),"reject future date");
        rejects(daily(evening,morning),"reject reversed shifts");
        rejects(daily(join(number(6,1),number(2,43),number(11,at(26,7,55)/1000),number(12,at(26,7,55)/1000),number(23,20261026)),evening),"reject other account");
        rejects(daily(join(number(6,1),number(7,0)),evening),"no success without actual record");
        rejects(new byte[]{10,100},"reject truncated protobuf");
        if(args.length>=3){
            byte[] db=Files.readAllBytes(Paths.get(args[0])),wal=Files.readAllBytes(Paths.get(args[1]));
            byte[] decoded=AttendanceCodec.database(db,wal,Long.parseLong(args[2]));
            check(new String(decoded,0,15,"US-ASCII").equals("SQLite format 3"),"real snapshot decrypts with verified WAL");
            if(args.length>=4)check(Arrays.equals(decoded,Files.readAllBytes(Paths.get(args[3]))),"entire decoded snapshot matches independent reader");
            boolean fail=false;try{AttendanceCodec.database(db,wal,43);}catch(Exception e){fail=true;}check(fail,"wrong database key rejected");
        }
        System.out.println("ALL "+count+" LOCAL RECORD CHECKS PASSED");
    }
}
