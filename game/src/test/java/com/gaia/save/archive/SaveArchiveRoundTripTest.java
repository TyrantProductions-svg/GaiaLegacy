package com.gaia.save.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveArchiveRoundTripTest {
    private static final int WORLD_HEIGHT = 16;
    private static final int CHUNK_RADIUS = 4;
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-08-10T12:05:00Z");
    private static final EntityRef OWNER = new EntityRef(7);

    @TempDir Path tempDir;

    @Test
    void writesManifestFirstAndRequiredSectionsInCanonicalOrderAndRoundTrips()
            throws Exception {
        Path archive = tempDir.resolve("current.glsave");
        SaveGameSnapshot expected = snapshotFixture();
        new SaveArchiveWriter().write(archive, snapshotCodec().encode(expected, MODIFIED));

        assertEquals(
                List.of(
                        "manifest.json",
                        "chunks.bin",
                        "player.json",
                        "inventory.json",
                        "world-items.json"),
                zipEntryNames(archive));

        SaveArchiveReadResult result = new SaveArchiveReader(snapshotCodec()).read(archive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        assertEquals(expected, result.snapshot().orElseThrow());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void unchangedEncodedSaveProducesByteIdenticalArchiveMetadata() throws Exception {
        EncodedSaveGame encoded = encodedFixture();
        Path first = tempDir.resolve("first.glsave");
        Path second = tempDir.resolve("second.glsave");

        new SaveArchiveWriter().write(first, encoded);
        new SaveArchiveWriter().write(second, encoded);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void writesExplicitCanonicalZipMetadataForEveryEntry() throws Exception {
        Path archive = tempDir.resolve("metadata.glsave");
        new SaveArchiveWriter().write(archive, encodedFixture());

        LocalDateTime fixedDosEpoch = LocalDateTime.of(1980, 1, 1, 0, 0);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                assertEquals(fixedDosEpoch, entry.getTimeLocal(), entry.getName());
                assertEquals(ZipEntry.DEFLATED, entry.getMethod(), entry.getName());
                assertNull(entry.getComment(), entry.getName());
                assertNull(entry.getExtra(), entry.getName());
            }
        }
    }

    static EncodedSaveGame encodedFixture() {
        return snapshotCodec().encode(snapshotFixture(), MODIFIED);
    }

    static SaveSnapshotCodec snapshotCodec() {
        return new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                new PlayerSectionCodec(),
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
    }

    static SaveGameSnapshot snapshotFixture() {
        List<ChunkSnapshot> chunks = new ArrayList<>();
        long revision = 0;
        for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) {
            for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) {
                byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
                blocks[Math.floorMod(x * 31 + z * 17, blocks.length)] =
                        (byte) (1 + Math.floorMod(x + z, 6));
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(x, z), ++revision, WORLD_HEIGHT, blocks));
            }
        }
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000"),
                        "Archive World",
                        CREATED,
                        12345L,
                        "v1",
                        "b".repeat(64),
                        CHUNK_RADIUS,
                        WORLD_HEIGHT,
                        Optional.of("Archive fixture")),
                42L,
                new ChunkRepositorySnapshot(WORLD_HEIGHT, revision, chunks),
                new PlayerSaveSnapshot(
                        OWNER,
                        1.25,
                        20.5,
                        -3.75,
                        0.125,
                        -0.25,
                        0.5,
                        90.0,
                        -12.5,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        OWNER, Map.of(), BodySlot.LEFT_HAND, false, 3L),
                new WorldItemsSaveSnapshot(42L, List.of(), 0L, false));
    }

    private static List<String> zipEntryNames(Path archive) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
