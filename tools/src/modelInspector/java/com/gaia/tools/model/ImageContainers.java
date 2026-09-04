package com.gaia.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Versioned container admission, before any ImageIO reader sees image bytes. */
final class ImageContainers {
    static final class Rejected extends IllegalArgumentException {
        final String code;
        Rejected(String code) {super(code);this.code=code;}
    }
    record Admitted(String format,int width,int height,int channels,ByteBuffer source,int neutralBytes) {
        Admitted {source=source.asReadOnlyBuffer();}
        /** Called only after aggregate RGBA reservation. No metadata passed to PNG reader. */
        ByteBuffer decodeBytes() {
            if(format.equals("jpeg"))return source.asReadOnlyBuffer();
            ByteBuffer out=ByteBuffer.allocate(neutralBytes), in=source.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            out.put(in.slice(0,8));in.position(8);
            while(in.hasRemaining()) {
                int start=in.position(), length=in.getInt(), type=in.getInt();
                int total=Math.addExact(length,12);
                if(type==0x49484452 || type==0x49444154 || type==0x49454e44)out.put(in.slice(start,total));
                in.position(Math.addExact(start,total));
            }
            out.flip();return out.asReadOnlyBuffer();
        }
    }
    static Admitted admit(ByteBuffer source,String mime) {
        ByteBuffer in=source.slice().asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        if("image/png".equals(mime))return png(in);
        if("image/jpeg".equals(mime))return jpeg(in);
        throw new Rejected("UNSUPPORTED_IMAGE_PROFILE");
    }
    private static Admitted png(ByteBuffer in) {
        valid(in.remaining()>=8 && in.getLong()==0x89504e470d0a1a0aL);
        int width=0,height=0,channels=0,count=0,neutral=8,seenColor=0;
        boolean data=false,end=false;
        var crc=new CRC32();
        while(in.hasRemaining()) {
            limit(++count<=HandToolProfile.MAX_PNG_CHUNKS);valid(!end && in.remaining()>=12);
            long size=Integer.toUnsignedLong(in.getInt());valid(size<=in.remaining()-8L);
            int typeAt=in.position(),type=in.getInt(),body=in.position(),n=Math.toIntExact(size);
            crc.reset();crc.update(in.slice(typeAt,Math.addExact(n,4)));
            int tail=Math.addExact(body,n);valid(crc.getValue()==Integer.toUnsignedLong(in.getInt(tail)));
            if(count==1)valid(type==0x49484452);
            switch(type) {
                case 0x49484452 -> {
                    valid(count==1 && n==13);width=in.getInt(body);height=in.getInt(body+4);
                    int depth=u(in,body+8),color=u(in,body+9);
                    profile(depth==8 && (color==2 || color==6) && u(in,body+10)==0 && u(in,body+11)==0 && u(in,body+12)==0);
                    channels=color==2?3:4;dimensions(width,height);
                }
                case 0x49444154 -> {valid(count>1);data=true;}
                case 0x49454e44 -> {valid(data && n==0);end=true;}
                case 0x73524742,0x67414d41,0x6348524d -> {
                    valid(!data && count>1);
                    int bit=type==0x73524742?1:type==0x67414d41?2:4;valid((seenColor&bit)==0);seenColor|=bit;
                    if(bit==1) {valid(n==1);profile(u(in,body)<=3);}
                    else if(bit==2) {valid(n==4);profile(in.getInt(body)==45455);}
                    else {
                        valid(n==32);int[] standard={31270,32900,64000,33000,30000,60000,15000,6000};
                        for(int i=0;i<8;i++)profile(in.getInt(body+i*4)==standard[i]);
                    }
                }
                default -> throw new Rejected("UNSUPPORTED_IMAGE_PROFILE");
            }
            if(type==0x49484452 || type==0x49444154 || type==0x49454e44)neutral=Math.addExact(neutral,Math.addExact(n,12));
            in.position(Math.addExact(tail,4));
        }
        valid(end);return new Admitted("png",width,height,channels,in.rewind(),neutral);
    }
    private static Admitted jpeg(ByteBuffer in) {
        valid(in.remaining()>=4 && u(in,0)==255 && u(in,1)==216);
        int p=2,count=1,width=0,height=0,scanned=0;boolean jfif=false,frame=false,entropy=false,end=false;
        while(p<in.limit()) {
            if(entropy) {
                while(p<in.limit() && u(in,p)!=255)p++;
                valid(p<in.limit());
            }
            valid(u(in,p++)==255);while(p<in.limit() && u(in,p)==255)p++;
            valid(p<in.limit());int marker=u(in,p++);
            if(entropy && marker==0)continue;
            limit(++count<=HandToolProfile.MAX_JPEG_MARKERS);
            if(marker>=208 && marker<=215) {valid(entropy);continue;}
            entropy=false;
            if(marker==217) {valid(frame && scanned==7 && p==in.limit());end=true;break;}
            valid(marker!=0 && marker!=216 && p+2<=in.limit());
            int length=Short.toUnsignedInt(in.getShort(p));valid(length>=2 && length<=in.limit()-p);
            int b=p+2,n=length-2,next=Math.addExact(p,length);
            if(count==2)profile(marker==224);
            switch(marker) {
                case 224 -> {
                    profile(count==2 && !jfif && n==14);valid(in.getInt(b)==0x4a464946 && u(in,b+4)==0);
                    profile(u(in,b+5)==1 && u(in,b+6)<=2 && u(in,b+12)==0 && u(in,b+13)==0);
                    valid(u(in,b+7)<=2 && Short.toUnsignedInt(in.getShort(b+8))>0 && Short.toUnsignedInt(in.getShort(b+10))>0);jfif=true;
                }
                case 192 -> {
                    valid(jfif && !frame && n>=6);profile(u(in,b)==8 && u(in,b+5)==3);valid(n==15);
                    height=Short.toUnsignedInt(in.getShort(b+1));width=Short.toUnsignedInt(in.getShort(b+3));dimensions(width,height);
                    for(int i=0;i<3;i++) {
                        profile(u(in,b+6+i*3)==i+1);int sampling=u(in,b+7+i*3);
                        valid((sampling>>>4)>=1 && (sampling>>>4)<=4 && (sampling&15)>=1 && (sampling&15)<=4 && u(in,b+8+i*3)<=3);
                    }frame=true;
                }
                case 219 -> {
                    valid(jfif && n>0);int at=b;
                    while(at<next) {int info=u(in,at++);profile((info>>>4)==0);valid((info&15)<=3 && next-at>=64);
                        for(int i=0;i<64;i++)valid(u(in,at+i)>0);at+=64;}valid(at==next);
                }
                case 196 -> {
                    valid(jfif && n>0);int at=b;
                    while(at<next) {valid(next-at>=17);int info=u(in,at++);valid((info>>>4)<=1 && (info&15)<=3);
                        int symbols=0;for(int i=0;i<16;i++)symbols+=u(in,at++);valid(symbols>0 && symbols<=256 && symbols<=next-at);at+=symbols;}valid(at==next);
                }
                case 221 -> valid(jfif && n==2);
                case 218 -> {
                    valid(frame && n>=4);int components=u(in,b);valid(components>=1 && components<=3 && n==4+2*components);
                    for(int i=0;i<components;i++) {int id=u(in,b+1+2*i),table=u(in,b+2+2*i);valid(id>=1 && id<=3 && (scanned&(1<<(id-1)))==0 && (table>>>4)<=3 && (table&15)<=3);scanned|=1<<(id-1);}
                    profile(u(in,next-3)==0 && u(in,next-2)==63 && u(in,next-1)==0);entropy=true;
                }
                default -> throw new Rejected("UNSUPPORTED_IMAGE_PROFILE");
            }
            p=next;
        }
        valid(end);return new Admitted("jpeg",width,height,3,in.rewind(),in.limit());
    }
    private static int u(ByteBuffer b,int p) {return Byte.toUnsignedInt(b.get(p));}
    private static void valid(boolean ok) {if(!ok)throw new Rejected("INVALID_IMAGE");}
    private static void profile(boolean ok) {if(!ok)throw new Rejected("UNSUPPORTED_IMAGE_PROFILE");}
    private static void limit(boolean ok) {if(!ok)throw new Rejected("IMAGE_CONTAINER_LIMIT");}
    private static void dimensions(int w,int h) {if(w<=0 || h<=0 || w>HandToolProfile.MAX_IMAGE_DIMENSION || h>HandToolProfile.MAX_IMAGE_DIMENSION)throw new Rejected("IMAGE_DIMENSION_LIMIT");}
}
