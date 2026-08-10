package com.overlord.audio;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.regex.Pattern;

public record SoundCue(
        SoundEvent event,
        ResourceLocation resource,
        String category,
        float baseGain) {
    private static final Pattern CATEGORY =
            Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    public SoundCue {
        event = Objects.requireNonNull(event, "event");
        resource = Objects.requireNonNull(resource, "resource");
        category = Objects.requireNonNull(category, "category");
        if (!CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException("invalid sound category: " + category);
        }
        AudioBusSettings.requireGain(baseGain, "baseGain");
    }
}
