package com.daka.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;

/** Conservative decoder for the local daily-cache protobuf, verified on WeCom 4.1.27. */
public final class AttendanceRecord {
    private static class Field { int tag;long number;byte[] bytes; }
    private static long varint(byte[] b,int[] p)throws Exception{
        long v=0;for(int shift=0;shift<64;shift+=7){if(p[0]>=b.length)throw new Exception("short varint");int c=b[p[0]++]&255;v|=(long)(c&127)<<shift;if(c<128)return v;}throw new Exception("long varint");
    }
    private static List<Field> fields(byte[] b)throws Exception{
        if(b.length>1024*1024)throw new Exception("payload bounds");
        List<Field> out=new ArrayList<Field>();int[] p={0};
        while(p[0]<b.length){long key=varint(b,p);Field f=new Field();f.tag=(int)(key>>>3);int wire=(int)(key&7);
            if(f.tag==0)throw new Exception("field zero");
            if(wire==0)f.number=varint(b,p);
            else if(wire==2){long len=varint(b,p);if(len<0 || len>b.length-p[0])throw new Exception("short field");f.bytes=java.util.Arrays.copyOfRange(b,p[0],p[0]+(int)len);p[0]+=(int)len;}
            else if(wire==1 || wire==5){int n=wire==1?8:4;if(p[0]+n>b.length)throw new Exception("short fixed");p[0]+=n;}
            else throw new Exception("unknown wire");
            out.add(f);
        }return out;
    }
    private static long number(List<Field> fs,int tag){for(Field f:fs)if(f.tag==tag && f.bytes==null)return f.number;return 0;}
    private static String hm(long sec){Calendar c=Calendar.getInstance();c.setTimeInMillis(sec*1000);return String.format(Locale.US,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));}
    private static void shift(Snapshot s,byte[] payload,int expected,long vid,long start,long now)throws Exception{
        List<Field> fs=fields(payload);if(number(fs,6)!=expected)throw new Exception("shift schema mismatch");
        long owner=number(fs,2),actual=number(fs,12),other=number(fs,11);
        if(actual!=other)throw new Exception("ambiguous punch times");
        int status=StatusParser.NOT_DONE;String time="";
        if(actual>0){
            Calendar day=Calendar.getInstance();day.setTimeInMillis(start);long date=(long)day.get(Calendar.YEAR)*10000+(day.get(Calendar.MONTH)+1)*100+day.get(Calendar.DAY_OF_MONTH);
            if(owner!=vid || number(fs,23)!=date || actual*1000<start || actual*1000>now+60000)throw new Exception("punch date/account mismatch");
            status=StatusParser.DONE;time=hm(actual);
        }else if(number(fs,7)==0){throw new Exception("no evidence for unpunched shift");}
        if(expected==1){s.morning=status;s.morningTime=time;}else{s.evening=status;s.eveningTime=time;}
    }
    public static Snapshot parse(byte[] payload,long vid,long start,long now)throws Exception{
        Snapshot s=new Snapshot();List<Field> fs=fields(payload);byte[] m=null,e=null;
        for(Field f:fs){if(f.tag==1)m=f.bytes;if(f.tag==11)e=f.bytes;}
        if(m==null||e==null)throw new Exception("missing daily shifts");
        shift(s,m,1,vid,start,now);shift(s,e,2,vid,start,now);
        long sa=number(fs,2),sb=number(fs,12);
        if(sa*1000<start||sb*1000<start||sa>=sb||sb*1000>=start+26*3600000L)throw new Exception("schedule schema mismatch");
        s.schedA=hm(sa);s.schedB=hm(sb);s.pageReady=true;
        s.note="数据源：本地考勤记录";
        s.raw="本地考勤数据库 · 按账号、日期、上下班类型及实际记录时间校验";
        return s;
    }
}
