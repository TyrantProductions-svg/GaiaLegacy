package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.GlTF;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Format admission and all image budgets precede any JDK reader invocation. */
final class EmbeddedImages {
    @FunctionalInterface interface PixelDecoder {BufferedImage read(ImageReader reader) throws IOException;}
    static final class PixelBudget {
        private long bytes;
        private int count;
        int reserve(long width,long height) {
            require(width>0 && height>0 && width<=HandToolProfile.MAX_IMAGE_DIMENSION && height<=HandToolProfile.MAX_IMAGE_DIMENSION);
            long size=Math.multiplyExact(Math.multiplyExact(width,height),4);
            long total=Math.addExact(bytes,size);
            require(total<=HandToolProfile.MAX_RGBA_BYTES && count<HandToolProfile.MAX_IMAGES);
            bytes=total;count++;return Math.toIntExact(size);
        }
        long bytes() {return bytes;}
    }
    private record Pending(int index,ImageContainers.Admitted container,int bytes) { }
    static List<ValidatedModelSnapshot.Image> decode(GlTF doc,BufferAccess data,ValidationReport.Collector log) {
        return decode(doc,data,log,reader->reader.read(0));
    }
    static List<ValidatedModelSnapshot.Image> decode(GlTF doc,BufferAccess data,ValidationReport.Collector log,PixelDecoder decoder) {
        var images=doc.getImages();if(images==null)return List.of();
        if(images.size()>HandToolProfile.MAX_IMAGES) {log.error("IMAGE_LIMIT","/images","Image count exceeded");return List.of();}
        var pending=new ArrayList<Pending>();var result=new ArrayList<ValidatedModelSnapshot.Image>();var budget=new PixelBudget();
        for(int i=0;i<images.size();i++) {
            String path="/images/"+i;
            try {
                var image=images.get(i);require(image!=null && image.getBufferView()!=null);
                var view=data.viewDefinition(image.getBufferView());require(view.getByteStride()==null && view.getTarget()==null);
                var admitted=ImageContainers.admit(data.view(image.getBufferView()),image.getMimeType());
                int bytes;
                try {bytes=budget.reserve(admitted.width(),admitted.height());}
                catch(IllegalArgumentException|ArithmeticException reject) {log.error("IMAGE_DIMENSION_LIMIT",path,"Image dimensions or aggregate RGBA budget exceeded");continue;}
                pending.add(new Pending(i,admitted,bytes));
            } catch(ImageContainers.Rejected reject) {log.error(reject.code,path,"Embedded image container/profile rejected");}
            catch(IllegalArgumentException|NullPointerException reject) {log.error("INVALID_IMAGE",path,"Invalid embedded image reference");}
        }
        if(log.report().outcome()==ValidationReport.Outcome.FAIL)return List.of();
        // Each declaration is processed once. Texture/material references never drive decode.
        for(var item:pending) {
            ImageReader reader=null;
            try(var stream=new MemoryCacheImageInputStream(new BufferStream(item.container.decodeBytes()))) {
                reader=jdkReader(item.container.format());boolean[] warning={false};
                reader.addIIOReadWarningListener((source,message)->warning[0]=true);
                reader.setInput(stream,true,true);
                BufferedImage image=decoder.read(reader);
                require(image!=null && !warning[0] && image.getWidth()==item.container.width() && image.getHeight()==item.container.height());
                var model=image.getColorModel();var raster=image.getRaster();
                require(model instanceof ComponentColorModel && model.getColorSpace().isCS_sRGB()
                        && !model.isAlphaPremultiplied() && model.getTransferType()==DataBuffer.TYPE_BYTE
                        && model.getNumColorComponents()==3 && model.getNumComponents()==item.container.channels()
                        && raster.getNumBands()==item.container.channels());
                for(int bits:raster.getSampleModel().getSampleSize())require(bits==8);
                byte[] rgba=new byte[item.bytes];int at=0;
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                    rgba[at++]=(byte)raster.getSample(x,y,0);rgba[at++]=(byte)raster.getSample(x,y,1);
                    rgba[at++]=(byte)raster.getSample(x,y,2);
                    rgba[at++]=item.container.channels()==4?(byte)raster.getSample(x,y,3):(byte)255;
                }
                result.add(new ValidatedModelSnapshot.Image(image.getWidth(),image.getHeight(),rgba));
            }catch(IOException|IllegalArgumentException reject) {log.error("INVALID_IMAGE","/images/"+item.index,"Embedded pixel decode failed");}
            finally {if(reader!=null)reader.dispose();}
        }
        return List.copyOf(result);
    }
    private static ImageReader jdkReader(String format) {
        var readers=ImageIO.getImageReadersByFormatName(format);
        String name=format.equals("png")?"com.sun.imageio.plugins.png.PNGImageReader":"com.sun.imageio.plugins.jpeg.JPEGImageReader";
        while(readers.hasNext()) {var reader=readers.next();if(reader.getClass().getName().equals(name))return reader;reader.dispose();}
        throw new IllegalArgumentException("Supported JDK image reader unavailable");
    }
    private static final class BufferStream extends InputStream {
        private final ByteBuffer bytes;
        BufferStream(ByteBuffer bytes) {this.bytes=bytes.asReadOnlyBuffer();}
        @Override public int read() {return bytes.hasRemaining()?Byte.toUnsignedInt(bytes.get()):-1;}
        @Override public int read(byte[] target,int offset,int length) {
            java.util.Objects.checkFromIndexSize(offset,length,target.length);
            if(length==0)return 0;if(!bytes.hasRemaining())return -1;
            int count=Math.min(length,bytes.remaining());bytes.get(target,offset,count);return count;
        }
    }
    private static void require(boolean value) {if(!value)throw new IllegalArgumentException("INVALID_IMAGE");}
}
