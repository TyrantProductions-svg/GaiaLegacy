package com.overlord.worlditem.api;

/** One bounded current-live ownership state inside the sole logical service. */
public enum WorldItemLiveState {
    ACTIVE,
    DECODED_DORMANT,
    EVICTED_UNEXPIRED,
    PENDING
}
