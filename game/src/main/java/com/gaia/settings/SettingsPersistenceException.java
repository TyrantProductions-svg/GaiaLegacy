package com.gaia.settings;

/** Signals an I/O failure that prevented settings persistence. */
public final class SettingsPersistenceException extends RuntimeException {
    public SettingsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
