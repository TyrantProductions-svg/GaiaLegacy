package com.gaia.tools.viewer;

import com.gaia.tools.model.GaiaGlbValidator;
import com.gaia.tools.model.ValidatedModelSnapshot;
import com.gaia.tools.model.ValidationReportWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Tiny project-owned fixture bytes. Every snapshot still comes through real Gate B. */
final class ViewerFixtures {
    private ViewerFixtures() { }

    static byte[] triangle(boolean textured, boolean instances, double translation) throws Exception {
        return triangle(textured, instances, translation, 1, 1);
    }

    static byte[] triangle(boolean textured, boolean instances, double translation,
            int imageWidth, int imageHeight) throws Exception {
        byte[] png = textured ? rgbPng(imageWidth, imageHeight) : new byte[0];
        int imageOffset = 104;
        ByteBuffer bin = ByteBuffer.allocate(textured ? imageOffset + png.length : 104)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0,0,0, 1,0,0, 0,1,0, 0,0,1, 0,0,1, 0,0,1}) bin.putFloat(value);
        bin.putShort((short)0).putShort((short)1).putShort((short)2);
        bin.position(80);
        for (float value : new float[]{0,0, 1,0, 0,1}) bin.putFloat(value);
        if (textured) bin.position(imageOffset).put(png);
        String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":%s,
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1%s},"indices":2,"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[0.25,0.5,0.75,1],"metallicFactor":0,"roughnessFactor":0.8%s}}],
                 "buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                                {"buffer":0,"byteOffset":36,"byteLength":36},
                                {"buffer":0,"byteOffset":72,"byteLength":6},
                                {"buffer":0,"byteOffset":80,"byteLength":24}%s],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"},
                              {"bufferView":3,"componentType":5126,"count":3,"type":"VEC2"}]%s}
                """.formatted(instances
                        ? "[{\"name\":\"GAIA_ASSET_ROOT\",\"translation\":["+translation+",0,0],\"children\":[1,2]},{\"mesh\":0},{\"mesh\":0,\"translation\":[0,2,0]}]"
                        : "[{\"name\":\"GAIA_ASSET_ROOT\",\"mesh\":0,\"translation\":["+translation+",0,0]}]",
                        textured ? ",\"TEXCOORD_0\":3" : "",
                        textured ? ",\"baseColorTexture\":{\"index\":0}" : "", bin.capacity(),
                        textured ? ",{\"buffer\":0,\"byteOffset\":"+imageOffset+",\"byteLength\":"+png.length+"}" : "",
                        textured ? ",\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0,\"sampler\":0},{\"source\":0}],\"samplers\":[{\"magFilter\":9728,\"minFilter\":9728,\"wrapS\":33071,\"wrapT\":33648}]" : "");
        return container(json, bin.array());
    }

    static GaiaGlbValidator.Result result(byte[] bytes) throws Exception {
        return GaiaGlbValidator.validate(new ByteArrayInputStream(bytes));
    }

    static ValidatedModelSnapshot snapshot(boolean textured, boolean instances) throws Exception {
        var result = result(triangle(textured, instances, 3));
        if (result.snapshot().isEmpty()) throw new AssertionError(ValidationReportWriter.text(result));
        return result.snapshot().orElseThrow();
    }

    static byte[] invalid() throws Exception {
        return container("{\"asset\":{\"version\":\"2.0\"}}", new byte[0]);
    }

    static ValidatedModelSnapshot twoPrimitives() throws Exception {
        byte[] bytes = triangle(false, true, 3);
        ByteBuffer source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = source.getInt(12);
        ObjectNode json = (ObjectNode)new ObjectMapper().readTree(Arrays.copyOfRange(bytes,20,20+jsonLength));
        var primitives = (com.fasterxml.jackson.databind.node.ArrayNode)json.at("/meshes/0/primitives");
        primitives.add(((ObjectNode)primitives.get(0)).deepCopy().put("material",1));
        json.withArray("materials").addObject().putObject("pbrMetallicRoughness")
                .putArray("baseColorFactor").add(1).add(0).add(0).add(1);
        var result = result(container(json.toString(),Arrays.copyOfRange(bytes,28+jsonLength,bytes.length)));
        if (result.snapshot().isEmpty()) throw new AssertionError(ValidationReportWriter.text(result));
        return result.snapshot().orElseThrow();
    }

    static GaiaGlbValidator.Result invalidScaleResult() throws Exception {
        byte[] bytes=triangle(false,false,3);
        ByteBuffer source=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength=source.getInt(12);
        ObjectNode json=(ObjectNode)new ObjectMapper().readTree(Arrays.copyOfRange(bytes,20,20+jsonLength));
        ((ObjectNode)json.withArray("nodes").get(0)).putArray("scale").add(2).add(2).add(2);
        return result(container(json.toString(),Arrays.copyOfRange(bytes,28+jsonLength,bytes.length)));
    }

    static GaiaGlbValidator.Result textureBudgetResult(int textureCount,int samplerCount) throws Exception {
        byte[] bytes=triangle(true,false,0);
        ByteBuffer source=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength=source.getInt(12);
        ObjectNode json=(ObjectNode)new ObjectMapper().readTree(Arrays.copyOfRange(bytes,20,20+jsonLength));
        var samplers=json.putArray("samplers");
        for(int i=0;i<samplerCount;i++) samplers.addObject();
        var textures=json.putArray("textures");
        for(int i=0;i<textureCount;i++) {
            var texture=textures.addObject().put("source",0);
            if(samplerCount>0) texture.put("sampler",Math.min(i,samplerCount-1));
        }
        ((ObjectNode)json.at("/materials/0/pbrMetallicRoughness/baseColorTexture")).put("index",0);
        return result(container(json.toString(),Arrays.copyOfRange(bytes,28+jsonLength,bytes.length)));
    }

    private static byte[] container(String json, byte[] bin) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        byte[] paddedText = Arrays.copyOf(text, (text.length + 3) & ~3);
        Arrays.fill(paddedText, text.length, paddedText.length, (byte)' ');
        byte[] paddedBin = Arrays.copyOf(bin, (bin.length + 3) & ~3);
        int size = 28 + paddedText.length + paddedBin.length;
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546c67).putInt(2).putInt(size)
                .putInt(paddedText.length).putInt(0x4e4f534a).put(paddedText)
                .putInt(paddedBin.length).putInt(0x004e4942).put(paddedBin).array();
    }

    private static byte[] rgbPng(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(output,"IHDR",ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(new byte[]{8,2,0,0,0}).array());
        byte[] samples = new byte[Math.multiplyExact(height, Math.addExact(1,
                Math.multiplyExact(width, 3)))];
        int cursor = 0;
        for (int y = 0; y < height; y++) {
            samples[cursor++] = 0;
            for (int x = 0; x < width; x++) {
                samples[cursor++] = 17;
                samples[cursor++] = 85;
                samples[cursor++] = (byte)204;
            }
        }
        var compressed = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(compressed)) { deflate.write(samples); }
        chunk(output,"IDAT",compressed.toByteArray());
        chunk(output,"IEND",new byte[0]);
        return output.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream output, String type, byte[] data) throws Exception {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var writer = new DataOutputStream(output);
        writer.writeInt(data.length); writer.write(name); writer.write(data);
        var crc = new CRC32(); crc.update(name); crc.update(data); writer.writeInt((int)crc.getValue());
    }
}
