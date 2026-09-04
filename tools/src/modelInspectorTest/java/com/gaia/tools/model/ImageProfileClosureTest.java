package com.gaia.tools.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.gaia.tools.model.PrimitiveAttributesTest.failure;

class ImageProfileClosureTest {
    static GaiaGlbValidator.Result run(byte[] image,String mime,AtomicInteger calls) throws Exception {
        return GaiaGlbValidator.validate(new ByteArrayInputStream(ImageFixtures.model(image,mime).bytes()),reader->{calls.incrementAndGet();return reader.read(0);});
    }
    static void rejected(byte[] image,String mime,String code) throws Exception {
        var calls=new AtomicInteger();var r=run(image,mime,calls);
        failure(r,code,"/images/0");assertEquals(0,calls.get(),"Rejected container reached decoder");
    }
    @ParameterizedTest @ValueSource(ints={2,6})
    void rgbAndRgbaPass(int color) throws Exception {
        var calls=new AtomicInteger();var r=run(ImageFixtures.png(8,color,0,1,new byte[0]),"image/png",calls);
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(ints={0,3,4})
    void unsupportedPngColorTypesNeverReachDecoder(int color) throws Exception {
        rejected(ImageFixtures.png(8,color,0,1,new byte[0]),"image/png","UNSUPPORTED_IMAGE_PROFILE");
    }
    @Test void interlaceAndSixteenBitFailBeforeDecode() throws Exception {
        rejected(ImageFixtures.png(8,2,1,1,new byte[0]),"image/png","UNSUPPORTED_IMAGE_PROFILE");
        rejected(ImageFixtures.png(16,2,0,1,new byte[0]),"image/png","UNSUPPORTED_IMAGE_PROFILE");
    }
    @ParameterizedTest @ValueSource(strings={"iCCP","zTXt","iTXt","tEXt","eXIf","cICP","tRNS","raNd"})
    void unsupportedMetadataNeverReachesDecoder(String name) throws Exception {
        rejected(ImageFixtures.png(8,2,0,1,ImageFixtures.chunk(name,new byte[]{0})),"image/png","UNSUPPORTED_IMAGE_PROFILE");
    }
    @Test void formerTenThousandEntryAttackIsRejectedThroughFullValidator() throws Exception {
        var metadata=new ByteArrayOutputStream();for(int i=0;i<10000;i++)metadata.write(ImageFixtures.chunk("tEXt",new byte[]{65,0,66}));
        rejected(ImageFixtures.png(8,2,0,1,metadata.toByteArray()),"image/png","UNSUPPORTED_IMAGE_PROFILE");
        rejected(ImageFixtures.png(8,3,0,1,metadata.toByteArray()),"image/png","UNSUPPORTED_IMAGE_PROFILE");
    }
    @Test void pngChunkBoundaryIsInclusiveAndCheckedBeforeDecode() throws Exception {
        var calls=new AtomicInteger();assertEquals(ValidationReport.Outcome.PASS,run(ImageFixtures.png(8,2,0,254,new byte[0]),"image/png",calls).report().outcome());
        assertEquals(1,calls.get());rejected(ImageFixtures.png(8,2,0,255,new byte[0]),"image/png","IMAGE_CONTAINER_LIMIT");
    }
    @Test void crcAndChunkRangeFailDeterministicallyBeforeDecode() throws Exception {
        byte[] crc=ImageFixtures.rgb();crc[29]^=1;rejected(crc,"image/png","INVALID_IMAGE");
        byte[] length=ImageFixtures.rgb();length[8]=127;rejected(length,"image/png","INVALID_IMAGE");
    }
    @Test void baselineJfifPasses() throws Exception {
        var calls=new AtomicInteger();assertEquals(ValidationReport.Outcome.PASS,run(ImageFixtures.jpeg(false,false),"image/jpeg",calls).report().outcome());assertEquals(1,calls.get());
    }
    @Test void progressiveAndGrayscaleJpegFailBeforeDecode() throws Exception {
        rejected(ImageFixtures.jpeg(true,false),"image/jpeg","UNSUPPORTED_IMAGE_PROFILE");
        rejected(ImageFixtures.jpeg(false,true),"image/jpeg","UNSUPPORTED_IMAGE_PROFILE");
    }
    @ParameterizedTest @ValueSource(ints={225,226,238,254,227})
    void jpegAppAndCommentMetadataNeverReachDecoder(int marker) throws Exception {
        rejected(ImageFixtures.jpegExtra(marker,new byte[]{65,0}),"image/jpeg","UNSUPPORTED_IMAGE_PROFILE");
    }
    @Test void malformedJpegSegmentLengthNeverReachesDecoder() throws Exception {
        byte[] jpeg=ImageFixtures.jpeg(false,false);jpeg[4]=0;jpeg[5]=1;rejected(jpeg,"image/jpeg","INVALID_IMAGE");
    }
    @Test void oneDeclaredImageSharedByTexturesDecodesAndChargesOnlyOnce() throws Exception {
        var f=new SemanticFixtures();f.uv();byte[] maximum=EmbeddedImagesTest.image("png",1024,1024);
        for(int i=0;i<4;i++)f.image(maximum,"image/png");
        var textures=f.json.putArray("textures");for(int i=0;i<3;i++)textures.addObject().put("source",0);
        var calls=new AtomicInteger();
        var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->{calls.incrementAndGet();return reader.read(0);});
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());assertEquals(4,calls.get());
        assertEquals(4,r.snapshot().orElseThrow().images().size());
        assertEquals(16777216,r.snapshot().orElseThrow().images().stream().mapToLong(i->i.rgba().length).sum());
        for(var texture:r.snapshot().orElseThrow().textures())assertEquals(0,texture.image());
    }
    @Test void identicalDeclaredImagesRemainSeparateUnits() throws Exception {
        var f=new SemanticFixtures();byte[] png=ImageFixtures.rgb();f.image(png,"image/png");f.image(png,"image/png");
        var calls=new AtomicInteger();var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->{calls.incrementAndGet();return reader.read(0);});
        assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());assertEquals(2,calls.get());
        assertEquals(2,r.snapshot().orElseThrow().images().size());assertEquals(8,r.snapshot().orElseThrow().images().stream().mapToLong(i->i.rgba().length).sum());
    }
    @Test void laterRejectedImagePreventsAllDecode() throws Exception {
        var f=ImageFixtures.model(ImageFixtures.rgb(),"image/png");f.image(ImageFixtures.png(8,3,0,1,new byte[0]),"image/png");
        var r=GaiaGlbValidator.validate(new ByteArrayInputStream(f.bytes()),reader->{fail("Aggregate admission not complete");return null;});
        assertEquals(ValidationReport.Outcome.FAIL,r.report().outcome());assertTrue(r.snapshot().isEmpty());
    }
    @Test void jpegMarkerCountIncludesHeaderMarkersAndEoi() throws Exception {
        byte[] base=ImageFixtures.jpeg(false,false);int offset=2,baseCount=2;
        // Fixture is one scan without restarts: count SOI/EOI plus pre-scan markers.
        while(true) {int marker=Byte.toUnsignedInt(base[offset+1]);baseCount++;if(marker==218)break;
            offset+=2+Short.toUnsignedInt(java.nio.ByteBuffer.wrap(base).getShort(offset+2));}
        int insert=4+Short.toUnsignedInt(java.nio.ByteBuffer.wrap(base).getShort(4));
        for(int extra:new int[]{256-baseCount,257-baseCount}) {
            var out=new ByteArrayOutputStream();out.write(base,0,insert);
            for(int i=0;i<extra;i++)out.write(ImageFixtures.segment(221,new byte[2]));
            out.write(base,insert,base.length-insert);
            if(extra+baseCount==256)assertEquals(ValidationReport.Outcome.PASS,run(out.toByteArray(),"image/jpeg",new AtomicInteger()).report().outcome());
            else rejected(out.toByteArray(),"image/jpeg","IMAGE_CONTAINER_LIMIT");
        }
    }
    @Test void cmykFrameCannotReachGenericDecoder() throws Exception {
        byte[] base=ImageFixtures.jpeg(false,false);int p=2;
        while(Byte.toUnsignedInt(base[p+1])!=192)p+=2+Short.toUnsignedInt(java.nio.ByteBuffer.wrap(base).getShort(p+2));
        base[p+9]=4;rejected(base,"image/jpeg","UNSUPPORTED_IMAGE_PROFILE");
    }
    @Test void duplicateAndPostDataColorDeclarationsFailBeforeDecode() throws Exception {
        byte[] srgb=ImageFixtures.chunk("sRGB",new byte[]{0});var twice=new ByteArrayOutputStream();twice.write(srgb);twice.write(srgb);
        rejected(ImageFixtures.png(8,2,0,1,twice.toByteArray()),"image/png","INVALID_IMAGE");
        byte[] base=ImageFixtures.rgb();var late=new ByteArrayOutputStream();late.write(base,0,base.length-12);late.write(srgb);late.write(base,base.length-12,12);
        rejected(late.toByteArray(),"image/png","INVALID_IMAGE");
    }
}
