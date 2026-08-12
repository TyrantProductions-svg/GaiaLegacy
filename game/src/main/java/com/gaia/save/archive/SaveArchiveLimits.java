package com.gaia.save.archive;

import com.overlord.config.GameConfig;

/** Checked structural byte/count bounds for the finite radius-8 v1 format. */
public final class SaveArchiveLimits {
    public static final int MAX_ENTRY_COUNT = 32;
    public static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    public static final long MAX_PLAYER_BYTES = 16L * 1024L;
    public static final long MAX_INVENTORY_BYTES = 64L * 1024L;
    public static final long MAX_WORLD_ITEMS_BYTES = 1024L * 1024L;
    public static final long MAX_OPTIONAL_BYTES = 1024L * 1024L;
    public static final long MAX_CHUNKS_BYTES = computeMaxChunksBytes();
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = computeMaxTotalBytes();
    public static final long MAX_ARCHIVE_FILE_BYTES = Math.addExact(
            MAX_TOTAL_UNCOMPRESSED_BYTES, 4L * 1024L * 1024L);

    public long maxBytesFor(String entryName) {
        return switch (entryName) {
            case "manifest.json" -> MAX_MANIFEST_BYTES;
            case "chunks.bin" -> MAX_CHUNKS_BYTES;
            case "player.json" -> MAX_PLAYER_BYTES;
            case "inventory.json" -> MAX_INVENTORY_BYTES;
            case "world-items.json" -> MAX_WORLD_ITEMS_BYTES;
            default -> MAX_OPTIONAL_BYTES;
        };
    }

    private static long computeMaxChunksBytes() {
        long diameter = Math.addExact(Math.multiplyExact(8L, 2L), 1L);
        long chunks = Math.multiplyExact(diameter, diameter);
        long blocks = Math.multiplyExact(
                Math.multiplyExact((long) GameConfig.Chunk.SIZE,
                        (long) GameConfig.Chunk.MAX_HEIGHT),
                (long) GameConfig.Chunk.SIZE);
        long perChunk = Math.addExact(20L, blocks);
        return Math.addExact(24L, Math.multiplyExact(chunks, perChunk));
    }

    private static long computeMaxTotalBytes() {
        long required = Math.addExact(
                Math.addExact(MAX_MANIFEST_BYTES, MAX_CHUNKS_BYTES),
                Math.addExact(
                        Math.addExact(MAX_PLAYER_BYTES, MAX_INVENTORY_BYTES),
                        MAX_WORLD_ITEMS_BYTES));
        long optionalBudget = Math.multiplyExact(
                (long) (MAX_ENTRY_COUNT - 5), MAX_OPTIONAL_BYTES);
        return Math.addExact(required, optionalBudget);
    }
}
