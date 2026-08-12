package com.gaia.save.codec;

import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import java.util.List;
import java.util.Objects;

/** Complete in-memory v1 manifest and canonical required section payloads. */
public record EncodedSaveGame(
        SaveGameManifest manifest,
        List<EncodedSaveSection> sections) {
    private static final List<SaveSectionId> REQUIRED_ORDER = List.of(
            SaveSectionId.CHUNKS,
            SaveSectionId.PLAYER,
            SaveSectionId.INVENTORY,
            SaveSectionId.WORLD_ITEMS);

    public EncodedSaveGame {
        manifest = Objects.requireNonNull(manifest, "manifest");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        if (sections.size() != REQUIRED_ORDER.size()
                || manifest.sections().size() != REQUIRED_ORDER.size()) {
            throw new IllegalArgumentException(
                    "An encoded v1 save must contain exactly four required sections");
        }
        for (int index = 0; index < REQUIRED_ORDER.size(); index++) {
            EncodedSaveSection section = sections.get(index);
            SaveSectionDescriptor descriptor = section.descriptor();
            SaveSectionDescriptor manifestDescriptor = manifest.sections().get(index);
            if (!descriptor.sectionId().equals(REQUIRED_ORDER.get(index))) {
                throw new IllegalArgumentException(
                        "Encoded save sections are not in canonical v1 order");
            }
            if (!descriptor.required() || descriptor.codecVersion() != 1) {
                throw new IllegalArgumentException(
                        "Encoded v1 core sections must be required at codec version 1");
            }
            if (!descriptor.equals(manifestDescriptor)) {
                throw new IllegalArgumentException(
                        "Encoded section descriptor does not match the manifest");
            }
        }
    }
}
