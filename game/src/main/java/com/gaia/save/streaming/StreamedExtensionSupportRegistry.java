package com.gaia.save.streaming;

import com.gaia.save.format.SaveSectionId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Injected semantic support policy for generic streamed Chunk extensions. */
public final class StreamedExtensionSupportRegistry {
    private final Map<SaveSectionId, Support> support;

    private StreamedExtensionSupportRegistry(Map<SaveSectionId, Support> support) {
        this.support = Map.copyOf(support);
    }

    public static StreamedExtensionSupportRegistry empty() {
        return new StreamedExtensionSupportRegistry(Map.of());
    }

    public static StreamedExtensionSupportRegistry productionDefaults() {
        return builder()
                .supportRequired(SaveSectionId.DETAIL_BLOCKS, 1)
                .supportRequired(SaveSectionId.WORLD_ITEM_PAGE, 1)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    boolean supports(SaveSectionId sectionId, int codecVersion, boolean required) {
        Support value = support.get(Objects.requireNonNull(sectionId, "sectionId"));
        return value != null
                && value.codecVersion == codecVersion
                && (required ? value.required : value.optional);
    }

    public static final class Builder {
        private final Map<SaveSectionId, Support> support = new HashMap<>();

        public Builder supportRequired(SaveSectionId sectionId, int codecVersion) {
            return add(sectionId, codecVersion, true, false);
        }

        public Builder supportOptional(SaveSectionId sectionId, int codecVersion) {
            return add(sectionId, codecVersion, false, true);
        }

        private Builder add(
                SaveSectionId sectionId,
                int codecVersion,
                boolean required,
                boolean optional) {
            Objects.requireNonNull(sectionId, "sectionId");
            if (codecVersion <= 0) {
                throw new IllegalArgumentException("codecVersion must be positive");
            }
            Support previous = support.get(sectionId);
            if (previous != null && previous.codecVersion != codecVersion) {
                throw new IllegalArgumentException(
                        "A streamed extension ID has conflicting codec versions");
            }
            support.put(sectionId, previous == null
                    ? new Support(codecVersion, required, optional)
                    : new Support(
                            codecVersion,
                            previous.required || required,
                            previous.optional || optional));
            return this;
        }

        public StreamedExtensionSupportRegistry build() {
            return new StreamedExtensionSupportRegistry(support);
        }
    }

    private record Support(int codecVersion, boolean required, boolean optional) {}
}
