package com.overlord.renderer.pass;

import static com.overlord.renderer.pass.FeedbackVisualPassTestSupport.INCOMING;
import static com.overlord.renderer.pass.FeedbackVisualPassTestSupport.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.FirstPersonItemVisual;
import com.overlord.renderer.feedback.FirstPersonMovementVisual;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.VisualTransform;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.voxel.BlockFace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class FirstPersonItemVisualPassTest {
    private static final WorldItemFaceRegions FACES = distinctFaces();

    @Test
    void placeBreakAndDropTransformsReachTheActualDrawPath() {
        Fixture fixture = new Fixture();
        List<VisualTransform> transforms = List.of(
                new VisualTransform(0.01f, -0.02f, 0.03f, 4, 5, 6, 0.9f, 0.8f),
                new VisualTransform(-0.04f, 0.05f, -0.06f, -7, 8, -9, 1.1f, 0.7f),
                new VisualTransform(0.07f, 0.08f, -0.09f, 10, -11, 12, 0.95f, 0.6f));

        for (VisualTransform transform : transforms) {
            fixture.pass.render(context(frame(transform), ignored -> {}), new RenderQueue());
        }

        assertEquals(3, fixture.cube.drawCalls);
        assertEquals(List.of(0.8f, 0.7f, 0.6f), fixture.shader.floats.get("visualAlpha"));
        assertEquals(expected(transforms.get(0)), fixture.shader.models.get(0));
        assertEquals(expected(transforms.get(1)), fixture.shader.models.get(1));
        assertEquals(expected(transforms.get(2)), fixture.shader.models.get(2));
        assertEquals(3, fixture.state.restoreCalls);
        assertEquals(INCOMING, fixture.state.current);
    }

    @Test
    void identityUsesCanonicalHeldItemBaselineAndRestoresState() {
        Fixture fixture = new Fixture();
        List<Long> metrics = new ArrayList<>();

        fixture.pass.render(
                context(frame(VisualTransform.identity()), metrics::add),
                new RenderQueue());

        assertEquals(
                new Matrix4f()
                        .translation(0.58f, -0.58f, -1.15f)
                        .rotateX((float) Math.toRadians(5.0f))
                        .rotateY((float) Math.toRadians(8.0f))
                        .rotateZ((float) Math.toRadians(-3.0f))
                        .scale(0.35f)
                        .translate(-0.5f, -0.5f, -0.5f),
                fixture.shader.models.get(0));
        assertEquals(List.of(12L), metrics);
        assertEquals(
                new RenderStateSpec(
                        true,
                        DepthFunction.LESS,
                        true,
                        BlendMode.ALPHA,
                        true,
                        false,
                        0.0f,
                        0.0f),
                fixture.state.applied);
        assertEquals(1, fixture.state.clearDepthCalls);
        for (BlockFace face : BlockFace.values()) {
            TextureRegion region = FACES.region(face);
            assertEquals(
                    List.of(region.uMin()),
                    fixture.shader.floats.get("uMin[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.uMax()),
                    fixture.shader.floats.get("uMax[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.vMin()),
                    fixture.shader.floats.get("vMin[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.vMax()),
                    fixture.shader.floats.get("vMax[" + face.ordinal() + "]"));
        }
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void absentHeldItemDoesNotClearDepthOrTouchRenderState() {
        Fixture fixture = new Fixture();

        fixture.pass.render(
                context(InteractionFeedbackFrame.hidden(), ignored -> {}),
                new RenderQueue());

        assertEquals(0, fixture.state.clearDepthCalls);
        assertEquals(0, fixture.state.captureCalls);
        assertEquals(0, fixture.cube.drawCalls);
    }

    @Test
    void depthAndCullingAreRestoredToDisabledIncomingState() {
        Fixture fixture = new Fixture();
        RenderStateSnapshot incoming = new RenderStateSnapshot(
                false, false, false,
                1, 2, 3, 4, 5, 6,
                false, 7, 8, 9);
        fixture.state.current = incoming;

        fixture.pass.render(
                context(frame(VisualTransform.identity()), ignored -> {}),
                new RenderQueue());

        assertTrue(fixture.state.applied.depthTest());
        assertTrue(fixture.state.applied.depthWrite());
        assertTrue(fixture.state.applied.cullFace());
        assertEquals(incoming, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void asymmetricLogPoseShowsTopAndTwoBarkFacesWithoutExposingBottom() {
        Fixture fixture = new Fixture();

        fixture.pass.render(
                context(frame(VisualTransform.identity()), ignored -> {}),
                new RenderQueue());

        Matrix4f model = fixture.shader.models.get(0);
        assertTrue(faceVisibility(model, BlockFace.UP) >= 0.40f);
        assertTrue(faceVisibility(model, BlockFace.DOWN) <= -0.40f);
        assertTrue(faceVisibility(model, BlockFace.SOUTH) >= 0.60f);
        assertTrue(faceVisibility(model, BlockFace.WEST) >= 0.45f);
        assertTrue(faceVisibility(model, BlockFace.EAST) <= -0.45f);
    }

    @Test
    void productionHeldCubeIsPositiveConvexAndSafelyBehindNearPlane() {
        Fixture fixture = new Fixture();

        fixture.pass.render(
                productionContext(frame(VisualTransform.identity())),
                new RenderQueue());

        Matrix4f model = fixture.shader.models.get(0);
        assertEquals(0.042875f, model.determinant3x3(), 1.0e-6f);
        assertEquals(0.35f,
                model.transformDirection(new Vector3f(1, 0, 0)).length(), 1.0e-6f);
        assertEquals(0.35f,
                model.transformDirection(new Vector3f(0, 1, 0)).length(), 1.0e-6f);
        assertEquals(0.35f,
                model.transformDirection(new Vector3f(0, 0, 1)).length(), 1.0e-6f);

        Vector3f[] corners = transformedCorners(model);
        Vector3f edgeX = new Vector3f(corners[4]).sub(corners[0]);
        Vector3f edgeY = new Vector3f(corners[2]).sub(corners[0]);
        Vector3f edgeZ = new Vector3f(corners[1]).sub(corners[0]);
        assertTrue(new Vector3f(edgeX).cross(edgeY).dot(edgeZ) > 0.0f);
        for (Vector3f corner : corners) {
            assertTrue(corner.z <= -GameConfig.Rendering.NEAR_PLANE - 0.50f);
        }
        Vector3f center = model.transformPosition(new Vector3f(0.5f, 0.5f, 0.5f));
        assertEquals(0.58f, center.x, 1.0e-6f);
        assertEquals(-0.58f, center.y, 1.0e-6f);
        assertEquals(-1.15f, center.z, 1.0e-6f);
    }

    @Test
    void heldPassUsesPerspectiveWithIdentityViewInsteadOfWorldCameraTranslation() {
        Fixture fixture = new Fixture();
        RenderContext context = productionContext(frame(VisualTransform.identity()));

        fixture.pass.render(context, new RenderQueue());

        assertEquals(List.of(productionProjection()), fixture.shader.projections);
        assertEquals(List.of(new Matrix4f()), fixture.shader.views);
        Matrix4f projection = fixture.shader.projections.get(0);
        assertTrue(projection.m00() > 0.0f);
        assertTrue(projection.m11() > 0.0f);
        assertEquals(-1.0f, projection.m23(), 1.0e-6f);
        assertEquals(0.0f, projection.m33(), 1.0e-6f);
    }

    @Test
    void neutralDiagnosticCubeIsRigidConvexAndCameraSafe() {
        Matrix4f diagnostic = new Matrix4f()
                .translation(0.42f, -0.36f, -1.20f)
                .scale(0.30f)
                .translate(-0.5f, -0.5f, -0.5f);

        Vector3f[] corners = transformedCorners(diagnostic);

        assertEquals(0.027f, diagnostic.determinant3x3(), 1.0e-6f);
        for (Vector3f corner : corners) {
            assertTrue(corner.z <= -1.05f);
        }
        Vector3f edgeX = new Vector3f(corners[4]).sub(corners[0]);
        Vector3f edgeY = new Vector3f(corners[2]).sub(corners[0]);
        Vector3f edgeZ = new Vector3f(corners[1]).sub(corners[0]);
        assertTrue(new Vector3f(edgeX).cross(edgeY).dot(edgeZ) > 0.0f);
    }

    @Test
    void movementResponseAndActionTransformBothContributeInOrder() {
        Fixture fixture = new Fixture();
        VisualTransform action =
                new VisualTransform(0.03f, -0.02f, 0.01f, 4, 5, 6, 0.9f, 0.8f);
        FirstPersonMovementVisual movement =
                new FirstPersonMovementVisual(0.012f, -0.025f, 0.18f);

        fixture.pass.render(
                context(frame(action, movement), ignored -> {}),
                new RenderQueue());

        assertEquals(expected(action, movement), fixture.shader.models.get(0));
        assertNotEquals(expected(action, FirstPersonMovementVisual.identity()),
                fixture.shader.models.get(0));
    }

    @Test
    void drawFailureRestoresExactIncomingStateAndDoesNotRecordMetrics() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("draw failed");
        fixture.cube.failure = failure;
        List<Long> metrics = new ArrayList<>();

        RuntimeException escaped = assertThrows(
                RuntimeException.class,
                () -> fixture.pass.render(
                        context(frame(VisualTransform.identity()), metrics::add),
                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(List.of(), metrics);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    private static InteractionFeedbackFrame frame(VisualTransform transform) {
        return frame(transform, FirstPersonMovementVisual.identity());
    }

    private static InteractionFeedbackFrame frame(
            VisualTransform transform,
            FirstPersonMovementVisual movement) {
        return new InteractionFeedbackFrame(
                new FeedbackVisibility(true, true, true, false),
                Optional.empty(),
                List.of(),
                new ParticleRenderBatch(List.of()),
                Optional.of(new FirstPersonItemVisual(FACES, transform)),
                movement,
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                List.of(),
                List.of());
    }

    private static Matrix4f expected(VisualTransform transform) {
        return expected(transform, FirstPersonMovementVisual.identity());
    }

    private static Matrix4f expected(
            VisualTransform transform,
            FirstPersonMovementVisual movement) {
        return new Matrix4f()
                .translation(0.58f, -0.58f, -1.15f)
                .rotateX((float) Math.toRadians(5.0f))
                .rotateY((float) Math.toRadians(8.0f))
                .rotateZ((float) Math.toRadians(-3.0f))
                .translate(
                        -movement.translationX() * 0.65f,
                        -movement.translationY() * 0.55f,
                        0.0f)
                .rotateZ((float) Math.toRadians(-movement.rollDegrees() * 0.5f))
                .translate(
                        transform.translationX(),
                        transform.translationY(),
                        transform.translationZ())
                .rotateX((float) Math.toRadians(transform.pitchDegrees()))
                .rotateY((float) Math.toRadians(transform.yawDegrees()))
                .rotateZ((float) Math.toRadians(transform.rollDegrees()))
                .scale(0.35f * transform.scale())
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private static WorldItemFaceRegions distinctFaces() {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            int x = face.ordinal() * 7;
            int y = face.ordinal() * 5;
            faces.put(face, new TextureRegion(
                    ResourceLocation.parse("test:held_" + face.name().toLowerCase()),
                    x, y, 3, 4, 64, 64));
        }
        return new WorldItemFaceRegions(faces);
    }

    private static RenderContext productionContext(InteractionFeedbackFrame feedback) {
        return new RenderContext(
                productionProjection(),
                new Matrix4f().translation(-120.0f, 45.0f, 900.0f),
                RenderVisualSettings.milestoneOneDefaults(),
                ignored -> {},
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1.0f, 1.0f),
                feedback);
    }

    private static Matrix4f productionProjection() {
        return new Matrix4f().perspective(
                (float) Math.toRadians(GameConfig.Rendering.FOV),
                1024.0f / 768.0f,
                GameConfig.Rendering.NEAR_PLANE,
                GameConfig.Rendering.FAR_PLANE);
    }

    private static Vector3f[] transformedCorners(Matrix4f model) {
        Vector3f[] corners = new Vector3f[8];
        int index = 0;
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    corners[index++] = model.transformPosition(new Vector3f(x, y, z));
                }
            }
        }
        return corners;
    }

    private static float faceVisibility(Matrix4f model, BlockFace face) {
        Vector3f center = model.transformPosition(new Vector3f(0.5f, 0.5f, 0.5f));
        Vector3f towardCamera = center.negate(new Vector3f()).normalize();
        Vector3f normal = switch (face) {
            case NORTH -> new Vector3f(0, 0, -1);
            case SOUTH -> new Vector3f(0, 0, 1);
            case UP -> new Vector3f(0, 1, 0);
            case DOWN -> new Vector3f(0, -1, 0);
            case WEST -> new Vector3f(-1, 0, 0);
            case EAST -> new Vector3f(1, 0, 0);
        };
        return model.transformDirection(normal).normalize().dot(towardCamera);
    }

    private static final class Fixture {
        private final FeedbackVisualPassTestSupport.RecordingState state =
                new FeedbackVisualPassTestSupport.RecordingState();
        private final FeedbackVisualPassTestSupport.RecordingShader shader =
                new FeedbackVisualPassTestSupport.RecordingShader();
        private final FeedbackVisualPassTestSupport.RecordingTexture texture =
                new FeedbackVisualPassTestSupport.RecordingTexture();
        private final FeedbackVisualPassTestSupport.RecordingCube cube =
                new FeedbackVisualPassTestSupport.RecordingCube();
        private final FirstPersonItemVisualPass pass =
                new FirstPersonItemVisualPass(state, shader, texture, cube);
    }
}
