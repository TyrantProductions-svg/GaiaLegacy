package com.gaia.save.format;

/** Codec boundary for one independently versioned archive section. */
public interface SaveSectionCodec<T> {
    SaveSectionId sectionId();

    int codecVersion();

    boolean required();

    byte[] encode(T value);

    T decode(byte[] bytes);
}
