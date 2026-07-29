package com.gaia.ui;

public final class GaiaUiAssetLoadException extends IllegalStateException {
    public GaiaUiAssetLoadException(String classpathPath, Throwable cause) {
        super("Failed to load required UI asset " + classpathPath + ": "
                + message(cause), cause);
    }

    private static String message(Throwable cause) {
        if (cause == null) {
            return "unknown failure";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }
}
