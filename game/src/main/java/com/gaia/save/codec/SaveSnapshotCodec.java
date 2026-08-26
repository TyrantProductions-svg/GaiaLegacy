package com.gaia.save.codec;

import com.gaia.save.format.SaveCodecRegistry;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.voxel.ChunkRepositorySnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure deterministic assembly boundary for the four required v1 sections. */
public final class SaveSnapshotCodec {
    private static final String INVALID_SNAPSHOT =
            "save-snapshot.invalid-snapshot";
    private static final String INVALID_SAVE = "save-snapshot.invalid-save";
    private static final List<SaveSectionId> REQUIRED_ORDER = List.of(
            SaveSectionId.CHUNKS,
            SaveSectionId.PLAYER,
            SaveSectionId.INVENTORY,
            SaveSectionId.WORLD_ITEMS);

    private final SaveSectionCodec<ChunkRepositorySnapshot> chunksCodec;
    private final SaveSectionCodec<PlayerSaveSnapshot> playerCodec;
    private final SaveSectionCodec<InventorySaveSnapshot> inventoryCodec;
    private final SaveSectionCodec<WorldItemsSaveSnapshot> worldItemsCodec;
    private final SaveCodecRegistry registry;

    public SaveSnapshotCodec(
            SaveSectionCodec<ChunkRepositorySnapshot> chunksCodec,
            SaveSectionCodec<PlayerSaveSnapshot> playerCodec,
            SaveSectionCodec<InventorySaveSnapshot> inventoryCodec,
            SaveSectionCodec<WorldItemsSaveSnapshot> worldItemsCodec) {
        this.chunksCodec = requireRequiredV1Codec(
                chunksCodec, SaveSectionId.CHUNKS);
        this.playerCodec = requireRequiredV1Codec(
                playerCodec, SaveSectionId.PLAYER);
        this.inventoryCodec = requireRequiredV1Codec(
                inventoryCodec, SaveSectionId.INVENTORY);
        this.worldItemsCodec = requireRequiredV1Codec(
                worldItemsCodec, SaveSectionId.WORLD_ITEMS);
        this.registry = SaveCodecRegistry.of(List.of(
                this.chunksCodec,
                this.playerCodec,
                this.inventoryCodec,
                this.worldItemsCodec));
    }

    public EncodedSaveGame encode(
            SaveGameSnapshot snapshot, Instant modifiedTime) {
        try {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(modifiedTime, "modifiedTime");

            List<EncodedSaveSection> sections = List.of(
                    encodeSection(chunksCodec, snapshot.chunks()),
                    encodeSection(playerCodec, snapshot.player()),
                    encodeSection(inventoryCodec, snapshot.inventory()),
                    encodeSection(worldItemsCodec, snapshot.worldItems()));
            SaveGameSnapshot.StaticMetadata metadata = snapshot.metadata();
            SaveGameManifest manifest = new SaveGameManifest(
                    metadata.formatVersion(),
                    metadata.gameVersion(),
                    metadata.saveGameId(),
                    metadata.displayName(),
                    metadata.createdAt(),
                    modifiedTime,
                    metadata.worldSeed(),
                    metadata.generatorVersion(),
                    metadata.generatorConfigFingerprint(),
                    metadata.chunkRadius(),
                    metadata.worldHeight(),
                    snapshot.fixedTick(),
                    metadata.summary().orElse(null),
                    sections.stream()
                            .map(EncodedSaveSection::descriptor)
                            .toList());
            return new EncodedSaveGame(manifest, sections);
        } catch (SaveCodecException sectionFailure) {
            throw sectionFailure;
        } catch (RuntimeException failure) {
            throw new SaveCodecException(
                    INVALID_SNAPSHOT,
                    "Save snapshot could not be encoded",
                    failure);
        }
    }

    public SaveGameSnapshot decode(
            SaveGameManifest manifest,
            Map<SaveSectionId, byte[]> payloads) {
        try {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(payloads, "payloads");

            validateDescriptorOrder(manifest.sections());
            Map<SaveSectionId, byte[]> verifiedPayloads =
                    verifyAndCopyEveryPayload(manifest.sections(), payloads);

            ChunkRepositorySnapshot chunks = chunksCodec.decode(
                    verifiedPayloads.get(SaveSectionId.CHUNKS));
            PlayerSaveSnapshot player = playerCodec.decode(
                    verifiedPayloads.get(SaveSectionId.PLAYER));
            InventorySaveSnapshot inventory = inventoryCodec.decode(
                    verifiedPayloads.get(SaveSectionId.INVENTORY));
            WorldItemsSaveSnapshot worldItems = worldItemsCodec.decode(
                    verifiedPayloads.get(SaveSectionId.WORLD_ITEMS));

            SaveGameSnapshot.StaticMetadata metadata =
                    new SaveGameSnapshot.StaticMetadata(
                            manifest.formatVersion(),
                            manifest.gameVersion(),
                            manifest.saveGameId(),
                            manifest.displayName(),
                            manifest.createdAt(),
                            manifest.worldSeed(),
                            manifest.generatorVersion(),
                            manifest.generatorConfigFingerprint(),
                            manifest.chunkRadius(),
                            manifest.worldHeight(),
                            Optional.ofNullable(manifest.summary()));
            return new SaveGameSnapshot(
                    metadata,
                    manifest.fixedTick(),
                    chunks,
                    player,
                    inventory,
                    worldItems);
        } catch (RuntimeException failure) {
            throw new SaveCodecException(
                    INVALID_SAVE,
                    "Encoded save could not be decoded",
                    failure);
        }
    }

    private void validateDescriptorOrder(
            List<SaveSectionDescriptor> descriptors) {
        if (descriptors.size() < REQUIRED_ORDER.size()) {
            throw new IllegalArgumentException(
                    "Save manifest is missing a required v1 descriptor");
        }
        for (int index = 0; index < REQUIRED_ORDER.size(); index++) {
            SaveSectionDescriptor descriptor = descriptors.get(index);
            if (!descriptor.sectionId().equals(REQUIRED_ORDER.get(index))) {
                throw new IllegalArgumentException(
                        "Save manifest descriptors are not in canonical v1 order");
            }
        }
        for (int index = REQUIRED_ORDER.size(); index < descriptors.size(); index++) {
            if (descriptors.get(index).required()) {
                throw new IllegalArgumentException(
                        "Unsupported required descriptor follows the v1 sections");
            }
        }
    }

    private Map<SaveSectionId, byte[]> verifyAndCopyEveryPayload(
            List<SaveSectionDescriptor> descriptors,
            Map<SaveSectionId, byte[]> payloads) {
        Set<SaveSectionId> describedIds = new HashSet<>();
        LinkedHashMap<SaveSectionId, byte[]> verified = new LinkedHashMap<>();
        for (SaveSectionDescriptor descriptor : descriptors) {
            describedIds.add(descriptor.sectionId());
            registry.resolve(descriptor);
            byte[] bytes = payloads.get(descriptor.sectionId());
            if (bytes == null) {
                if (!descriptor.required()) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "Save payload is missing section "
                                + descriptor.sectionId().value());
            }
            byte[] detached = bytes.clone();
            if (descriptor.uncompressedSize() != detached.length) {
                throw new IllegalArgumentException(
                        "Save section size does not match descriptor for "
                                + descriptor.sectionId().value());
            }
            if (!descriptor.sha256().equals(sha256(detached))) {
                throw new IllegalArgumentException(
                        "Save section SHA-256 does not match descriptor for "
                                + descriptor.sectionId().value());
            }
            verified.put(descriptor.sectionId(), detached);
        }
        for (Map.Entry<SaveSectionId, byte[]> entry : payloads.entrySet()) {
            SaveSectionId sectionId = Objects.requireNonNull(
                    entry.getKey(), "payload section ID");
            Objects.requireNonNull(entry.getValue(), "payload bytes");
            if (!describedIds.contains(sectionId)) {
                throw new IllegalArgumentException(
                        "Save payload has no manifest descriptor: "
                                + sectionId.value());
            }
        }
        return Map.copyOf(verified);
    }

    private static <T> EncodedSaveSection encodeSection(
            SaveSectionCodec<T> codec, T value) {
        byte[] bytes = Objects.requireNonNull(
                codec.encode(value), "encoded section bytes");
        SaveSectionDescriptor descriptor = new SaveSectionDescriptor(
                codec.sectionId(),
                codec.codecVersion(),
                codec.required(),
                bytes.length,
                sha256(bytes));
        return new EncodedSaveSection(descriptor, bytes);
    }

    private static <T> SaveSectionCodec<T> requireRequiredV1Codec(
            SaveSectionCodec<T> codec, SaveSectionId expectedId) {
        Objects.requireNonNull(codec, "codec");
        if (!codec.sectionId().equals(expectedId)
                || codec.codecVersion() != 1
                || !codec.required()) {
            throw new IllegalArgumentException(
                    "SaveSnapshotCodec requires the required "
                            + expectedId.value()
                            + " codec at version 1");
        }
        return codec;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
