package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.interaction.GaiaInteractionContext;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangedEvent;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.inventory.api.BodySlot;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.texture.TextureRegion;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CommittedBreakVisualAdapterTest {
    private static final ResourceLocation AIR = ResourceLocation.parse("gaia:air");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final TextureRegion STONE_REGION =
            new TextureRegion(
                    ResourceLocation.parse("gaia:stone_top"), 0, 0, 16, 16, 16, 16);

    @Test
    void primaryCommittedBreakEmitsExactlyOneTwentyFourParticleRequestFromPreviousBlock() {
        List<ResourceLocation> resolved = new ArrayList<>();
        List<ParticleEmission> emissions = new ArrayList<>();
        List<Throwable> diagnostics = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR,
                block -> {
                    resolved.add(block);
                    return STONE_REGION;
                },
                emissions::add,
                (event, failure) -> diagnostics.add(failure));

        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR));

        assertEquals(List.of(STONE), resolved);
        assertEquals(1, emissions.size());
        ParticleEmission emission = emissions.get(0);
        assertEquals(ParticleCategory.BREAK_COMMITTED, emission.category());
        assertEquals(24, emission.count());
        assertEquals(1.5f, emission.x());
        assertEquals(2.5f, emission.y());
        assertEquals(3.5f, emission.z());
        assertEquals(STONE_REGION, emission.region());
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void placementNoChangeSecondaryAndNonAirReplacementEmitNothing() {
        List<ParticleEmission> emissions = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = adapter(emissions::add, (event, failure) -> {});

        adapter.onBlockChanged(changed(InteractionAction.SECONDARY, AIR, DIRT));
        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, STONE));
        adapter.onBlockChanged(changed(InteractionAction.SECONDARY, STONE, AIR));
        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, DIRT));
        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, AIR, AIR));

        assertEquals(List.of(), emissions);
    }

    @Test
    void resolverRuntimeFailureIsDiagnosedOnceAndContained() {
        RuntimeException failure = new IllegalStateException("resolver");
        List<Throwable> diagnostics = new ArrayList<>();
        CommittedBreakVisualAdapter adapter = new CommittedBreakVisualAdapter(
                AIR,
                block -> {
                    throw failure;
                },
                emission -> {
                    throw new AssertionError("emission must not be reached");
                },
                (event, reported) -> diagnostics.add(reported));

        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR));

        assertEquals(1, diagnostics.size());
        assertSame(failure, diagnostics.get(0));
    }

    @Test
    void emissionRuntimeFailureIsDiagnosedOnceAndContainedWithoutRetry() {
        RuntimeException failure = new IllegalStateException("emission");
        List<Throwable> diagnostics = new ArrayList<>();
        int[] calls = {0};
        CommittedBreakVisualAdapter adapter = adapter(
                emission -> {
                    calls[0]++;
                    throw failure;
                },
                (event, reported) -> diagnostics.add(reported));

        adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR));

        assertEquals(1, calls[0]);
        assertEquals(List.of(failure), diagnostics);
    }

    @Test
    void recoverableVisualFailureRemainsContainedWhenDiagnosticsFails() {
        RuntimeException visualFailure = new IllegalStateException("emission");
        RuntimeException diagnosticFailure = new IllegalArgumentException("diagnostic");
        int[] emissionCalls = {0};
        int[] diagnosticCalls = {0};
        CommittedBreakVisualAdapter adapter = adapter(
                emission -> {
                    emissionCalls[0]++;
                    throw visualFailure;
                },
                (event, reported) -> {
                    diagnosticCalls[0]++;
                    assertSame(visualFailure, reported);
                    throw diagnosticFailure;
                });

        assertDoesNotThrow(
                () -> adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR)));

        assertEquals(1, emissionCalls[0]);
        assertEquals(1, diagnosticCalls[0]);
        assertEquals(1, visualFailure.getSuppressed().length);
        assertSame(diagnosticFailure, visualFailure.getSuppressed()[0]);
    }

    @Test
    void fatalErrorIsDiagnosedOnceThenRethrownWithoutRetry() {
        AssertionError failure = new AssertionError("fatal");
        List<Throwable> diagnostics = new ArrayList<>();
        int[] calls = {0};
        CommittedBreakVisualAdapter adapter = adapter(
                emission -> {
                    calls[0]++;
                    throw failure;
                },
                (event, reported) -> diagnostics.add(reported));

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR)));

        assertSame(failure, thrown);
        assertEquals(1, calls[0]);
        assertEquals(List.of(failure), diagnostics);
    }

    @Test
    void fatalVisualErrorKeepsIdentityAndSuppressesDiagnosticFailureWithoutRetry() {
        AssertionError visualFailure = new AssertionError("fatal");
        RuntimeException diagnosticFailure = new IllegalStateException("diagnostic");
        int[] emissionCalls = {0};
        int[] diagnosticCalls = {0};
        CommittedBreakVisualAdapter adapter = adapter(
                emission -> {
                    emissionCalls[0]++;
                    throw visualFailure;
                },
                (event, reported) -> {
                    diagnosticCalls[0]++;
                    assertSame(visualFailure, reported);
                    throw diagnosticFailure;
                });

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> adapter.onBlockChanged(changed(InteractionAction.PRIMARY, STONE, AIR)));

        assertSame(visualFailure, thrown);
        assertEquals(1, emissionCalls[0]);
        assertEquals(1, diagnosticCalls[0]);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(diagnosticFailure, thrown.getSuppressed()[0]);
    }

    private static CommittedBreakVisualAdapter adapter(
            Consumer<ParticleEmission> emissions,
            VisualFeedbackDiagnostics diagnostics) {
        return new CommittedBreakVisualAdapter(
                AIR, ignored -> STONE_REGION, emissions, diagnostics);
    }

    private static BlockChangedEvent changed(
            InteractionAction action,
            ResourceLocation previous,
            ResourceLocation current) {
        GaiaInteractionContext context = new GaiaInteractionContext(
                new EntityRef(7), BodySlot.LEFT_HAND, action, 41, 4100);
        BlockChangeRequest request = new BlockChangeRequest(
                context, 1, 2, 3, previous, current);
        return new BlockChangedEvent(request, previous, current);
    }
}
