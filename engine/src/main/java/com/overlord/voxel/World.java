package com.overlord.voxel;

import java.util.Objects;
import java.util.function.Consumer;

public final class World {
    private final ChunkRepository chunks;

    public World() {
        this(new ChunkRepository());
    }

    public World(ChunkRepository chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    public ChunkRepository chunks() {
        return chunks;
    }

    public byte getBlock(int x, int y, int z) {
        return chunks.getBlock(x, y, z);
    }

    public boolean setBlock(int x, int y, int z, byte blockId) {
        return chunks.setBlock(x, y, z, blockId);
    }

    public void generate(
            ChunkKey key, Consumer<Chunk> generator) {
        chunks.generate(key, generator);
    }
}
