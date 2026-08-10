package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gaia.GameLoop;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameSessionApiSurfaceTest {
    @Test
    void gameLoopExposesNoPublicStaticGameplayMutationOrOrchestrationHelpers() {
        List<String> exposed =
                Arrays.stream(GameLoop.class.getDeclaredMethods())
                        .filter(
                                method ->
                                        Modifier.isPublic(
                                                method.getModifiers()))
                        .filter(
                                method ->
                                        Modifier.isStatic(
                                                method.getModifiers()))
                        .map(Method::getName)
                        .sorted()
                        .toList();

        assertEquals(List.of(), exposed);
    }

    @Test
    void fixedFrameOrchestratorIsNotAPublicAlternateSessionEntryPoint()
            throws ClassNotFoundException {
        Class<?> orchestrator =
                Class.forName("com.gaia.GameLoopFrameOrchestrator");

        assertFalse(Modifier.isPublic(orchestrator.getModifiers()));
    }
}
