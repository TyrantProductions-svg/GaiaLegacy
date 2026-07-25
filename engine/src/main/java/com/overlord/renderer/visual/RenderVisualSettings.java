package com.overlord.renderer.visual;

import java.util.Objects;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class RenderVisualSettings {
    private final Vector3f sunDirection;
    private final float ambientStrength;
    private final float directionalStrength;
    private final LinearColor skyTop;
    private final LinearColor skyHorizon;
    private final LinearColor fogColor;
    private final float fogStart;
    private final float fogEnd;
    private final GammaPath gammaPath;

    public RenderVisualSettings(
            Vector3fc sunDirection,
            float ambientStrength,
            float directionalStrength,
            LinearColor skyTop,
            LinearColor skyHorizon,
            LinearColor fogColor,
            float fogStart,
            float fogEnd,
            GammaPath gammaPath) {
        this.sunDirection = normalizeSunDirection(sunDirection);
        this.ambientStrength = validateNonNegativeFinite(ambientStrength, "ambientStrength");
        this.directionalStrength = validateNonNegativeFinite(directionalStrength, "directionalStrength");
        this.skyTop = Objects.requireNonNull(skyTop, "skyTop");
        this.skyHorizon = Objects.requireNonNull(skyHorizon, "skyHorizon");
        this.fogColor = Objects.requireNonNull(fogColor, "fogColor");
        this.fogStart = validateNonNegativeFinite(fogStart, "fogStart");
        this.fogEnd = validateNonNegativeFinite(fogEnd, "fogEnd");
        if (this.fogEnd <= this.fogStart) {
            throw new IllegalArgumentException("fogEnd must be greater than fogStart");
        }
        this.gammaPath = Objects.requireNonNull(gammaPath, "gammaPath");
    }

    public static RenderVisualSettings milestoneOneDefaults() {
        LinearColor skyHorizon = new LinearColor(0.350f, 0.570f, 0.780f);
        return new RenderVisualSettings(
                new Vector3f(-0.45f, 0.85f, -0.30f),
                0.38f,
                0.72f,
                new LinearColor(0.035f, 0.160f, 0.470f),
                skyHorizon,
                skyHorizon,
                64.0f,
                160.0f,
                GammaPath.SHADER_SRGB_DECODE_ENCODE);
    }

    public Vector3f sunDirection() {
        return new Vector3f(sunDirection);
    }

    public float ambientStrength() {
        return ambientStrength;
    }

    public float directionalStrength() {
        return directionalStrength;
    }

    public LinearColor skyTop() {
        return skyTop;
    }

    public LinearColor skyHorizon() {
        return skyHorizon;
    }

    public LinearColor fogColor() {
        return fogColor;
    }

    public float fogStart() {
        return fogStart;
    }

    public float fogEnd() {
        return fogEnd;
    }

    public GammaPath gammaPath() {
        return gammaPath;
    }

    private static Vector3f normalizeSunDirection(Vector3fc direction) {
        Objects.requireNonNull(direction, "sunDirection");
        float x = direction.x();
        float y = direction.y();
        float z = direction.z();
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("sunDirection must be finite");
        }
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (length == 0.0d) {
            throw new IllegalArgumentException("sunDirection must not be zero");
        }
        return new Vector3f((float) (x / length), (float) (y / length), (float) (z / length));
    }

    private static float validateNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}
