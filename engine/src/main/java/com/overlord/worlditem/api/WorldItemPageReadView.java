package com.overlord.worlditem.api;

/** One immutable streamed-index generation used for complete restart validation. */
public interface WorldItemPageReadView extends AutoCloseable {
    long indexSequence();

    String checkpointDigest();

    WorldItemPagingCheckpoint checkpoint();

    WorldItemPageSnapshot read(WorldItemPageDescriptor descriptor);

    @Override
    void close();
}
