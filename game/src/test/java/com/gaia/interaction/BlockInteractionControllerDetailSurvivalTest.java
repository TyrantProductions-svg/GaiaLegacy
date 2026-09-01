package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Architecture guards complementing the behavioral conservation tests. */
final class BlockInteractionControllerDetailSurvivalTest {
    @Test
    void soleControllerOwnsOneSurvivalTransactionWithoutRetryQueue() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/gaia/interaction/BlockInteractionController.java"));

        assertTrue(source.contains("Optional<SurvivalDetailEditTransaction>"));
        assertFalse(source.contains("DetailToolController"));
        assertFalse(source.contains("DetailEditQueue"));
        assertFalse(source.contains("retryDetail"));
        assertFalse(source.contains("new BlockRaycast"));
    }

    @Test
    void productionSessionComposesButNeverAutomaticallyInvokesDebugProvisioning()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/gaia/session/GameSessionFactory.java"));

        assertTrue(source.contains("new DetailToolDebugProvisioner"));
        assertFalse(source.contains(".provision("));
    }
}
