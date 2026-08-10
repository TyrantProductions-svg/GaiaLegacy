package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameSessionFactoryTest {
    private static final GameSessionConfig CONFIG =
            new GameSessionConfig(8675309L, 3, GameMode.CREATIVE, true);

    @Test
    void constructionIsLazyAndCreateConsumesTheCompleteConfig() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();

        assertTrue(fixture.configs().isEmpty());
        assertTrue(fixture.worlds().isEmpty());

        GameSession session = fixture.factory().create(CONFIG);

        assertEquals(List.of(CONFIG), fixture.configs());
        assertEquals(1, fixture.worlds().size());
        assertEquals(GameSessionState.LOADING, session.state());
        assertEquals(8675309L, fixture.configs().get(0).seed());
        assertEquals(3, fixture.configs().get(0).chunkRadius());
        assertEquals(GameMode.CREATIVE, fixture.configs().get(0).defaultGameMode());
        assertTrue(fixture.configs().get(0).debugHudDefault());
    }

    @Test
    void returningToMainClosesOneSessionAndNextNewWorldOwnsAFreshWorld() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession first = fixture.factory().create(CONFIG);
        var firstWorld = fixture.worlds().get(0);

        first.close();
        first.close();

        GameSession second = fixture.factory().create(CONFIG);
        var secondWorld = fixture.worlds().get(1);

        assertNotSame(firstWorld, secondWorld);
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(0));
        assertTrue(fixture.closeOrder(1).isEmpty());

        second.close();
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(1));
    }

    @Test
    void eachSessionUsesAnIndependentShutdownCoordinator() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession first = fixture.factory().create(CONFIG);
        GameSession second = fixture.factory().create(CONFIG);

        first.close();

        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(0));
        assertTrue(fixture.closeOrder(1).isEmpty());
        assertEquals(GameSessionState.LOADING, second.state());

        second.close();
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(1));
    }

    @Test
    void assemblyFailureClosesAlreadyRegisteredResourcesInReverseOrder() {
        List<String> closeOrder = new ArrayList<>();
        RuntimeException assemblyFailure =
                new RuntimeException("session assembly failed");
        GameSessionFactory factory =
                new GameSessionFactory(
                        (config, world, shutdown) -> {
                            shutdown.register(
                                    "gameplay",
                                    () -> closeOrder.add("gameplay"));
                            shutdown.register(
                                    "world-load",
                                    () -> closeOrder.add("world-load"));
                            throw assemblyFailure;
                        });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> factory.create(CONFIG));

        assertSame(assemblyFailure, thrown);
        assertEquals(
                List.of("world-load", "gameplay"),
                closeOrder);
    }
}
