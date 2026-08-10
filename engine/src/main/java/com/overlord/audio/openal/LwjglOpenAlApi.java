package com.overlord.audio.openal;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

public final class LwjglOpenAlApi implements OpenAlApi {
    private static final int MAXIMUM_STALE_ERRORS = 32;
    private long currentDevice;

    @Override
    public long openDefaultDevice() {
        long[] createdDevice = new long[1];
        long device = checkedAlcHandleCreation(
                "open default device",
                () -> ALC10.alcGetError(createdDevice[0]),
                () -> createdDevice[0] = ALC10.alcOpenDevice((ByteBuffer) null),
                LwjglOpenAlApi::closeFailedDevice);
        currentDevice = device;
        return device;
    }

    @Override
    public long createContext(long device) {
        return checkedAlcHandleCreation(
                "create context",
                () -> ALC10.alcGetError(device),
                () -> ALC10.alcCreateContext(device, (int[]) null),
                context -> destroyFailedContext(device, context));
    }

    @Override
    public void makeContextCurrent(long context) {
        checkedAlcActivation(
                "make context current",
                () -> ALC10.alcGetError(currentDevice),
                () -> ALC10.alcMakeContextCurrent(context),
                this::undoCurrentContext);
        if (context == 0L) {
            clearCurrentAlCapabilities();
        }
    }

    @Override
    public void createCapabilities(long device) {
        ALCCapabilities[] deviceCapabilities = new ALCCapabilities[1];
        checkedCapabilityInitialization(
                () -> deviceCapabilities[0] = ALC.createCapabilities(device),
                () -> ALC10.alcGetError(device),
                () -> AL.createCapabilities(deviceCapabilities[0]),
                AL10::alGetError);
    }

    @Override
    public int generateSource() {
        return checkedAlObjectCreation(
                "generate source",
                AL10::alGetError,
                AL10::alGenSources,
                source ->
                        checkedAlCall(
                                "delete source after failed generation",
                                AL10::alGetError,
                                () -> AL10.alDeleteSources(source)));
    }

    @Override
    public int[] generateBuffers(int count) {
        return checkedAlObjectArrayCreation(
                "generate buffers",
                count,
                AL10::alGetError,
                () -> {
                    int[] buffers = new int[count];
                    AL10.alGenBuffers(buffers);
                    return buffers;
                },
                LwjglOpenAlApi::deleteCreatedBuffers);
    }

    @Override
    public void uploadPcm16(
            int buffer, int channels, int sampleRate, ShortBuffer samples) {
        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        checkedAlCall(
                "upload PCM",
                AL10::alGetError,
                () -> AL10.alBufferData(buffer, format, samples, sampleRate));
    }

    @Override
    public void queueBuffer(int source, int buffer) {
        checkedAlCall(
                "queue buffer",
                AL10::alGetError,
                () -> AL10.alSourceQueueBuffers(source, buffer));
    }

    @Override
    public int processedBufferCount(int source) {
        int[] processed = new int[1];
        checkedAlCall(
                "query processed buffers",
                AL10::alGetError,
                () -> processed[0] = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED));
        return processed[0];
    }

    @Override
    public int unqueueProcessedBuffer(int source) {
        int[] buffer = new int[1];
        checkedAlCall(
                "unqueue processed buffer",
                AL10::alGetError,
                () -> buffer[0] = AL10.alSourceUnqueueBuffers(source));
        return buffer[0];
    }

    @Override
    public int queuedBufferCount(int source) {
        int[] queued = new int[1];
        checkedAlCall(
                "query queued buffers",
                AL10::alGetError,
                () -> queued[0] = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
        return queued[0];
    }

    @Override
    public boolean isSourcePlaying(int source) {
        int[] state = new int[1];
        checkedAlCall(
                "query source state",
                AL10::alGetError,
                () -> state[0] = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE));
        return state[0] == AL10.AL_PLAYING;
    }

    @Override
    public void playSource(int source) {
        checkedAlCall("play source", AL10::alGetError, () -> AL10.alSourcePlay(source));
    }

    @Override
    public void setSourceGain(int source, float gain) {
        checkedAlCall(
                "set source gain",
                AL10::alGetError,
                () -> AL10.alSourcef(source, AL10.AL_GAIN, gain));
    }

    @Override
    public void stopSource(int source) {
        checkedAlCall("stop source", AL10::alGetError, () -> AL10.alSourceStop(source));
    }

    @Override
    public void deleteSource(int source) {
        checkedAlCall(
                "delete source", AL10::alGetError, () -> AL10.alDeleteSources(source));
    }

    @Override
    public void deleteBuffers(int[] buffers) {
        checkedAlCall(
                "delete buffers", AL10::alGetError, () -> AL10.alDeleteBuffers(buffers));
    }

    @Override
    public void destroyContext(long context) {
        clearErrors("destroy context", () -> ALC10.alcGetError(currentDevice));
        ALC10.alcDestroyContext(context);
        requireNoError("destroy context", ALC10.alcGetError(currentDevice));
    }

    @Override
    public void closeDevice(long device) {
        clearErrors("close device", () -> ALC10.alcGetError(device));
        boolean closed = ALC10.alcCloseDevice(device);
        requireAlcSuccess(
                "close device",
                closed,
                () -> closed ? ALC10.ALC_NO_ERROR : ALC10.alcGetError(device));
        if (currentDevice == device) {
            currentDevice = 0L;
        }
    }

    static void checkedAlCall(String operation, IntSupplier errors, Runnable call) {
        clearErrors(operation, errors);
        call.run();
        requireNoError(operation, errors.getAsInt());
    }

    static long checkedAlcHandleCreation(
            String operation,
            IntSupplier errors,
            LongSupplier create,
            LongConsumer cleanup) {
        clearErrors(operation, errors);
        long handle = create.getAsLong();
        int postCallError = errors.getAsInt();
        if (handle == 0L || postCallError != ALC10.ALC_NO_ERROR) {
            IllegalStateException failure = error(operation, postCallError);
            if (handle != 0L) {
                cleanupLocally(failure, () -> cleanup.accept(handle));
            }
            throw failure;
        }
        return handle;
    }

    static int checkedAlObjectCreation(
            String operation,
            IntSupplier errors,
            IntSupplier create,
            IntConsumer cleanup) {
        clearErrors(operation, errors);
        int object = create.getAsInt();
        int postCallError = errors.getAsInt();
        if (object == 0 || postCallError != AL10.AL_NO_ERROR) {
            IllegalStateException failure = error(operation, postCallError);
            if (object != 0) {
                cleanupLocally(failure, () -> cleanup.accept(object));
            }
            throw failure;
        }
        return object;
    }

    static int[] checkedAlObjectArrayCreation(
            String operation,
            int count,
            IntSupplier errors,
            Supplier<int[]> create,
            Consumer<int[]> cleanup) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        clearErrors(operation, errors);
        int[] objects = create.get();
        int postCallError = errors.getAsInt();
        boolean invalidObjects = objects == null || objects.length != count;
        if (!invalidObjects) {
            for (int object : objects) {
                if (object == 0) {
                    invalidObjects = true;
                    break;
                }
            }
        }
        if (invalidObjects || postCallError != AL10.AL_NO_ERROR) {
            IllegalStateException failure = postCallError == AL10.AL_NO_ERROR
                    ? new IllegalStateException(operation + " returned invalid OpenAL objects")
                    : error(operation, postCallError);
            if (containsCreatedObject(objects)) {
                cleanupLocally(failure, () -> cleanup.accept(objects));
            }
            throw failure;
        }
        return objects;
    }

    static void checkedAlcActivation(
            String operation,
            IntSupplier errors,
            BooleanSupplier activate,
            Runnable undo) {
        clearErrors(operation, errors);
        boolean activated = activate.getAsBoolean();
        int postCallError = errors.getAsInt();
        if (!activated || postCallError != ALC10.ALC_NO_ERROR) {
            IllegalStateException failure = error(operation, postCallError);
            if (activated) {
                cleanupLocally(failure, undo);
            }
            throw failure;
        }
    }

    static void checkedCapabilityInitialization(
            Runnable createAlcCapabilities,
            IntSupplier alcErrors,
            Runnable createAlCapabilities,
            IntSupplier alErrors) {
        clearErrors("create ALC capabilities", alcErrors);
        createAlcCapabilities.run();
        requireNoError("create ALC capabilities", alcErrors.getAsInt());

        createAlCapabilities.run();
        requireNoError("create AL capabilities", alErrors.getAsInt());
    }

    static long requireAlcHandle(
            String operation, long handle, IntSupplier errors) {
        int error = errors.getAsInt();
        if (handle == 0L || error != ALC10.ALC_NO_ERROR) {
            throw error(operation, error);
        }
        return handle;
    }

    static void requireAlcSuccess(
            String operation, boolean success, IntSupplier errors) {
        int error = errors.getAsInt();
        if (!success || error != ALC10.ALC_NO_ERROR) {
            throw error(operation, error);
        }
    }

    private static void clearErrors(String operation, IntSupplier errors) {
        for (int count = 0; count < MAXIMUM_STALE_ERRORS; count++) {
            if (errors.getAsInt() == AL10.AL_NO_ERROR) {
                return;
            }
        }
        throw new IllegalStateException(operation + " could not clear stale OpenAL errors");
    }

    private static void requireNoError(String operation, int error) {
        if (error != AL10.AL_NO_ERROR) {
            throw error(operation, error);
        }
    }

    private static IllegalStateException error(String operation, int error) {
        return new IllegalStateException(
                "OPENAL_ERROR operation="
                        + operation
                        + " code="
                        + String.format(Locale.ROOT, "0x%04X", error));
    }

    private static void cleanupLocally(Throwable primaryFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static boolean containsCreatedObject(int[] objects) {
        if (objects == null) {
            return false;
        }
        for (int object : objects) {
            if (object != 0) {
                return true;
            }
        }
        return false;
    }

    private static void closeFailedDevice(long device) {
        boolean closed = ALC10.alcCloseDevice(device);
        requireAlcSuccess(
                "close device after failed open",
                closed,
                () -> closed ? ALC10.ALC_NO_ERROR : ALC10.alcGetError(device));
    }

    private static void destroyFailedContext(long device, long context) {
        ALC10.alcDestroyContext(context);
        requireNoError(
                "destroy context after failed creation",
                ALC10.alcGetError(device));
    }

    private void undoCurrentContext() {
        boolean undone = ALC10.alcMakeContextCurrent(0L);
        try {
            requireAlcSuccess(
                    "undo current context after failed activation",
                    undone,
                    () -> ALC10.alcGetError(currentDevice));
        } finally {
            if (undone) {
                clearCurrentAlCapabilities();
            }
        }
    }

    private static void clearCurrentAlCapabilities() {
        AL.setCurrentThread(null);
        AL.setCurrentProcess(null);
    }

    private static void deleteCreatedBuffers(int[] buffers) {
        int nonzeroCount = 0;
        for (int buffer : buffers) {
            if (buffer != 0) {
                nonzeroCount++;
            }
        }
        int[] created = new int[nonzeroCount];
        int index = 0;
        for (int buffer : buffers) {
            if (buffer != 0) {
                created[index++] = buffer;
            }
        }
        checkedAlCall(
                "delete buffers after failed generation",
                AL10::alGetError,
                () -> AL10.alDeleteBuffers(created));
    }
}
