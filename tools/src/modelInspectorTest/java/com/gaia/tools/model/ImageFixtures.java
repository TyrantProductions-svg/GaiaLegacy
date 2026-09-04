package com.gaia.tools.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Small owned containers; no external images or OOM fixtures. */
final class ImageFixtures {
    static byte[] chunk(String type,byte[] data) throws Exception {
        var out=new ByteArrayOutputStream();var d=new DataOutputStream(out);
        byte[] name=type.getBytes(StandardCharsets.US_ASCII);d.writeInt(data.length);d.write(name);d.write(data);
        var crc=new CRC32();crc.update(name);crc.update(data);d.writeInt((int)crc.getValue());return out.toByteArray();
    }
    static byte[] png(int depth,int color,int interlace,int idats,byte[] metadata) throws Exception {
        var out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        var header=ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte)depth).put((byte)color).put((byte)0).put((byte)0).put((byte)interlace).array();
        out.write(chunk("IHDR",header));
        if(color==3)out.write(chunk("PLTE",new byte[]{64,96,(byte)128}));
        out.write(metadata);
        var compressed=new ByteArrayOutputStream();
        try(var zip=new DeflaterOutputStream(compressed)) {
            zip.write(0);zip.write(color==6?new byte[]{64,96,(byte)128,7}:color==2?new byte[]{64,96,(byte)128}:new byte[]{0});
        }
        for(int i=1;i<idats;i++)out.write(chunk("IDAT",new byte[0]));
        out.write(chunk("IDAT",compressed.toByteArray()));out.write(chunk("IEND",new byte[0]));return out.toByteArray();
    }
    static byte[] rgb() throws Exception {return png(8,2,0,1,new byte[0]);}
    static byte[] jpeg(boolean progressive,boolean gray) throws Exception {
        var image=new BufferedImage(2,2,gray?BufferedImage.TYPE_BYTE_GRAY:BufferedImage.TYPE_3BYTE_BGR);
        for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.setRGB(x,y,0xff406080);
        var writer=ImageIO.getImageWritersByFormatName("jpeg").next();var out=new ByteArrayOutputStream();
        try(var stream=new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);var param=writer.getDefaultWriteParam();
            if(progressive)param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new IIOImage(image,null,null),param);
        } finally {writer.dispose();}return out.toByteArray();
    }
    static byte[] segment(int marker,byte[] payload) throws Exception {
        var out=new ByteArrayOutputStream();var d=new DataOutputStream(out);d.writeByte(255);d.writeByte(marker);d.writeShort(payload.length+2);d.write(payload);return out.toByteArray();
    }
    static byte[] jpegExtra(int marker,byte[] payload) throws Exception {
        byte[] base=jpeg(false,false);int after=4+Short.toUnsignedInt(ByteBuffer.wrap(base).getShort(4));
        var out=new ByteArrayOutputStream();out.write(base,0,after);out.write(segment(marker,payload));out.write(base,after,base.length-after);return out.toByteArray();
    }
    static SemanticFixtures model(byte[] bytes,String mime) throws Exception {var f=new SemanticFixtures();f.image(bytes,mime);return f;}
}
