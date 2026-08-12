package com.gaia.save.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SaveCodecRegistryTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void resolvesTheExactRegisteredCodecForMatchingDescriptor() {
        StubCodec chunks = new StubCodec(SaveSectionId.CHUNKS, 1, true);
        SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(chunks));

        assertSame(chunks, registry.resolve(descriptor(SaveSectionId.CHUNKS, 1, true)).orElseThrow());
    }

    @Test
    void registryRejectsUnknownRequiredButSkipsUnknownOptional() {
        SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(new StubCodec(SaveSectionId.CHUNKS, 1, true)));

        assertThrows(UnsupportedSaveSectionException.class,
                () -> registry.resolve(new SaveSectionDescriptor(new SaveSectionId("future"), 1, true, 0L, SHA)));
        assertTrue(registry.resolve(new SaveSectionDescriptor(new SaveSectionId("future"), 1, false, 0L, SHA)).isEmpty());
    }

    @Test
    void registryRejectsUnsupportedRequiredCodecVersionButSkipsOptionalOne() {
        SaveSectionId future = new SaveSectionId("future");
        SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(
                new StubCodec(SaveSectionId.CHUNKS, 1, true),
                new StubCodec(future, 1, false)));

        assertThrows(UnsupportedSaveSectionException.class,
                () -> registry.resolve(descriptor(SaveSectionId.CHUNKS, 2, true)));
        assertTrue(registry.resolve(descriptor(future, 2, false)).isEmpty());
    }

    @Test
    void registryRejectsDuplicateCodecKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> SaveCodecRegistry.of(List.of(
                        new StubCodec(SaveSectionId.CHUNKS, 1, true),
                        new StubCodec(SaveSectionId.CHUNKS, 1, true))));
    }

    @Test
    void registryRejectsBuiltInSectionCodecsWithContradictoryRequiredness() {
        assertThrows(IllegalArgumentException.class,
                () -> SaveCodecRegistry.of(List.of(new StubCodec(SaveSectionId.CHUNKS, 1, false))));
        assertThrows(IllegalArgumentException.class,
                () -> SaveCodecRegistry.of(List.of(new StubCodec(SaveSectionId.DISCOVERY_LORE, 1, true))));
    }

    @Test
    void registryRejectsDescriptorRequirednessThatMismatchesTheRegisteredCodec() {
        SaveSectionId future = new SaveSectionId("future");
        SaveCodecRegistry registry = SaveCodecRegistry.of(List.of(new StubCodec(future, 1, true)));

        assertThrows(UnsupportedSaveSectionException.class,
                () -> registry.resolve(descriptor(future, 1, false)));
    }

    private static SaveSectionDescriptor descriptor(SaveSectionId id, int version, boolean required) {
        return new SaveSectionDescriptor(id, version, required, 0L, SHA);
    }

    private record StubCodec(SaveSectionId sectionId, int codecVersion, boolean required)
            implements SaveSectionCodec<String> {
        @Override
        public byte[] encode(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
