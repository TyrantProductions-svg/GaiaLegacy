package com.overlord.audio;

import java.util.Objects;
import java.util.regex.Pattern;

public record SoundEvent(String id) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    public SoundEvent {
        id = Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid sound event id: " + id);
        }
    }
}
