package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedWorldItemPageBackend;
import com.gaia.session.GameSessionFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameBootstrapStreamingCompositionTest {
    @TempDir Path saveRoot;

    @Test
    void bootstrapBuildsOneCombinedBackendPerSessionFromTheConfiguredSaveRoot()
            throws Exception {
        Method compose = requireMethod(
                GameBootstrap.class, "composeStreamingBackends", Path.class);
        Object factory = compose.invoke(null, saveRoot);
        Method open = requireMethod(factory.getClass(), "open", SaveGameId.class);
        SaveGameId id = SaveGameId.parse("0fbd8b6f-31dc-4aaa-9858-441f2a1b9f67");

        Object first = open.invoke(factory, id);
        Object second = open.invoke(factory, id);

        assertNotSame(first, second, "each production session owns a fresh backend graph");
        assertCombinedBackend(first);
        assertCombinedBackend(second);
    }

    @Test
    void gameSessionFactoryConsumesTheCombinedBackendFactoryByConstructorInjection()
            throws Exception {
        Class<?> factoryType = requireClass(
                "com.gaia.session.GameSessionFactory$StreamingBackendFactory");
        long constructorParameters = Arrays.stream(GameSessionFactory.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .filter(factoryType::equals)
                .count();
        Method compose = requireMethod(
                GameBootstrap.class, "composeStreamingBackends", Path.class);

        assertEquals(1L, constructorParameters,
                "production construction must accept exactly one combined backend factory");
        assertEquals(factoryType, compose.getReturnType());
        assertTrue(Modifier.isStatic(compose.getModifiers()));
    }

    @Test
    void bootstrapSaveTargetReusesTheExactSessionBackendGraph() throws Exception {
        Method composeBackends = requireMethod(
                GameBootstrap.class, "composeStreamingBackends", Path.class);
        Object factory = composeBackends.invoke(null, saveRoot);
        SaveGameId id = SaveGameId.parse("0fbd8b6f-31dc-4aaa-9858-441f2a1b9f68");
        Object backends = requireMethod(factory.getClass(), "open", SaveGameId.class)
                .invoke(factory, id);
        Class<?> backendType = requireClass(
                "com.gaia.session.GameSessionFactory$StreamingBackends");
        Method composeTarget = requireMethod(
                GameBootstrap.class,
                "composeStreamedSaveTarget",
                Path.class,
                SaveGameId.class,
                backendType);

        Object target = composeTarget.invoke(null, saveRoot, id, backends);

        assertTrue(target instanceof SaveCoordinator.SaveTarget);
        Object expectedStore = requireMethod(backends.getClass(), "chunkStore")
                .invoke(backends);
        Object expectedPages = requireMethod(backends.getClass(), "worldItems")
                .invoke(backends);
        assertSame(expectedStore, identityField(target, StreamedChunkStore.class),
                "save must reuse the session-owned Chunk store instance");
        assertSame(expectedPages,
                identityField(target, StreamedWorldItemPageBackend.class),
                "save must reuse the session-owned WorldItem backend instance");
    }

    private static void assertCombinedBackend(Object backend) throws Exception {
        Method chunkStore = requireMethod(backend.getClass(), "chunkStore");
        Method worldItems = requireMethod(backend.getClass(), "worldItems");
        Object store = chunkStore.invoke(backend);
        Object itemBackend = worldItems.invoke(backend);

        assertEquals(StreamedChunkStore.class, store.getClass());
        assertEquals(StreamedWorldItemPageBackend.class, itemBackend.getClass());
        Field itemStore = StreamedWorldItemPageBackend.class.getDeclaredField("store");
        itemStore.setAccessible(true);
        assertSame(store, itemStore.get(itemBackend),
                "Chunk and WorldItem persistence must share one semantic root authority");
    }

    private static Method requireMethod(
            Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missing) {
            return fail("Missing Task 11 bootstrap composition seam: "
                    + type.getName() + "." + name);
        }
    }

    private static Class<?> requireClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            return fail("Missing Task 11 bootstrap composition type: " + name);
        }
    }

    private static Object identityField(Object target, Class<?> fieldType)
            throws IllegalAccessException {
        Field field = Arrays.stream(target.getClass().getDeclaredFields())
                .filter(candidate -> candidate.getType().equals(fieldType))
                .findFirst()
                .orElseGet(() -> fail("Missing shared backend field "
                        + fieldType.getName() + " on " + target.getClass().getName()));
        field.setAccessible(true);
        return field.get(target);
    }
}
