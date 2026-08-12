package com.gaia.save.format;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable lookup table for codecs supported by this game build. */
public final class SaveCodecRegistry {
    private final Map<CodecKey, SaveSectionCodec<?>> codecs;

    private SaveCodecRegistry(Map<CodecKey, SaveSectionCodec<?>> codecs) {
        this.codecs = Map.copyOf(codecs);
    }

    public static SaveCodecRegistry of(Collection<? extends SaveSectionCodec<?>> codecs) {
        Objects.requireNonNull(codecs, "codecs");
        Map<CodecKey, SaveSectionCodec<?>> indexed = new LinkedHashMap<>();
        for (SaveSectionCodec<?> codec : codecs) {
            Objects.requireNonNull(codec, "codec");
            CodecKey key = new CodecKey(codec.sectionId(), codec.codecVersion());
            if (codec.codecVersion() <= 0) {
                throw new IllegalArgumentException("Codec version must be positive");
            }
            validateBuiltInRequiredness(codec);
            if (indexed.putIfAbsent(key, codec) != null) {
                throw new IllegalArgumentException("Duplicate codec registration for " + key);
            }
        }
        return new SaveCodecRegistry(indexed);
    }

    public Optional<SaveSectionCodec<?>> resolve(SaveSectionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        SaveSectionCodec<?> codec = codecs.get(new CodecKey(descriptor.sectionId(), descriptor.codecVersion()));
        if (codec != null) {
            if (codec.required() != descriptor.required()) {
                throw new UnsupportedSaveSectionException(
                        "Save section requiredness does not match registered codec for "
                                + descriptor.sectionId().value());
            }
            return Optional.of(codec);
        }
        if (descriptor.required()) {
            throw new UnsupportedSaveSectionException(
                    "Unsupported required save section " + descriptor.sectionId().value()
                            + " codec version " + descriptor.codecVersion());
        }
        return Optional.empty();
    }

    private static void validateBuiltInRequiredness(SaveSectionCodec<?> codec) {
        if (SaveSectionId.isRequiredV1(codec.sectionId()) && !codec.required()) {
            throw new IllegalArgumentException("A required v1 section codec cannot be optional");
        }
        if (SaveSectionId.isReservedOptionalV1(codec.sectionId()) && codec.required()) {
            throw new IllegalArgumentException("A reserved optional v1 section codec cannot be required");
        }
    }

    private record CodecKey(SaveSectionId sectionId, int codecVersion) {
        private CodecKey {
            Objects.requireNonNull(sectionId, "sectionId");
        }
    }
}

final class UnsupportedSaveSectionException extends IllegalArgumentException {
    UnsupportedSaveSectionException(String message) {
        super(message);
    }
}
