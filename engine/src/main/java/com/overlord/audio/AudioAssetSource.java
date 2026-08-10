package com.overlord.audio;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.system.MemoryUtil;

@FunctionalInterface
public interface AudioAssetSource {
    static AudioAssetSource fromAssetManager(AssetManager assetManager) {
        AssetManager assets = Objects.requireNonNull(assetManager, "assetManager");
        return new AudioAssetSource() {
            private final Set<ByteBuffer> callerOwned =
                    Collections.newSetFromMap(new IdentityHashMap<>());

            @Override
            public ByteBuffer read(ResourceLocation location) {
                ResourceLocation resource = Objects.requireNonNull(location, "location");
                byte[] encoded;
                try (InputStream input = assets.open(resource)) {
                    encoded = input.readAllBytes();
                } catch (IOException failure) {
                    throw new IllegalStateException(
                            "AUDIO_ASSET_READ_FAILED resource=" + resource,
                            failure);
                }

                ByteBuffer direct = MemoryUtil.memAlloc(encoded.length);
                boolean registered = false;
                try {
                    direct.put(encoded).flip();
                    callerOwned.add(direct);
                    registered = true;
                    return direct;
                } finally {
                    if (!registered) {
                        MemoryUtil.memFree(direct);
                    }
                }
            }

            @Override
            public void release(ByteBuffer buffer) {
                ByteBuffer released = Objects.requireNonNull(buffer, "buffer");
                if (!callerOwned.remove(released)) {
                    throw new IllegalArgumentException(
                            "audio buffer is not caller-owned or was already released");
                }
                MemoryUtil.memFree(released);
            }
        };
    }

    /**
     * Reads one compressed audio resource into a caller-owned direct buffer.
     * Each successful call returns independent storage positioned for reading.
     */
    ByteBuffer read(ResourceLocation location);

    /**
     * Releases a caller-owned compressed buffer after the decoder has retained
     * any bytes it needs. GC-managed sources may use this default no-op;
     * native/custom allocators override it with their matching release policy.
     */
    default void release(ByteBuffer buffer) {}
}
