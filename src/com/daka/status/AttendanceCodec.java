package com.daka.status;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** wxSQLite3 AES128 compatibility for read-only copies. See THIRD_PARTY_NOTICES.md. */
public final class AttendanceCodec {
    private static final byte[] PAD = hex("28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a");
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    private static byte[] md5(byte[] b) throws Exception { return MessageDigest.getInstance("MD5").digest(b); }
    private static byte[] rc4(byte[] key, byte[] in) {
        int[] s=new int[256]; for(int i=0;i<256;i++)s[i]=i;
        int j=0;
        for(int i=0;i<256;i++){j=(j+s[i]+(key[i%key.length]&255))&255;int t=s[i];s[i]=s[j];s[j]=t;}
        byte[] out=new byte[in.length];int a=0;j=0;
        for(int i=0;i<in.length;i++){a=(a+1)&255;j=(j+s[a])&255;int t=s[a];s[a]=s[j];s[j]=t;out[i]=(byte)(in[i]^s[(s[a]+s[j])&255]);}
        return out;
    }
    public static byte[] key(long vid) throws Exception {
        byte[] user=new byte[32];
        for(int i=0;i<8;i++)user[i]=(byte)(vid>>>(i*8));
        System.arraycopy(PAD,0,user,8,24);
        byte[] digest=md5(PAD);
        for(int i=0;i<50;i++)digest=md5(digest);
        byte[] owner=user.clone();
        for(int i=0;i<20;i++){byte[] k=digest.clone();for(int j=0;j<k.length;j++)k[j]^=(byte)i;owner=rc4(k,owner);}
        byte[] both=new byte[64];System.arraycopy(user,0,both,0,32);System.arraycopy(owner,0,both,32,32);
        digest=md5(both);for(int i=0;i<50;i++)digest=md5(digest);
        return digest;
    }
    private static void little(byte[] b,int off,long v){for(int i=0;i<4;i++)b[off+i]=(byte)(v>>>(8*i));}
    private static long number(byte[] b,int off,boolean little){
        long n=0;for(int i=0;i<4;i++)n=(n<<8)|(b[off+(little?3-i:i)]&255);return n;
    }
    private static byte[] iv(int page)throws Exception{
        long z=page+1;byte[] b=new byte[16];
        for(int i=0;i<4;i++){z=40692*(z%52774)-3791*(z/52774);if(z<0)z+=2147483399L;little(b,i*4,z);}
        return md5(b);
    }
    public static byte[] page(byte[] in,int page,byte[] key)throws Exception{
        if(in.length<512 || in.length%16!=0)throw new Exception("invalid page");
        byte[] b=in.clone(),header=Arrays.copyOfRange(b,16,24);
        int offset=page==1?16:0;
        if(page==1)System.arraycopy(b,8,b,16,8);
        byte[] material=new byte[24];System.arraycopy(key,0,material,0,16);little(material,16,page);
        material[20]=0x73;material[21]=0x41;material[22]=0x6c;material[23]=0x54;
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(md5(material),"AES"),new IvParameterSpec(iv(page)));
        byte[] decoded=cipher.doFinal(b,offset,b.length-offset);System.arraycopy(decoded,0,b,offset,decoded.length);
        if(page==1){
            if(!Arrays.equals(header,Arrays.copyOfRange(b,16,24)))throw new Exception("database key mismatch");
            System.arraycopy("SQLite format 3\000".getBytes("US-ASCII"),0,b,0,16);
        }
        return b;
    }
    private static void checksum(byte[] b,int off,int len,boolean le,long[] s){
        for(int i=off;i<off+len;i+=8){s[0]=(s[0]+number(b,i,le)+s[1])&0xffffffffL;s[1]=(s[1]+number(b,i+4,le)+s[0])&0xffffffffL;}
    }
    /** Verifies WAL salts/checksums, applies complete committed transactions only. */
    public static byte[] database(byte[] raw,byte[] wal,long vid)throws Exception{
        if(raw.length<100)throw new Exception("short database");
        int size=((raw[16]&255)<<8)|(raw[17]&255);if(size==1)size=65536;
        if(size<512 || size>65536 || (size&(size-1))!=0 || raw.length%size!=0)throw new Exception("page size");
        byte[] key=key(vid);page(Arrays.copyOf(raw,size),1,key);
        Map<Integer,byte[]> pages=new LinkedHashMap<Integer,byte[]>();
        for(int i=0;i<raw.length;i+=size)pages.put(i/size+1,Arrays.copyOfRange(raw,i,i+size));
        int count=raw.length/size;
        if(wal!=null && wal.length>0){
            if(wal.length<32)throw new Exception("short WAL");
            long magic=number(wal,0,false);
            if((magic!=0x377f0682L && magic!=0x377f0683L)||number(wal,8,false)!=size)throw new Exception("WAL format");
            boolean le=magic==0x377f0682L;long[] sum={0,0};checksum(wal,0,24,le,sum);
            if(sum[0]!=number(wal,24,false)||sum[1]!=number(wal,28,false))throw new Exception("WAL header checksum");
            Map<Integer,byte[]> transaction=new LinkedHashMap<Integer,byte[]>();
            for(int off=32;off+24+size<=wal.length;off+=size+24){
                if(!Arrays.equals(Arrays.copyOfRange(wal,off+8,off+16),Arrays.copyOfRange(wal,16,24)))break;
                checksum(wal,off,8,le,sum);checksum(wal,off+24,size,le,sum);
                if(sum[0]!=number(wal,off+16,false)||sum[1]!=number(wal,off+20,false))break;
                long pg=number(wal,off,false),commit=number(wal,off+4,false);
                if(pg<1 || pg>8192 || commit>8192)throw new Exception("WAL bounds");
                transaction.put((int)pg,Arrays.copyOfRange(wal,off+24,off+24+size));
                if(commit>0){pages.putAll(transaction);transaction.clear();count=(int)commit;}
            }
        }
        if(count>8192)throw new Exception("database bounds");
        ByteArrayOutputStream out=new ByteArrayOutputStream(count*size);
        for(int pg=1;pg<=count;pg++){byte[] b=pages.get(pg);if(b==null)throw new Exception("missing page");out.write(page(b,pg,key));}
        return out.toByteArray();
    }
}
