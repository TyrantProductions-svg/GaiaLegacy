package com.overlord.audio;

import java.util.Objects;

public final class MusicHandle {
    private final long value;
    private final Object ownerToken;

    public MusicHandle(long value) {
        this(value, null);
    }

    private MusicHandle(long value, Object ownerToken) {
        if (value <= 0L) {
            throw new IllegalArgumentException("music handle must be positive");
        }
        this.value = value;
        this.ownerToken = ownerToken;
    }

    public static Domain newDomain() {
        return new Domain();
    }

    public long value() {
        return value;
    }

    @Override
    public String toString() {
        return "MusicHandle[value=" + value + "]";
    }

    public static final class Domain {
        private final Object ownerToken = new Object();

        private Domain() {}

        public MusicHandle issue(long value) {
            return new MusicHandle(value, ownerToken);
        }

        public MusicHandle requireOwned(MusicHandle handle) {
            Objects.requireNonNull(handle, "handle");
            if (handle.ownerToken != ownerToken) {
                throw new IllegalArgumentException(
                        "foreign music handle: " + handle.value());
            }
            return handle;
        }
    }
}
