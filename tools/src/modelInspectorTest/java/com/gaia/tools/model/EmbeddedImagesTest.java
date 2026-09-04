package com.gaia.tools.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.gaia.tools.model.PrimitiveAttributesTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedImagesTest {
    static byte[] image(String format,int width,int height) throws Exception {
        var raster=new BufferedImage(width,height,format.equals("png")?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        raster.setRGB(0,0,0xff406080);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(raster,format,output));return output.toByteArray();
    }
    static SemanticFixtures textured(String format,Integer texCoord) throws Exception {
        var f=new SemanticFixtures();f.uv();f.image(image(format,2,2),"image/"+format);
        f.json.putArray("textures").addObject().put("source",0);
        var info=MaterialTextureChecksTest.material(f).putObject("pbrMetallicRoughness").putObject("baseColorTexture").put("index",0);
        if(texCoord!=null)info.put("texCoord",texCoord);return f;
    }
    @ParameterizedTest @ValueSource(strings={"png","jpeg"})
    void validEmbeddedTextureUsesDefaultOrExplicitUvZero(String format) throws Exception {
        for(Integer set:new Integer[]{null,0}) {
            var r=validate(textured(format,set));assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());
            var snapshot=r.snapshot().orElseThrow();var image=snapshot.images().get(0);
            assertEquals(2,image.width());assertEquals(2,image.height());assertEquals(16,image.rgba().length);
            assertEquals(0,snapshot.materials().get(0).baseColorTexture());assertEquals(0,snapshot.textures().get(0).image());
        }
    }
    @Test void mimeContentMismatchAndCorruptStreamFailWithoutSnapshot() throws Exception {
        for(byte[] encoded:new byte[][]{image("jpeg",1,1),new byte[]{1,2,3},Arrays.copyOf(image("png",2,2),35)}) {
            var f=new SemanticFixtures();f.image(encoded,"image/png");
            assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());assertTrue(validate(f).snapshot().isEmpty());
        }
    }
    @Test void unsupportedFormatBadViewAndImageViewStrideFail() throws Exception {
        var f=new SemanticFixtures();f.image(new byte[]{71,73,70,56,57,97},"image/gif");
        assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
        var b=textured("png",0);((com.fasterxml.jackson.databind.node.ObjectNode)b.json.at("/images/0")).put("bufferView",999);
        assertEquals(ValidationReport.Outcome.FAIL,validate(b).report().outcome());
        var s=textured("png",0);((com.fasterxml.jackson.databind.node.ObjectNode)s.json.at("/bufferViews/4")).put("byteStride",4);
        assertEquals(ValidationReport.Outcome.FAIL,validate(s).report().outcome());
    }
    @Test void oversizedPngHeaderIsRejectedBeforePixelDecoderEntry() throws Exception {
        byte[] encoded=image("png",1,1);
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(16,1025);
        var crc=new CRC32();crc.update(encoded,12,17);ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(29,(int)crc.getValue());
        var f=new SemanticFixtures();f.image(encoded,"image/png");
        var a=GlbAdmission.admit(new ByteArrayInputStream(f.bytes()));var log=new ValidationReport.Collector();
        EmbeddedImages.decode(a.document(),new BufferAccess(a),log,(reader)->{fail("Oversized metadata reached pixel decode");return null;});
        assertEquals(ValidationReport.Outcome.FAIL,log.report().outcome());
        failure(validate(f),"IMAGE_DIMENSION_LIMIT","/images/0");
    }
    @Test void exactImageBudgetAndOneOverNeverWrap() {
        var b=new EmbeddedImages.PixelBudget();
        for(int i=0;i<4;i++)assertEquals(4194304,b.reserve(1024,1024));
        assertEquals(16777216,b.bytes());
        assertThrows(IllegalArgumentException.class,()->b.reserve(1,1));
        assertThrows(IllegalArgumentException.class,()->new EmbeddedImages.PixelBudget().reserve(Long.MAX_VALUE,2));
        assertThrows(IllegalArgumentException.class,()->new EmbeddedImages.PixelBudget().reserve(0,1));
    }
    @Test void fourMaximumImagesAreAdmittedBeforeAnyDecodeAndFifthFails() throws Exception {
        var f=new SemanticFixtures();byte[] png=image("png",1024,1024);
        for(int i=0;i<4;i++)f.image(png,"image/png");
        var r=validate(f);assertEquals(ValidationReport.Outcome.PASS,r.report().outcome());assertEquals(4,r.snapshot().orElseThrow().images().size());
        f.image(image("png",1,1),"image/png");failure(validate(f),"IMAGE_LIMIT","/images");
    }
    @Test void rgbaAndSamplingAreOwnedAndFaithful() throws Exception {
        var f=textured("png",0);f.json.putArray("samplers").addObject().put("minFilter",9987).put("magFilter",9728).put("wrapS",33071).put("wrapT",33648);
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.json.at("/textures/0")).put("sampler",0);
        var s=validate(f).snapshot().orElseThrow();byte[] rgba=s.images().get(0).rgba();
        assertArrayEquals(new byte[]{64,96,(byte)128,(byte)255},Arrays.copyOf(rgba,4));rgba[0]=0;
        assertEquals(64,s.images().get(0).rgba()[0]);assertEquals(9987,s.textures().get(0).minFilter());assertEquals(33071,s.textures().get(0).wrapS());
    }
    @Test void pngCrcAndRequiredEndChunkAreNotSilentlyIgnored() throws Exception {
        byte[] png=image("png",2,2);
        for(byte[] corrupt:new byte[][]{png.clone(),Arrays.copyOf(png,png.length-12)}) {
            if(corrupt.length==png.length)corrupt[29]^=1;
            var f=new SemanticFixtures();f.image(corrupt,"image/png");
            assertEquals(ValidationReport.Outcome.FAIL,validate(f).report().outcome());
        }
    }
}
