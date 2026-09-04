package com.gaia.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Tiny project-owned fixtures, built in memory; no downloaded models or exports. */
final class GlbFixtures {
    static final int JSON = 0x4E4F534A;
    static final int BIN = 0x004E4942;
    static final String MINIMAL = "{\"asset\":{\"version\":\"2.0\"}}";

    private GlbFixtures() { }

    static byte[] glb(String json) {
        return container(chunk(JSON, json.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] chunk(int type, byte[] data) {
        int padded = (data.length + 3) & ~3;
        byte[] content = Arrays.copyOf(data, padded);
        if (type == JSON) {
            Arrays.fill(content, data.length, padded, (byte) ' ');
        }
        return ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(padded).putInt(type).put(content).array();
    }

    static byte[] container(byte[]... chunks) {
        int size = 12;
        for (byte[] chunk : chunks) { size += chunk.length; }
        ByteBuffer output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(size);
        for (byte[] chunk : chunks) { output.put(chunk); }
        return output.array();
    }

    static byte[] withInt(byte[] data, int offset, int value) {
        byte[] copy = data.clone();
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
        return copy;
    }

    static byte[] triangle() {
        String json = """
                {"asset":{"version":"2.0","generator":"Gaia project-owned admission fixture"},
                 "scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"name":"GAIA_ASSET_ROOT","mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"mode":4}]}],
                 "buffers":[{"byteLength":78}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
                                {"buffer":0,"byteOffset":36,"byteLength":36,"target":34962},
                                {"buffer":0,"byteOffset":72,"byteLength":6,"target":34963}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}]}
                """;
        ByteBuffer binary = ByteBuffer.allocate(78).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0,0,0, 1,0,0, 0,1,0, 0,0,1, 0,0,1, 0,0,1}) {
            binary.putFloat(value);
        }
        binary.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        return container(chunk(JSON, json.getBytes(StandardCharsets.UTF_8)), chunk(BIN, binary.array()));
    }
}
