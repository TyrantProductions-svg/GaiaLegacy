package com.overlord.renderer.pass;

import static com.overlord.renderer.pass.FeedbackVisualPassTestSupport.INCOMING;
import static com.overlord.renderer.pass.FeedbackVisualPassTestSupport.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.TransientBlockVisual;
import com.overlord.renderer.feedback.VisualTransform;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class TransientBlockVisualPassTest {
    private static final BlockVisualCoordinate CELL = new BlockVisualCoordinate(2, 3, 4);

    @Test
    void placementAndBreakEnvelopesReachDrawBackendWithOldSixFaceMaterial() {
        Fixture fixture = new Fixture();
        WorldItemFaceRegions oldFaces = distinctFaces();
        VisualTransform placementInitial = new VisualTransform(0, 0, 0, 0, 0, 0, 0.85f, 1);
        VisualTransform placementLater = new VisualTransform(0, 0, 0, 0, 0, 0, 0.99f, 1);
        VisualTransform breaking = new VisualTransform(0, -0.02f, 0, 0, 0, 0, 0.78f, 0.58f);
        List<TransientBlockVisual> visuals = List.of(
                visual(TransientBlockVisual.Type.PLACEMENT, 1, placementInitial, oldFaces),
                visual(TransientBlockVisual.Type.PLACEMENT, 2, placementLater, oldFaces),
                visual(TransientBlockVisual.Type.BREAK, 3, breaking, oldFaces));

        fixture.pass.render(context(frame(visuals), ignored -> {}), new RenderQueue());

        assertEquals(3, fixture.cube.drawCalls);
        assertEquals(expected(placementInitial), fixture.shader.models.get(0));
        assertEquals(expected(placementLater), fixture.shader.models.get(1));
        assertEquals(expected(breaking), fixture.shader.models.get(2));
        assertEquals(List.of(1.0f, 1.0f, 0.58f), fixture.shader.floats.get("visualAlpha"));
        for (BlockFace face : BlockFace.values()) {
            TextureRegion region = oldFaces.region(face);
            assertEquals(
                    List.of(region.uMin(), region.uMin(), region.uMin()),
                    fixture.shader.floats.get("uMin[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.uMax(), region.uMax(), region.uMax()),
                    fixture.shader.floats.get("uMax[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.vMin(), region.vMin(), region.vMin()),
                    fixture.shader.floats.get("vMin[" + face.ordinal() + "]"));
            assertEquals(
                    List.of(region.vMax(), region.vMax(), region.vMax()),
                    fixture.shader.floats.get("vMax[" + face.ordinal() + "]"));
        }
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void emptyExpiredSnapshotDrawsNothingAndDoesNotTouchState() {
        Fixture fixture = new Fixture();

        fixture.pass.render(context(frame(List.of()), ignored -> {}), new RenderQueue());

        assertEquals(0, fixture.cube.drawCalls);
        assertEquals(0, fixture.state.captureCalls);
        assertEquals(0, fixture.shader.useCalls);
    }

    @Test
    void drawFailureRestoresExactIncomingStateAndDoesNotRecordMetrics() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("proxy draw failed");
        fixture.cube.failure = failure;
        List<Long> metrics = new ArrayList<>();

        RuntimeException escaped = assertThrows(
                RuntimeException.class,
                () -> fixture.pass.render(
                        context(frame(List.of(visual(
                                TransientBlockVisual.Type.BREAK,
                                4,
                                VisualTransform.identity(),
                                distinctFaces()))), metrics::add),
                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(List.of(), metrics);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    private static TransientBlockVisual visual(
            TransientBlockVisual.Type type,
            long eventIdentity,
            VisualTransform transform,
            WorldItemFaceRegions faces) {
        return new TransientBlockVisual(CELL, faces, type, eventIdentity, transform);
    }

    private static InteractionFeedbackFrame frame(List<TransientBlockVisual> visuals) {
        return new InteractionFeedbackFrame(
                new FeedbackVisibility(true, true, true, false),
                Optional.empty(),
                List.of(),
                new ParticleRenderBatch(List.of()),
                Optional.empty(),
                com.overlord.renderer.feedback.CameraImpulseVisual.identity(),
                visuals,
                visuals.stream().map(TransientBlockVisual::coordinate).distinct().toList());
    }

    private static Matrix4f expected(VisualTransform transform) {
        return new Matrix4f()
                .translation(
                        CELL.x() + 0.5f + transform.translationX(),
                        CELL.y() + 0.5f + transform.translationY(),
                        CELL.z() + 0.5f + transform.translationZ())
                .rotateX((float) Math.toRadians(transform.pitchDegrees()))
                .rotateY((float) Math.toRadians(transform.yawDegrees()))
                .rotateZ((float) Math.toRadians(transform.rollDegrees()))
                .scale(transform.scale())
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private static WorldItemFaceRegions distinctFaces() {
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            int offset = face.ordinal() * 16;
            faces.put(face, new TextureRegion(
                    ResourceLocation.parse("test:old_" + face.name().toLowerCase()),
                    offset, 0, 16, 16, 96, 16));
        }
        return new WorldItemFaceRegions(faces);
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
        private final TransientBlockVisualPass pass =
                new TransientBlockVisualPass(state, shader, texture, cube);
    }
}
