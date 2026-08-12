package com.gaia.save.codec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SaveSnapshotCodecTest {
    private static final int WORLD_HEIGHT = 2;
    private static final long FIXED_TICK = 100;
    private static final EntityRef OWNER = new EntityRef(7);
    private static final EntityRef OTHER_OWNER = new EntityRef(8);
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-08-10T12:30:00Z");
    private static final List<SaveSectionId> CANONICAL_SECTION_ORDER = List.of(
            SaveSectionId.CHUNKS,
            SaveSectionId.PLAYER,
            SaveSectionId.INVENTORY,
            SaveSectionId.WORLD_ITEMS);

    private final ChunkSectionCodec chunks = new ChunkSectionCodec();
    private final PlayerSectionCodec player = new PlayerSectionCodec();
    private final InventorySectionCodec inventory = new InventorySectionCodec();
    private final WorldItemsSectionCodec worldItems = new WorldItemsSectionCodec();
    private final SaveSnapshotCodec codec =
            new SaveSnapshotCodec(chunks, player, inventory, worldItems);

    @Test
    void encodeBuildsCanonicalManifestAndExactDescriptors() {
        SaveGameSnapshot snapshot = fixture((byte) 7);

        EncodedSaveGame encoded = codec.encode(snapshot, MODIFIED);
        SaveGameManifest manifest = encoded.manifest();

        assertAll(
                () -> assertEquals(SaveFormatVersion.CURRENT, manifest.formatVersion()),
                () -> assertEquals(snapshot.metadata().gameVersion(), manifest.gameVersion()),
                () -> assertEquals(snapshot.metadata().saveGameId(), manifest.saveGameId()),
                () -> assertEquals(snapshot.metadata().displayName(), manifest.displayName()),
                () -> assertEquals(snapshot.metadata().createdAt(), manifest.createdAt()),
                () -> assertEquals(MODIFIED, manifest.modifiedAt()),
                () -> assertEquals(snapshot.metadata().worldSeed(), manifest.worldSeed()),
                () -> assertEquals(snapshot.metadata().generatorVersion(), manifest.generatorVersion()),
                () -> assertEquals(
                        snapshot.metadata().generatorConfigFingerprint(),
                        manifest.generatorConfigFingerprint()),
                () -> assertEquals(snapshot.metadata().chunkRadius(), manifest.chunkRadius()),
                () -> assertEquals(snapshot.metadata().worldHeight(), manifest.worldHeight()),
                () -> assertEquals(snapshot.fixedTick(), manifest.fixedTick()),
                () -> assertEquals(snapshot.metadata().summary().orElse(null), manifest.summary()));

        assertEquals(CANONICAL_SECTION_ORDER, sectionIds(encoded.sections()));
        assertEquals(CANONICAL_SECTION_ORDER, descriptorIds(manifest.sections()));
        assertEquals(manifest.sections(), descriptors(encoded.sections()));

        for (EncodedSaveSection section : encoded.sections()) {
            byte[] bytes = section.bytes();
            SaveSectionDescriptor descriptor = section.descriptor();
            assertAll(
                    descriptor.sectionId().value(),
                    () -> assertEquals(1, descriptor.codecVersion()),
                    () -> assertEquals(true, descriptor.required()),
                    () -> assertEquals(bytes.length, descriptor.uncompressedSize()),
                    () -> assertEquals(sha256(bytes), descriptor.sha256()));
        }
    }

    @Test
    void repeatedEncodeIsDeterministicAndModifiedTimeChangesOnlyTheManifestTime() {
        SaveGameSnapshot snapshot = fixture((byte) 7);

        EncodedSaveGame first = codec.encode(snapshot, MODIFIED);
        EncodedSaveGame repeated = codec.encode(snapshot, MODIFIED);
        EncodedSaveGame later = codec.encode(snapshot, MODIFIED.plusSeconds(60));

        assertEquals(first.manifest(), repeated.manifest());
        assertSectionBytesEqual(first.sections(), repeated.sections());

        assertEquals(first.manifest().sections(), later.manifest().sections());
        assertEquals(
                withModifiedAt(first.manifest(), MODIFIED.plusSeconds(60)),
                later.manifest());
        assertSectionBytesEqual(first.sections(), later.sections());
    }

    @Test
    void changingOneBlockChangesOnlyTheChunksPayloadAndDescriptor() {
        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        EncodedSaveGame changed = codec.encode(fixture((byte) 8), MODIFIED);

        for (int index = 0; index < CANONICAL_SECTION_ORDER.size(); index++) {
            EncodedSaveSection before = baseline.sections().get(index);
            EncodedSaveSection after = changed.sections().get(index);
            if (before.descriptor().sectionId().equals(SaveSectionId.CHUNKS)) {
                assertFalse(Arrays.equals(before.bytes(), after.bytes()));
                assertNotEquals(before.descriptor(), after.descriptor());
            } else {
                assertArrayEquals(before.bytes(), after.bytes());
                assertEquals(before.descriptor(), after.descriptor());
            }
        }

        assertEquals(
                baseline.manifest(),
                withSections(changed.manifest(), baseline.manifest().sections()));
    }

    @Test
    void decodeReconstructsExactCanonicalSnapshotEquality() {
        SaveGameSnapshot snapshot = fixture((byte) 7);
        EncodedSaveGame encoded = codec.encode(snapshot, MODIFIED);

        SaveGameSnapshot decoded = codec.decode(encoded.manifest(), payloads(encoded));

        assertEquals(snapshot, decoded);
        assertEquals(snapshot.hashCode(), decoded.hashCode());
    }

    @Test
    void encodedValuesDefensivelyOwnPayloadsAndSectionCollections() {
        byte[] source = new byte[] {1, 2, 3};
        SaveSectionDescriptor descriptor = descriptor(
                new SaveSectionId("future"), 1, false, source);
        EncodedSaveSection detachedSection = new EncodedSaveSection(descriptor, source);

        source[0] = 99;
        byte[] exposed = detachedSection.bytes();
        exposed[1] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, detachedSection.bytes());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedSaveSection(
                        new SaveSectionDescriptor(
                                descriptor.sectionId(),
                                descriptor.codecVersion(),
                                descriptor.required(),
                                4,
                                descriptor.sha256()),
                        new byte[] {1, 2, 3}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedSaveSection(
                        new SaveSectionDescriptor(
                                descriptor.sectionId(),
                                descriptor.codecVersion(),
                                descriptor.required(),
                                3,
                                "0".repeat(64)),
                        new byte[] {1, 2, 3}));

        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        List<EncodedSaveSection> mutableSections =
                new ArrayList<>(baseline.sections());
        EncodedSaveGame detachedGame =
                new EncodedSaveGame(baseline.manifest(), mutableSections);
        mutableSections.clear();

        assertEquals(CANONICAL_SECTION_ORDER, sectionIds(detachedGame.sections()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> detachedGame.sections().clear());
    }

    @Test
    void encodedSaveGameRejectsUnsupportedCoreCodecVersion() {
        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        byte[] chunksBytes = baseline.sections().get(0).bytes();
        SaveSectionDescriptor versionTwoDescriptor =
                descriptor(SaveSectionId.CHUNKS, 2, true, chunksBytes);
        List<EncodedSaveSection> versionTwoSections =
                new ArrayList<>(baseline.sections());
        versionTwoSections.set(
                0, new EncodedSaveSection(versionTwoDescriptor, chunksBytes));
        List<SaveSectionDescriptor> versionTwoDescriptors =
                new ArrayList<>(baseline.manifest().sections());
        versionTwoDescriptors.set(0, versionTwoDescriptor);

        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedSaveGame(
                        withSections(
                                baseline.manifest(), versionTwoDescriptors),
                        versionTwoSections));
    }

    @Test
    void encodedSaveGameRejectsMissingOrAdditionalSectionCount() {
        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        SaveSectionId optionalId = new SaveSectionId("future-optional");
        byte[] optionalBytes = new byte[] {4, 5, 6};
        SaveSectionDescriptor optionalDescriptor =
                descriptor(optionalId, 1, false, optionalBytes);
        List<SaveSectionDescriptor> additionalDescriptors =
                new ArrayList<>(baseline.manifest().sections());
        additionalDescriptors.add(optionalDescriptor);
        List<EncodedSaveSection> additionalSections =
                new ArrayList<>(baseline.sections());
        additionalSections.add(
                new EncodedSaveSection(optionalDescriptor, optionalBytes));

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new EncodedSaveGame(
                                baseline.manifest(),
                                baseline.sections().subList(0, 3))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new EncodedSaveGame(
                                withSections(
                                        baseline.manifest(), additionalDescriptors),
                                additionalSections)));
    }

    @Test
    void encodedSaveGameRejectsNoncanonicalOrderEvenWhenManifestAgrees() {
        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        List<EncodedSaveSection> reorderedSections =
                new ArrayList<>(baseline.sections());
        java.util.Collections.swap(reorderedSections, 0, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedSaveGame(
                        withSections(
                                baseline.manifest(),
                                descriptors(reorderedSections)),
                        reorderedSections));
    }

    @Test
    void encodedSaveGameRejectsManifestSectionDescriptorMismatch() {
        EncodedSaveGame baseline = codec.encode(fixture((byte) 7), MODIFIED);
        byte[] chunksBytes = baseline.sections().get(0).bytes();
        SaveSectionDescriptor differentManifestDescriptor =
                descriptor(SaveSectionId.CHUNKS, 2, true, chunksBytes);
        List<SaveSectionDescriptor> differentManifestDescriptors =
                new ArrayList<>(baseline.manifest().sections());
        differentManifestDescriptors.set(0, differentManifestDescriptor);

        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedSaveGame(
                        withSections(
                                baseline.manifest(),
                                differentManifestDescriptors),
                        baseline.sections()));
    }

    @Test
    void decodeRejectsMissingRequiredPayloadAndExactSizeOrChecksumMismatch() {
        EncodedSaveGame encoded = codec.encode(fixture((byte) 7), MODIFIED);

        Map<SaveSectionId, byte[]> missing = payloads(encoded);
        missing.remove(SaveSectionId.WORLD_ITEMS);

        Map<SaveSectionId, byte[]> wrongSize = payloads(encoded);
        byte[] inventoryBytes = wrongSize.get(SaveSectionId.INVENTORY);
        wrongSize.put(
                SaveSectionId.INVENTORY,
                Arrays.copyOf(inventoryBytes, inventoryBytes.length - 1));

        Map<SaveSectionId, byte[]> wrongChecksum = payloads(encoded);
        byte[] playerBytes = wrongChecksum.get(SaveSectionId.PLAYER).clone();
        playerBytes[playerBytes.length - 1] ^= 1;
        wrongChecksum.put(SaveSectionId.PLAYER, playerBytes);

        assertAll(
                () -> assertDecodeFailure(
                        () -> codec.decode(encoded.manifest(), missing)),
                () -> assertDecodeFailure(
                        () -> codec.decode(encoded.manifest(), wrongSize)),
                () -> assertDecodeFailure(
                        () -> codec.decode(encoded.manifest(), wrongChecksum)));
    }

    @Test
    void decodeRejectsUnsupportedRequiredSectionsAndNoncanonicalDescriptorOrder() {
        EncodedSaveGame encoded = codec.encode(fixture((byte) 7), MODIFIED);
        Map<SaveSectionId, byte[]> payloads = payloads(encoded);

        List<SaveSectionDescriptor> unsupportedVersion =
                new ArrayList<>(encoded.manifest().sections());
        SaveSectionDescriptor chunksDescriptor = unsupportedVersion.get(0);
        unsupportedVersion.set(
                0,
                new SaveSectionDescriptor(
                        SaveSectionId.CHUNKS,
                        2,
                        true,
                        chunksDescriptor.uncompressedSize(),
                        chunksDescriptor.sha256()));

        SaveSectionId futureRequired = new SaveSectionId("future-required");
        byte[] futureBytes = new byte[] {4, 5, 6};
        List<SaveSectionDescriptor> unknownRequired =
                new ArrayList<>(encoded.manifest().sections());
        unknownRequired.add(descriptor(futureRequired, 1, true, futureBytes));
        Map<SaveSectionId, byte[]> unknownRequiredPayloads = payloads(encoded);
        unknownRequiredPayloads.put(futureRequired, futureBytes);

        List<SaveSectionDescriptor> reversed =
                new ArrayList<>(encoded.manifest().sections());
        java.util.Collections.reverse(reversed);

        assertAll(
                () -> assertDecodeFailure(
                        () -> codec.decode(
                                withSections(encoded.manifest(), unsupportedVersion),
                                payloads)),
                () -> assertDecodeFailure(
                        () -> codec.decode(
                                withSections(encoded.manifest(), unknownRequired),
                                unknownRequiredPayloads)),
                () -> assertDecodeFailure(
                        () -> codec.decode(
                                withSections(encoded.manifest(), reversed),
                                payloads)));
    }

    @Test
    void unknownOptionalSectionIsValidatedWhenPresentAndMayBeAbsent() {
        SaveGameSnapshot snapshot = fixture((byte) 7);
        EncodedSaveGame encoded = codec.encode(snapshot, MODIFIED);
        SaveSectionId optionalId = new SaveSectionId("future-optional");
        byte[] optionalBytes = new byte[] {9, 8, 7};
        SaveSectionDescriptor optionalDescriptor =
                descriptor(optionalId, 3, false, optionalBytes);
        List<SaveSectionDescriptor> withOptional =
                new ArrayList<>(encoded.manifest().sections());
        withOptional.add(optionalDescriptor);
        SaveGameManifest optionalManifest =
                withSections(encoded.manifest(), withOptional);
        Map<SaveSectionId, byte[]> validPayloads = payloads(encoded);
        validPayloads.put(optionalId, optionalBytes.clone());

        assertEquals(snapshot, codec.decode(optionalManifest, validPayloads));

        Map<SaveSectionId, byte[]> corruptPayloads = copyPayloads(validPayloads);
        corruptPayloads.get(optionalId)[0] ^= 1;
        Map<SaveSectionId, byte[]> missingPayloads = copyPayloads(validPayloads);
        missingPayloads.remove(optionalId);

        assertAll(
                () -> assertDecodeFailure(
                        () -> codec.decode(optionalManifest, corruptPayloads)),
                () -> assertEquals(
                        snapshot, codec.decode(optionalManifest, missingPayloads)));
    }

    @Test
    void invalidLatePayloadsDoNotInvokeAnyDomainDecoder() {
        EncodedSaveGame encoded = codec.encode(fixture((byte) 7), MODIFIED);

        Map<SaveSectionId, byte[]> missingFinalRequired = payloads(encoded);
        missingFinalRequired.remove(SaveSectionId.WORLD_ITEMS);

        Map<SaveSectionId, byte[]> corruptFinalRequired = payloads(encoded);
        corruptFinalRequired.get(SaveSectionId.WORLD_ITEMS)[0] ^= 1;

        SaveSectionId optionalId = new SaveSectionId("future-optional");
        byte[] validOptionalBytes = new byte[] {9, 8, 7};
        List<SaveSectionDescriptor> optionalDescriptors =
                new ArrayList<>(encoded.manifest().sections());
        optionalDescriptors.add(
                descriptor(optionalId, 1, false, validOptionalBytes));
        SaveGameManifest optionalManifest =
                withSections(encoded.manifest(), optionalDescriptors);
        Map<SaveSectionId, byte[]> corruptOptional = payloads(encoded);
        byte[] corruptOptionalBytes = validOptionalBytes.clone();
        corruptOptionalBytes[0] ^= 1;
        corruptOptional.put(optionalId, corruptOptionalBytes);

        assertAll(
                () -> assertFailsBeforeAnyDomainDecode(
                        encoded.manifest(), missingFinalRequired),
                () -> assertFailsBeforeAnyDomainDecode(
                        encoded.manifest(), corruptFinalRequired),
                () -> assertFailsBeforeAnyDomainDecode(
                        optionalManifest, corruptOptional));
    }

    @Test
    void validInputInvokesEachDomainDecoderOnceInCanonicalOrder() {
        SaveGameSnapshot snapshot = fixture((byte) 7);
        EncodedSaveGame encoded = codec.encode(snapshot, MODIFIED);
        List<SaveSectionId> decodeCalls = new ArrayList<>();
        SaveSnapshotCodec recordingCodec =
                recordingSnapshotCodec(decodeCalls, false);

        SaveGameSnapshot decoded = recordingCodec.decode(
                encoded.manifest(), payloads(encoded));

        assertEquals(snapshot, decoded);
        assertEquals(CANONICAL_SECTION_ORDER, decodeCalls);
    }

    @Test
    void decodeRejectsPayloadWithoutDescriptorAndCrossSectionMetadataMismatch() {
        EncodedSaveGame encoded = codec.encode(fixture((byte) 7), MODIFIED);

        Map<SaveSectionId, byte[]> extraPayload = payloads(encoded);
        extraPayload.put(SaveSectionId.DETAIL_BLOCKS, new byte[] {1});

        SaveGameManifest wrongTick = new SaveGameManifest(
                encoded.manifest().formatVersion(),
                encoded.manifest().gameVersion(),
                encoded.manifest().saveGameId(),
                encoded.manifest().displayName(),
                encoded.manifest().createdAt(),
                encoded.manifest().modifiedAt(),
                encoded.manifest().worldSeed(),
                encoded.manifest().generatorVersion(),
                encoded.manifest().generatorConfigFingerprint(),
                encoded.manifest().chunkRadius(),
                encoded.manifest().worldHeight(),
                encoded.manifest().fixedTick() + 1,
                encoded.manifest().summary(),
                encoded.manifest().sections());
        SaveGameManifest wrongHeight = new SaveGameManifest(
                encoded.manifest().formatVersion(),
                encoded.manifest().gameVersion(),
                encoded.manifest().saveGameId(),
                encoded.manifest().displayName(),
                encoded.manifest().createdAt(),
                encoded.manifest().modifiedAt(),
                encoded.manifest().worldSeed(),
                encoded.manifest().generatorVersion(),
                encoded.manifest().generatorConfigFingerprint(),
                encoded.manifest().chunkRadius(),
                encoded.manifest().worldHeight() + 1,
                encoded.manifest().fixedTick(),
                encoded.manifest().summary(),
                encoded.manifest().sections());

        InventorySaveSnapshot otherInventory = new InventorySaveSnapshot(
                OTHER_OWNER,
                fixture((byte) 7).inventory().stacks(),
                BodySlot.RIGHT_HAND,
                false,
                6);
        byte[] otherInventoryBytes = inventory.encode(otherInventory);
        List<SaveSectionDescriptor> otherOwnerDescriptors =
                new ArrayList<>(encoded.manifest().sections());
        otherOwnerDescriptors.set(
                CANONICAL_SECTION_ORDER.indexOf(SaveSectionId.INVENTORY),
                descriptor(SaveSectionId.INVENTORY, 1, true, otherInventoryBytes));
        SaveGameManifest otherOwnerManifest =
                withSections(encoded.manifest(), otherOwnerDescriptors);
        Map<SaveSectionId, byte[]> otherOwnerPayloads = payloads(encoded);
        otherOwnerPayloads.put(SaveSectionId.INVENTORY, otherInventoryBytes);

        assertAll(
                () -> assertDecodeFailure(
                        () -> codec.decode(encoded.manifest(), extraPayload)),
                () -> assertDecodeFailure(
                        () -> codec.decode(wrongTick, payloads(encoded))),
                () -> assertDecodeFailure(
                        () -> codec.decode(wrongHeight, payloads(encoded))),
                () -> assertDecodeFailure(
                        () -> codec.decode(otherOwnerManifest, otherOwnerPayloads)));
    }

    @Test
    void encodeRejectsModifiedTimeBeforeCreationWithStableFailure() {
        SaveCodecException failure = assertThrows(
                SaveCodecException.class,
                () -> codec.encode(fixture((byte) 7), CREATED.minusNanos(1)));

        assertAll(
                () -> assertEquals("save-snapshot.invalid-snapshot", failure.code()),
                () -> assertNotNull(failure.getCause()));
    }

    @Test
    void fatalErrorsEscapeEncodeAndDecodeBoundaries() {
        AssertionError encodeFatal = new AssertionError("fatal encode");
        SaveSnapshotCodec fatalEncoder = new SaveSnapshotCodec(
                fatalCodec(chunks, encodeFatal, null),
                player,
                inventory,
                worldItems);
        assertEquals(
                encodeFatal,
                assertThrows(
                        AssertionError.class,
                        () -> fatalEncoder.encode(fixture((byte) 7), MODIFIED)));

        EncodedSaveGame encoded = codec.encode(fixture((byte) 7), MODIFIED);
        AssertionError decodeFatal = new AssertionError("fatal decode");
        SaveSnapshotCodec fatalDecoder = new SaveSnapshotCodec(
                fatalCodec(chunks, null, decodeFatal),
                player,
                inventory,
                worldItems);
        assertEquals(
                decodeFatal,
                assertThrows(
                        AssertionError.class,
                        () -> fatalDecoder.decode(
                                encoded.manifest(), payloads(encoded))));
    }

    private static SaveGameSnapshot fixture(byte changedBlock) {
        byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
        blocks[31] = changedBlock;
        ChunkSnapshot chunk = ChunkSnapshot.of(
                new ChunkKey(-1, 2), 3, WORLD_HEIGHT, blocks);
        ChunkRepositorySnapshot chunkSnapshot =
                new ChunkRepositorySnapshot(WORLD_HEIGHT, 7, List.of(chunk));

        EnumMap<BodySlot, ItemStack> stacks = new EnumMap<>(BodySlot.class);
        stacks.put(
                BodySlot.LEFT_HAND,
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 3));
        InventorySaveSnapshot inventorySnapshot = new InventorySaveSnapshot(
                OWNER, stacks, BodySlot.RIGHT_HAND, false, 6);

        ItemStack worldStack =
                new ItemStack(ResourceLocation.parse("gaia:stone"), 2);
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(3),
                worldStack,
                4.25, 5.5, -6.75,
                0.4, -0.5, 0.6,
                2);
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        item, Optional.of(OWNER), 90, 105),
                WorldItemPhysicalState.SLEEPING);

        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        SAVE_ID,
                        "World One",
                        CREATED,
                        12345L,
                        "v1",
                        "a".repeat(64),
                        4,
                        WORLD_HEIGHT,
                        Optional.of("A valid world")),
                FIXED_TICK,
                chunkSnapshot,
                new PlayerSaveSnapshot(
                        OWNER,
                        1.25, 2.5, -3.75,
                        0.1, -0.2, 0.3,
                        1080.25, -12.5,
                        GameMode.SURVIVAL,
                        true),
                inventorySnapshot,
                new WorldItemsSaveSnapshot(
                        FIXED_TICK, List.of(entry), 4, false));
    }

    private static List<SaveSectionId> sectionIds(
            List<EncodedSaveSection> sections) {
        return sections.stream()
                .map(section -> section.descriptor().sectionId())
                .toList();
    }

    private static List<SaveSectionId> descriptorIds(
            List<SaveSectionDescriptor> descriptors) {
        return descriptors.stream().map(SaveSectionDescriptor::sectionId).toList();
    }

    private static List<SaveSectionDescriptor> descriptors(
            List<EncodedSaveSection> sections) {
        return sections.stream().map(EncodedSaveSection::descriptor).toList();
    }

    private static Map<SaveSectionId, byte[]> payloads(EncodedSaveGame encoded) {
        LinkedHashMap<SaveSectionId, byte[]> payloads = new LinkedHashMap<>();
        for (EncodedSaveSection section : encoded.sections()) {
            payloads.put(section.descriptor().sectionId(), section.bytes());
        }
        return payloads;
    }

    private static Map<SaveSectionId, byte[]> copyPayloads(
            Map<SaveSectionId, byte[]> source) {
        LinkedHashMap<SaveSectionId, byte[]> copy = new LinkedHashMap<>();
        source.forEach((sectionId, bytes) -> copy.put(sectionId, bytes.clone()));
        return copy;
    }

    private static SaveSectionDescriptor descriptor(
            SaveSectionId sectionId,
            int codecVersion,
            boolean required,
            byte[] bytes) {
        return new SaveSectionDescriptor(
                sectionId, codecVersion, required, bytes.length, sha256(bytes));
    }

    private static SaveGameManifest withModifiedAt(
            SaveGameManifest source, Instant modifiedAt) {
        return new SaveGameManifest(
                source.formatVersion(),
                source.gameVersion(),
                source.saveGameId(),
                source.displayName(),
                source.createdAt(),
                modifiedAt,
                source.worldSeed(),
                source.generatorVersion(),
                source.generatorConfigFingerprint(),
                source.chunkRadius(),
                source.worldHeight(),
                source.fixedTick(),
                source.summary(),
                source.sections());
    }

    private static SaveGameManifest withSections(
            SaveGameManifest source, List<SaveSectionDescriptor> sections) {
        return new SaveGameManifest(
                source.formatVersion(),
                source.gameVersion(),
                source.saveGameId(),
                source.displayName(),
                source.createdAt(),
                source.modifiedAt(),
                source.worldSeed(),
                source.generatorVersion(),
                source.generatorConfigFingerprint(),
                source.chunkRadius(),
                source.worldHeight(),
                source.fixedTick(),
                source.summary(),
                sections);
    }

    private static void assertSectionBytesEqual(
            List<EncodedSaveSection> expected,
            List<EncodedSaveSection> actual) {
        assertEquals(descriptors(expected), descriptors(actual));
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).bytes(), actual.get(index).bytes());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void assertDecodeFailure(ThrowingOperation operation) {
        SaveCodecException failure =
                assertThrows(SaveCodecException.class, operation::run);
        assertEquals("save-snapshot.invalid-save", failure.code());
        assertNotNull(failure.getCause());
    }

    private void assertFailsBeforeAnyDomainDecode(
            SaveGameManifest manifest,
            Map<SaveSectionId, byte[]> payloads) {
        List<SaveSectionId> decodeCalls = new ArrayList<>();
        SaveSnapshotCodec poisonCodec =
                recordingSnapshotCodec(decodeCalls, true);

        assertDecodeFailure(() -> poisonCodec.decode(manifest, payloads));
        assertEquals(List.of(), decodeCalls);
    }

    private SaveSnapshotCodec recordingSnapshotCodec(
            List<SaveSectionId> decodeCalls, boolean poison) {
        return new SaveSnapshotCodec(
                recordingCodec(chunks, decodeCalls, poison),
                recordingCodec(player, decodeCalls, poison),
                recordingCodec(inventory, decodeCalls, poison),
                recordingCodec(worldItems, decodeCalls, poison));
    }

    private static <T> SaveSectionCodec<T> recordingCodec(
            SaveSectionCodec<T> delegate,
            List<SaveSectionId> decodeCalls,
            boolean poison) {
        return new SaveSectionCodec<>() {
            @Override
            public SaveSectionId sectionId() {
                return delegate.sectionId();
            }

            @Override
            public int codecVersion() {
                return delegate.codecVersion();
            }

            @Override
            public boolean required() {
                return delegate.required();
            }

            @Override
            public byte[] encode(T value) {
                return delegate.encode(value);
            }

            @Override
            public T decode(byte[] bytes) {
                decodeCalls.add(sectionId());
                if (poison) {
                    throw new AssertionError(
                            "Domain decoder ran before all payloads were valid");
                }
                return delegate.decode(bytes);
            }
        };
    }

    private static <T> SaveSectionCodec<T> fatalCodec(
            SaveSectionCodec<T> delegate,
            AssertionError encodeFatal,
            AssertionError decodeFatal) {
        return new SaveSectionCodec<>() {
            @Override
            public SaveSectionId sectionId() {
                return delegate.sectionId();
            }

            @Override
            public int codecVersion() {
                return delegate.codecVersion();
            }

            @Override
            public boolean required() {
                return delegate.required();
            }

            @Override
            public byte[] encode(T value) {
                if (encodeFatal != null) {
                    throw encodeFatal;
                }
                return delegate.encode(value);
            }

            @Override
            public T decode(byte[] bytes) {
                if (decodeFatal != null) {
                    throw decodeFatal;
                }
                return delegate.decode(bytes);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
