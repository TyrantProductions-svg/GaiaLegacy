package com.overlord.worlditem.api;

/** Read-only durable page boundary consumed by the WorldItem authority. */
public interface WorldItemPageSource {
    WorldItemPageReadView openReadView();
}
