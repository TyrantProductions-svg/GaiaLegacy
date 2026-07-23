package com.overlord.assets;

import java.util.Objects;
import java.util.stream.Collectors;

public final class AssetLoadException extends RuntimeException {
    private final AssetLoadReport report;

    public AssetLoadException(AssetLoadReport report) {
        super(
                report.errors().stream()
                        .map(d -> d.code() + " at " + d.source() + ": " + d.message())
                        .collect(
                                Collectors.joining(
                                        System.lineSeparator(),
                                        "Asset loading failed:" + System.lineSeparator(),
                                        "")));
        this.report = Objects.requireNonNull(report, "report");
    }

    public AssetLoadReport report() {
        return report;
    }
}
