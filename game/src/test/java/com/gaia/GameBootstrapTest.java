package com.gaia;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GameBootstrapTest {
    @Test
    void suppressesCleanupFailureOnPrimaryFailure() {
        RuntimeException primary = new RuntimeException("startup failed");
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        GameBootstrap.closeAfterRun(coordinator, primary);

        assertSame(cleanup, primary.getSuppressed()[0]);
    }

    @Test
    void throwsCleanupFailureWhenThereIsNoPrimaryFailure() {
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> GameBootstrap.closeAfterRun(coordinator, null));

        assertSame(cleanup, thrown);
    }

    @Test
    void logsEveryStructuredAssetDiagnostic() {
        AssetLoadReport.Builder report = AssetLoadReport.builder();
        report.add(
                new AssetDiagnostic(
                        AssetSeverity.WARNING,
                        "ASSET_MISSING_REGION",
                        "assets/gaia/blocks/grass.json",
                        ResourceLocation.parse("gaia:not_found"),
                        "textures.top",
                        "Block face references missing region",
                        ResourceLocation.parse("gaia:missing")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(
                    new PrintStream(
                            output, true, StandardCharsets.UTF_8));
            GameBootstrap.logAssetReport(report.build());
        } finally {
            System.setOut(original);
        }

        String logged = output.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("WARNING"));
        assertTrue(logged.contains("ASSET_MISSING_REGION"));
        assertTrue(
                logged.contains("assets/gaia/blocks/grass.json"));
        assertTrue(logged.contains("gaia:not_found"));
        assertTrue(logged.contains("textures.top"));
        assertTrue(
                logged.contains(
                        "Block face references missing region"));
        assertTrue(logged.contains("gaia:missing"));
    }
}
