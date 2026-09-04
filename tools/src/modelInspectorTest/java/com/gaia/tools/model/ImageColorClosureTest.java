package com.gaia.tools.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageColorClosureTest {
    @Test void pngSamplesAreExactAndNeverUseColorManagedGetRgb() throws Exception {
        for(int color:new int[]{2,6}) {
            var f=ImageFixtures.model(ImageFixtures.png(8,color,0,1,new byte[0]),"image/png");
            var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->{
                var decoded=reader.read(0);
                return new BufferedImage(decoded.getColorModel(),decoded.getRaster(),false,null) {
                    @Override public int getRGB(int x,int y) {throw new AssertionError("Color-managed getRGB used");}
                };
            });
            assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
            assertArrayEquals(new byte[]{64,96,(byte)128,(byte)(color==2?255:7)},r.snapshot().orElseThrow().images().get(0).rgba());
        }
    }
    @Test void unexpectedDecoderColorModelCannotPublishSnapshot() throws Exception {
        var f=ImageFixtures.model(ImageFixtures.rgb(),"image/png");
        var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->new BufferedImage(1,1,BufferedImage.TYPE_BYTE_GRAY));
        assertEquals(ValidationReport.Outcome.FAIL,r.report().outcome());assertTrue(r.snapshot().isEmpty());
    }
    @Test void acceptedColorDeclarationsAreNotPassedToDecoder() throws Exception {
        var declarations=new ByteArrayOutputStream();declarations.write(ImageFixtures.chunk("sRGB",new byte[]{0}));
        declarations.write(ImageFixtures.chunk("gAMA",ByteBuffer.allocate(4).putInt(45455).array()));
        var chroma=ByteBuffer.allocate(32);for(int v:new int[]{31270,32900,64000,33000,30000,60000,15000,6000})chroma.putInt(v);
        declarations.write(ImageFixtures.chunk("cHRM",chroma.array()));
        var f=ImageFixtures.model(ImageFixtures.png(8,2,0,1,declarations.toByteArray()),"image/png");
        var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->{
            var stream=(ImageInputStream)reader.getInput();stream.seek(8);
            assertEquals(13,stream.readInt());assertEquals(0x49484452,stream.readInt());stream.skipBytes(17);
            int data=stream.readInt();assertEquals(0x49444154,stream.readInt());stream.skipBytes(data+4);
            assertEquals(0,stream.readInt());assertEquals(0x49454e44,stream.readInt());stream.seek(0);return reader.read(0);
        });
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
        assertArrayEquals(new byte[]{64,96,(byte)128,(byte)255},r.snapshot().orElseThrow().images().get(0).rgba());
    }
    @Test void nonSrgbGammaAndChromaticityAreUnsupported() throws Exception {
        for(String name:new String[]{"gAMA","cHRM"}) {
            byte[] value=name.equals("gAMA")?ByteBuffer.allocate(4).putInt(100000).array():new byte[32];
            ImageProfileClosureTest.rejected(ImageFixtures.png(8,2,0,1,ImageFixtures.chunk(name,value)),"image/png","UNSUPPORTED_IMAGE_PROFILE");
        }
    }
    @Test void baselineJfifKnownPixelsAreStableAcrossRepeatedDecode() throws Exception {
        byte[] jpeg=ImageFixtures.jpeg(false,false);
        for(int i=0;i<3;i++) {
            var r=ImageProfileClosureTest.run(jpeg,"image/jpeg",new AtomicInteger());
            assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
            byte[] expected={63,96,127,(byte)255,63,96,127,(byte)255,63,96,127,(byte)255,63,96,127,(byte)255};
            assertArrayEquals(expected,r.snapshot().orElseThrow().images().get(0).rgba());
        }
    }
}
