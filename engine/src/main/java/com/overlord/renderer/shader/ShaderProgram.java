package com.overlord.renderer.shader;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3fc;

public final class ShaderProgram implements ShaderBinding, AutoCloseable {
    private final MainThreadGuard guard;
    private final ShaderBackend backend;
    private final int programId;
    private final Map<String, Integer> uniformLocations;
    private boolean cleanedUp;

    public ShaderProgram(
            MainThreadGuard guard, ShaderSourceSet sources, List<String> requiredUniforms) {
        this(guard, sources, requiredUniforms, new OpenGlShaderBackend());
    }

    ShaderProgram(
            MainThreadGuard guard,
            ShaderSourceSet sources,
            List<String> requiredUniforms,
            ShaderBackend backend) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.backend = Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(sources, "sources");
        List<String> uniforms = validateRequiredUniforms(requiredUniforms);
        this.guard.assertMainThread("shader program creation");

        int vertexShader = 0;
        int fragmentShader = 0;
        int createdProgram = 0;
        Map<String, Integer> locations = null;
        Throwable failure = null;
        try {
            vertexShader = backend.createShader(ShaderStage.VERTEX);
            compile(vertexShader, ShaderStage.VERTEX, sources.vertexResource(), sources.vertexSource(), sources.label());
            fragmentShader = backend.createShader(ShaderStage.FRAGMENT);
            compile(
                    fragmentShader,
                    ShaderStage.FRAGMENT,
                    sources.fragmentResource(),
                    sources.fragmentSource(),
                    sources.label());
            createdProgram = backend.createProgram();
            backend.attach(createdProgram, vertexShader);
            backend.attach(createdProgram, fragmentShader);
            backend.link(createdProgram);
            if (!backend.linkSucceeded(createdProgram)) {
                throw new ShaderProgramException(
                        "Failed to link shader program '"
                                + sources.label()
                                + "' ("
                                + sources.vertexResource()
                                + ", "
                                + sources.fragmentResource()
                                + "): "
                                + backend.programInfoLog(createdProgram));
            }
            locations = resolveUniformLocations(createdProgram, uniforms, sources);
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }

        failure = cleanupShader(vertexShader, failure);
        failure = cleanupShader(fragmentShader, failure);
        if (failure != null) {
            failure = cleanupProgram(createdProgram, failure);
            throwUnchecked(failure);
        }

        this.programId = createdProgram;
        this.uniformLocations = Map.copyOf(locations);
    }

    @Override
    public int programId() {
        return programId;
    }

    @Override
    public void use() {
        guard.assertMainThread("shader program use");
        ensureOpen();
        backend.useProgram(programId);
    }

    @Override
    public void setMatrix4(String uniform, Matrix4fc value) {
        guard.assertMainThread("shader program matrix uniform upload");
        ensureOpen();
        backend.uploadMatrix4(locationFor(uniform), Objects.requireNonNull(value, "value").get(new float[16]));
    }

    @Override
    public void setInt(String uniform, int value) {
        guard.assertMainThread("shader program integer uniform upload");
        ensureOpen();
        backend.uploadInt(locationFor(uniform), value);
    }

    @Override
    public void setFloat(String uniform, float value) {
        guard.assertMainThread("shader program float uniform upload");
        ensureOpen();
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("float uniform value must be finite");
        }
        backend.uploadFloat(locationFor(uniform), value);
    }

    @Override
    public void setVector2(String uniform, Vector2fc value) {
        guard.assertMainThread("shader program vector2 uniform upload");
        ensureOpen();
        Vector2fc vector = Objects.requireNonNull(value, "value");
        if (!Float.isFinite(vector.x()) || !Float.isFinite(vector.y())) {
            throw new IllegalArgumentException("vector2 uniform value must be finite");
        }
        backend.uploadVector2(locationFor(uniform), vector.x(), vector.y());
    }

    @Override
    public void setVector3(String uniform, Vector3fc value) {
        guard.assertMainThread("shader program vector3 uniform upload");
        ensureOpen();
        Vector3fc vector = Objects.requireNonNull(value, "value");
        if (!Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("vector3 uniform value must be finite");
        }
        backend.uploadVector3(locationFor(uniform), vector.x(), vector.y(), vector.z());
    }

    public void cleanup() {
        guard.assertMainThread("shader program cleanup");
        if (!cleanedUp) {
            cleanedUp = true;
            backend.deleteProgram(programId);
        }
    }

    @Override
    public void close() {
        cleanup();
    }

    private void compile(
            int shaderId, ShaderStage stage, ResourceLocation resource, String source, String label) {
        backend.setSource(shaderId, source);
        backend.compile(shaderId);
        if (!backend.compileSucceeded(shaderId)) {
            throw new ShaderProgramException(
                    "Failed to compile "
                            + stage
                            + " shader for program '"
                            + label
                            + "' ("
                            + resource
                            + "): "
                            + backend.shaderInfoLog(shaderId));
        }
    }

    private Map<String, Integer> resolveUniformLocations(
            int createdProgram, List<String> uniforms, ShaderSourceSet sources) {
        Map<String, Integer> locations = new LinkedHashMap<>();
        for (String uniform : uniforms) {
            int location = backend.uniformLocation(createdProgram, uniform);
            if (location < 0) {
                throw new ShaderProgramException(
                        "Required uniform '"
                                + uniform
                                + "' is missing from shader program '"
                                + sources.label()
                                + "' ("
                                + sources.vertexResource()
                                + ", "
                                + sources.fragmentResource()
                                + ")");
            }
            locations.put(uniform, location);
        }
        return locations;
    }

    private static List<String> validateRequiredUniforms(List<String> requiredUniforms) {
        Objects.requireNonNull(requiredUniforms, "requiredUniforms");
        Set<String> seen = new LinkedHashSet<>();
        for (String uniform : requiredUniforms) {
            if (uniform == null || uniform.isBlank()) {
                throw new IllegalArgumentException("required uniform names must not be blank");
            }
            if (!seen.add(uniform)) {
                throw new IllegalArgumentException("duplicate required uniform: " + uniform);
            }
        }
        return List.copyOf(requiredUniforms);
    }

    private int locationFor(String uniform) {
        Objects.requireNonNull(uniform, "uniform");
        Integer location = uniformLocations.get(uniform);
        if (location == null) {
            throw new IllegalArgumentException("uniform was not required: " + uniform);
        }
        return location;
    }

    private void ensureOpen() {
        if (cleanedUp) {
            throw new IllegalStateException("shader program has been cleaned up");
        }
    }

    private Throwable cleanupShader(int shaderId, Throwable failure) {
        if (shaderId == 0) {
            return failure;
        }
        try {
            backend.deleteShader(shaderId);
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(failure, cleanupFailure);
        }
        return failure;
    }

    private Throwable cleanupProgram(int createdProgram, Throwable failure) {
        if (createdProgram == 0) {
            return failure;
        }
        try {
            backend.deleteProgram(createdProgram);
        } catch (RuntimeException | Error cleanupFailure) {
            return appendCleanupFailure(failure, cleanupFailure);
        }
        return failure;
    }

    private static Throwable appendCleanupFailure(Throwable failure, Throwable cleanupFailure) {
        if (failure != null) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            return failure;
        }
        return cleanupFailure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }
}
