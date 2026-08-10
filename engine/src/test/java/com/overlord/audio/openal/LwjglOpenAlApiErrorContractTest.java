package com.overlord.audio.openal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LwjglOpenAlApiErrorContractTest {
    private static final int NO_ERROR = 0;
    private static final int INVALID_VALUE = 0xA003;

    @ParameterizedTest(name = "{0} reports its post-call AL error")
    @MethodSource("checkedOperations")
    void checkedAlCallsClearStaleErrorsAndReportOperationSpecificPostCallCode(
            String operation) {
        Deque<Integer> errors = new ArrayDeque<>();
        errors.add(0xA001);
        errors.add(NO_ERROR);
        errors.add(INVALID_VALUE);
        AtomicInteger nativeCalls = new AtomicInteger();

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                LwjglOpenAlApi.checkedAlCall(
                                        operation,
                                        errors::removeFirst,
                                        nativeCalls::incrementAndGet));

        assertEquals(1, nativeCalls.get());
        assertTrue(failure.getMessage().contains(operation));
        assertTrue(failure.getMessage().contains("0xA003"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void checkedAlCallAllowsAZeroPostCallErrorAfterClearingStaleState() {
        Deque<Integer> errors = new ArrayDeque<>();
        errors.add(0xA001);
        errors.add(NO_ERROR);
        errors.add(NO_ERROR);

        assertDoesNotThrow(
                () -> LwjglOpenAlApi.checkedAlCall("queue", errors::removeFirst, () -> {}));
        assertTrue(errors.isEmpty());
    }

    @Test
    void alcHandleAndBooleanFailuresIncludeOperationAndDeviceErrorCode() {
        IntSupplier error = () -> 0xA004;

        IllegalStateException handleFailure =
                assertThrows(
                        IllegalStateException.class,
                        () -> LwjglOpenAlApi.requireAlcHandle("openDevice", 0L, error));
        IllegalStateException callFailure =
                assertThrows(
                        IllegalStateException.class,
                        () -> LwjglOpenAlApi.requireAlcSuccess("makeCurrent", false, error));

        assertTrue(handleFailure.getMessage().contains("openDevice"));
        assertTrue(handleFailure.getMessage().contains("0xA004"));
        assertTrue(callFailure.getMessage().contains("makeCurrent"));
        assertTrue(callFailure.getMessage().contains("0xA004"));
    }

    @Test
    void alcSuccessValuesCannotHideAPostCallDeviceError() {
        IntSupplier error = () -> 0xA004;

        assertThrows(
                IllegalStateException.class,
                () -> LwjglOpenAlApi.requireAlcHandle("createContext", 22L, error));
        assertThrows(
                IllegalStateException.class,
                () -> LwjglOpenAlApi.requireAlcSuccess("makeCurrent", true, error));
        assertEquals(
                22L,
                LwjglOpenAlApi.requireAlcHandle("createContext", 22L, () -> NO_ERROR));
        assertDoesNotThrow(
                () -> LwjglOpenAlApi.requireAlcSuccess("makeCurrent", true, () -> NO_ERROR));
    }

    @ParameterizedTest(name = "{0} closes its nonzero local handle after post-call error")
    @MethodSource("alcCreatorOperations")
    void alcCreatorPostErrorClosesLocalHandleOnceAndSuppressesCleanupFailure(
            String operation) {
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, 0xA004));
        AtomicInteger cleanupCalls = new AtomicInteger();
        RuntimeException cleanupFailure = new RuntimeException("local ALC cleanup failed");

        IllegalStateException primary = assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlcHandleCreation(
                                operation,
                                errors::removeFirst,
                                () -> 11L,
                                handle -> {
                                    assertEquals(11L, handle);
                                    cleanupCalls.incrementAndGet();
                                    throw cleanupFailure;
                                }));

        assertTrue(primary.getMessage().contains(operation));
        assertEquals(1, cleanupCalls.get());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(cleanupFailure, primary.getSuppressed()[0]);
    }

    @Test
    void zeroAlcHandleIsInvalidWithoutAttemptingCleanup() {
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, NO_ERROR));
        AtomicInteger cleanupCalls = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlcHandleCreation(
                                "open default device",
                                errors::removeFirst,
                                () -> 0L,
                                ignored -> cleanupCalls.incrementAndGet()));

        assertEquals(0, cleanupCalls.get());
    }

    @Test
    void sourceCreatorPostErrorDeletesNonzeroLocalSourceExactlyOnce() {
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, INVALID_VALUE));
        AtomicInteger deletes = new AtomicInteger();
        RuntimeException cleanupFailure = new RuntimeException("local source cleanup failed");

        IllegalStateException primary = assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlObjectCreation(
                                "generate source",
                                errors::removeFirst,
                                () -> 21,
                                source -> {
                                    assertEquals(21, source);
                                    deletes.incrementAndGet();
                                    throw cleanupFailure;
                                }));

        assertTrue(primary.getMessage().contains("generate source"));
        assertEquals(1, deletes.get());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(cleanupFailure, primary.getSuppressed()[0]);
    }

    @Test
    void bufferCreatorPostErrorDeletesEveryNonzeroLocalBufferExactlyOnce() {
        int[] created = {31, 32, 33};
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, INVALID_VALUE));
        AtomicInteger deletes = new AtomicInteger();
        AtomicReference<int[]> deleted = new AtomicReference<>();
        RuntimeException cleanupFailure = new RuntimeException("local buffer cleanup failed");

        IllegalStateException primary = assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlObjectArrayCreation(
                                "generate buffers",
                                3,
                                errors::removeFirst,
                                () -> created,
                                buffers -> {
                                    deleted.set(buffers);
                                    deletes.incrementAndGet();
                                    throw cleanupFailure;
                                }));

        assertTrue(primary.getMessage().contains("generate buffers"));
        assertEquals(1, deletes.get());
        assertSame(created, deleted.get());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(cleanupFailure, primary.getSuppressed()[0]);
    }

    @Test
    void zeroInsideCreatedBufferArrayIsInvalidAndDeletesThePartialArrayOnce() {
        int[] partial = {41, 0, 43};
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, NO_ERROR));
        AtomicInteger deletes = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlObjectArrayCreation(
                                "generate buffers",
                                3,
                                errors::removeFirst,
                                () -> partial,
                                buffers -> {
                                    assertArrayEquals(partial, buffers);
                                    deletes.incrementAndGet();
                                }));

        assertEquals(1, deletes.get());
    }

    @Test
    void successfulMakeCurrentWithPostErrorUndoesCurrentAndSuppressesUndoFailure() {
        Deque<Integer> errors = new ArrayDeque<>(java.util.List.of(NO_ERROR, 0xA004));
        AtomicInteger undoCalls = new AtomicInteger();
        RuntimeException undoFailure = new RuntimeException("undo current failed");

        IllegalStateException primary = assertThrows(
                IllegalStateException.class,
                () ->
                        checkedAlcActivation(
                                "make context current",
                                errors::removeFirst,
                                () -> true,
                                () -> {
                                    undoCalls.incrementAndGet();
                                    throw undoFailure;
                                }));

        assertTrue(primary.getMessage().contains("make context current"));
        assertEquals(1, undoCalls.get());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(undoFailure, primary.getSuppressed()[0]);
    }

    @Test
    void alcCapabilityPostErrorFailsConstructionBeforeAlBootstrapAndCleansReverse() {
        CapabilityCheckingApi api =
                new CapabilityCheckingApi(
                        new ArrayDeque<>(java.util.List.of(NO_ERROR, 0xA004)),
                        new ArrayDeque<>(java.util.List.of(NO_ERROR)));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenAlAudioBackend(
                                        api,
                                        MainThreadGuard.captureCurrentThread(),
                                        ignored -> ByteBuffer.allocateDirect(1),
                                        ignored -> new OpenAlAudioBackendTest.RecordingDecoder(1)));

        assertTrue(failure.getMessage().contains("create ALC capabilities"));
        assertTrue(failure.getMessage().contains("0xA004"));
        assertEquals(java.util.List.of("alcCapabilities"), api.capabilityEvents);
        assertReverseInitializationCleanup(api.calls);
    }

    @Test
    void alCapabilityPostErrorIsCheckedOnlyAfterBootstrapAndCleansReverse() {
        CapabilityCheckingApi api =
                new CapabilityCheckingApi(
                        new ArrayDeque<>(java.util.List.of(NO_ERROR, NO_ERROR)),
                        new ArrayDeque<>(java.util.List.of(INVALID_VALUE)));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenAlAudioBackend(
                                        api,
                                        MainThreadGuard.captureCurrentThread(),
                                        ignored -> ByteBuffer.allocateDirect(1),
                                        ignored -> new OpenAlAudioBackendTest.RecordingDecoder(1)));

        assertTrue(failure.getMessage().contains("create AL capabilities"));
        assertTrue(failure.getMessage().contains("0xA003"));
        assertEquals(
                java.util.List.of("alcCapabilities", "alCapabilities", "alError"),
                api.capabilityEvents);
        assertReverseInitializationCleanup(api.calls);
    }

    private static void assertReverseInitializationCleanup(java.util.List<String> calls) {
        assertTrue(calls.indexOf("makeContextCurrent:0") < calls.indexOf("destroyContext:22"));
        assertTrue(calls.indexOf("destroyContext:22") < calls.indexOf("closeDevice:11"));
    }

    private static final class CapabilityCheckingApi
            extends OpenAlAudioBackendTest.RecordingOpenAlApi {
        private final Deque<Integer> alcErrors;
        private final Deque<Integer> alErrors;
        private final java.util.List<String> capabilityEvents = new java.util.ArrayList<>();
        private boolean alCapabilitiesCreated;

        private CapabilityCheckingApi(Deque<Integer> alcErrors, Deque<Integer> alErrors) {
            this.alcErrors = alcErrors;
            this.alErrors = alErrors;
        }

        @Override
        public void createCapabilities(long device) {
            calls.add("createCapabilities:" + device);
            LwjglOpenAlApi.checkedCapabilityInitialization(
                    () -> capabilityEvents.add("alcCapabilities"),
                    alcErrors::removeFirst,
                    () -> {
                        capabilityEvents.add("alCapabilities");
                        alCapabilitiesCreated = true;
                    },
                    () -> {
                        if (!alCapabilitiesCreated) {
                            throw new AssertionError(
                                    "AL error queried before capability bootstrap");
                        }
                        capabilityEvents.add("alError");
                        return alErrors.removeFirst();
                    });
        }
    }

    private static Stream<String> checkedOperations() {
        return Stream.of("upload", "queue", "play", "gain", "stop", "delete");
    }

    private static Stream<String> alcCreatorOperations() {
        return Stream.of("open default device", "create context");
    }

    private static long checkedAlcHandleCreation(
            String operation,
            IntSupplier errors,
            LongSupplier create,
            LongConsumer cleanup) {
        return (long)
                invokeCheckedSeam(
                        "checkedAlcHandleCreation",
                        new Class<?>[] {
                            String.class,
                            IntSupplier.class,
                            LongSupplier.class,
                            LongConsumer.class
                        },
                        operation,
                        errors,
                        create,
                        cleanup);
    }

    private static int checkedAlObjectCreation(
            String operation,
            IntSupplier errors,
            IntSupplier create,
            IntConsumer cleanup) {
        return (int)
                invokeCheckedSeam(
                        "checkedAlObjectCreation",
                        new Class<?>[] {
                            String.class,
                            IntSupplier.class,
                            IntSupplier.class,
                            IntConsumer.class
                        },
                        operation,
                        errors,
                        create,
                        cleanup);
    }

    private static int[] checkedAlObjectArrayCreation(
            String operation,
            int count,
            IntSupplier errors,
            Supplier<int[]> create,
            Consumer<int[]> cleanup) {
        return (int[])
                invokeCheckedSeam(
                        "checkedAlObjectArrayCreation",
                        new Class<?>[] {
                            String.class,
                            int.class,
                            IntSupplier.class,
                            Supplier.class,
                            Consumer.class
                        },
                        operation,
                        count,
                        errors,
                        create,
                        cleanup);
    }

    private static void checkedAlcActivation(
            String operation,
            IntSupplier errors,
            BooleanSupplier activate,
            Runnable undo) {
        invokeCheckedSeam(
                "checkedAlcActivation",
                new Class<?>[] {
                    String.class,
                    IntSupplier.class,
                    BooleanSupplier.class,
                    Runnable.class
                },
                operation,
                errors,
                activate,
                undo);
    }

    private static Object invokeCheckedSeam(
            String name, Class<?>[] parameterTypes, Object... arguments) {
        Method method;
        try {
            method = LwjglOpenAlApi.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (NoSuchMethodException missingSeam) {
            throw new AssertionError(
                    "LwjglOpenAlApi must expose package-private checked seam " + name,
                    missingSeam);
        } catch (IllegalAccessException reflectionFailure) {
            throw new AssertionError("Could not invoke checked seam " + name, reflectionFailure);
        } catch (InvocationTargetException invocationFailure) {
            Throwable cause = invocationFailure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error errorFailure) {
                throw errorFailure;
            }
            throw new AssertionError("Checked seam threw checked failure " + name, cause);
        }
    }
}
