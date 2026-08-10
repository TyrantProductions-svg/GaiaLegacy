package com.overlord.audio;

@FunctionalInterface
public interface AudioBackendFactory {
    AudioBackend create();
}
