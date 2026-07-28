# Phase 9B Interaction Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add a framebuffer-centered crosshair, ten-stage damage overlay, fixed-step particles, and stable-ID logical world-item visuals without changing Phase 9A gameplay or Phase 3 Chunk semantics.

**Architecture:** Game composition adapts BlockInteractionViewModel, post-write BlockChangedEvent, and immutable WorldItemSnapshot values into one engine-owned immutable InteractionFeedbackFrame. Renderer owns the ordered passes and GPU resources; it never receives a gameplay service. CPU particles update at fixed 1/60 and all OpenGL work remains on the context thread.

**Tech Stack:** Java 17, Gradle Wrapper 8.5, JUnit Jupiter 6.1.1, LWJGL 3.3.3, OpenGL 4.1 core, GLSL 410, JOML 1.10.5, classpath assets.

## Global Constraints

- Work only on feat/block-interaction-feedback at origin/main 51cb3f23b7ebf9a8999451ac2cf3defb9eec2ceb.
- Do not stage, commit, push, create a PR, or merge. Commit steps normally required by the planning skill are replaced by local review checkpoints because the user prohibited commits.
- Preserve Java 17 source/target and use the Gradle Wrapper.
- Use only OpenGL 4.1 and GLSL 410; no compute shader, SSBO, or worker-thread GL.
- Engine must not import or depend on game.
- Do not modify Phase 9A raycast, fixed timing, GameMode, reservation, WorldMutationService, conservation, committed order, or Chunk dirty logic.
- Do not create another ItemStack, item registry, world-item model/store, or stable-ID namespace.
- Damage presentation must never mutate a Chunk, revision, dirty set, or mesh.
- Every production change follows a focused RED test and recorded failure.
- Keep the Phase 5B shader gamma path and nearest/no-mipmap block-atlas policy.

---

## File map

Engine presentation values:

- Create engine/src/main/java/com/overlord/renderer/feedback/FeedbackVisibility.java.
- Create BlockDamageVisual.java, WorldItemVisual.java, ParticleVisual.java, ParticleRenderBatch.java, and InteractionFeedbackFrame.java in the same package.
- Create ScreenQuad.java, CrosshairGeometry.java, CrackStageMapper.java, DamageAtlasLayout.java, and DamageAtlasResourceLoader.java in the same package.
- Create ParticleCategory.java under renderer/particle with the two approved
  visual categories before ParticleVisual is compiled.

Engine rendering:

- Extend RenderStateSnapshot, RenderStateSpec, RenderStateBackend, OpenGlRenderStateBackend, RenderContext, RenderFrameInput, RenderAssets, and Renderer.
- Create DepthFunction.java and Viewport.java under renderer/state.
- Create CrosshairRenderPass.java, BlockDamageOverlayPass.java, WorldItemVisualPass.java, and ParticleRenderPass.java under renderer/pass.
- Create ScreenQuadBatch.java, UnitCubeMesh.java, StreamingTexturedCubeBatch.java and their OpenGL implementations under renderer/feedback.
- Create ParticleCategory.java, ParticleEmission.java, and ParticleSystem.java under renderer/particle.
- Create InteractionFeedbackAssets.java under renderer/feedback.
- Add GLSL 410 resources under engine/src/main/resources/assets/overlord/shaders/feedback.

Game adaptation:

- Create GaiaVisualRegionResolver.java, WorldItemVisualTracker.java, CommittedBreakVisualAdapter.java, VisualFeedbackDiagnostics.java, and InteractionFeedbackCoordinator.java under game/src/main/java/com/gaia/interaction/feedback.
- Create InteractionBlockState.java in that package as the future Phase 10
  blocking boundary; Phase 9B injects the constant-unblocked implementation.
- Modify GameBootstrap.java, GameContext.java, GameLoop.java, GaiaAssetCatalog.java, and GaiaResourceLoader.java only for composition, immutable presentation, lifecycle, and resource loading.
- Create tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java.
- Create game/src/main/resources/assets/gaia/textures/effects/block_damage.png.

Tests and documentation:

- Add focused engine tests under renderer/feedback, renderer/particle, and renderer/pass.
- Add focused game tests under interaction/feedback and integration tests for GameLoop/GameBootstrap.
- Extend packaged-resource tasks in engine/build.gradle and game/build.gradle.
- Finalize docs/architecture/block-interaction-feedback.md and create docs/agent-handoffs/phase-09b-handoff.md.

---

### Task 1: Complete render-state capture and restoration

**Files:**

- Create: engine/src/main/java/com/overlord/renderer/state/DepthFunction.java
- Create: engine/src/main/java/com/overlord/renderer/state/Viewport.java
- Modify: engine/src/main/java/com/overlord/renderer/state/RenderStateSnapshot.java
- Modify: engine/src/main/java/com/overlord/renderer/state/RenderStateSpec.java
- Modify: engine/src/main/java/com/overlord/renderer/state/RenderStateBackend.java
- Modify: engine/src/main/java/com/overlord/renderer/state/OpenGlRenderStateBackend.java
- Test: engine/src/test/java/com/overlord/renderer/state/RenderStateScopeTest.java
- Create test: engine/src/test/java/com/overlord/renderer/state/OpenGlRenderStateBackendStructureTest.java

**Interfaces:**

- Produces DepthFunction with LESS and LEQUAL.
- Produces Viewport(int x, int y, int width, int height).
- Produces RenderStateBackend.setViewport(Viewport).
- Preserves the existing four-argument RenderStateSpec constructor with LESS and polygon offset disabled.

- [x] **Step 1: Write failing complete-state tests**

    private static final RenderStateSnapshot INCOMING =
            snapshotWithDepthBuffersPolygonOffsetAndViewport();

    @Test
    void exceptionalExitRestoresEveryCapturedValue() {
        RecordingRenderStateBackend backend =
                new RecordingRenderStateBackend(INCOMING);
        assertThrows(IllegalStateException.class, () -> {
            try (RenderStateScope ignored =
                    RenderStateScope.open(backend, OVERLAY_STATE)) {
                backend.setViewport(new Viewport(0, 0, 1024, 768));
                throw new IllegalStateException("draw failed");
            }
        });
        assertEquals(INCOMING, backend.current());
        assertEquals(1, backend.restoreCount());
    }

The snapshot fixture must use distinct values for depth function, VAO, array buffer, element buffer, polygon offset, program, active texture, unit-zero texture, and all four viewport fields.

- [x] **Step 2: Run RED**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.state.*" --console=plain --no-daemon

Expected RED: the new values and backend operation do not exist.

- [x] **Step 3: Implement the minimal state extension**

    public enum DepthFunction { LESS, LEQUAL }

    public record Viewport(int x, int y, int width, int height) {
        public Viewport {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("viewport dimensions must be non-negative");
            }
        }
    }

Capture GL_DEPTH_FUNC, GL_VERTEX_ARRAY_BINDING, GL_ARRAY_BUFFER_BINDING, GL_ELEMENT_ARRAY_BUFFER_BINDING, polygon-offset enable/factor/units, and GL_VIEWPORT. Restore VAO before its captured EBO, then array buffer, program, unit-zero texture, active texture, depth, blend, cull, polygon offset, and viewport. Map only LESS and LEQUAL.

- [x] **Step 4: Run GREEN and pass regressions**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.state.*" --tests "com.overlord.renderer.pass.*" --console=plain --no-daemon

- [x] **Step 5: Checkpoint**

Run git diff --check and inspect only state production/tests. Record the RED assertion and GREEN command in the future handoff. Do not stage.

---

### Task 2: Immutable feedback frame and crosshair

**Files:**

- Create FeedbackVisibility.java, BlockDamageVisual.java,
  WorldItemVisual.java, ParticleVisual.java, ParticleRenderBatch.java,
  InteractionFeedbackFrame.java, ScreenQuad.java, and CrosshairGeometry.java
  under engine/src/main/java/com/overlord/renderer/feedback.
- Create: engine/src/main/java/com/overlord/renderer/particle/ParticleCategory.java
- Create: engine/src/main/java/com/overlord/renderer/feedback/ScreenQuadBatch.java
- Create: engine/src/main/java/com/overlord/renderer/feedback/OpenGlScreenQuadBatch.java
- Create: engine/src/main/java/com/overlord/renderer/pass/CrosshairRenderPass.java
- Modify: engine/src/main/java/com/overlord/renderer/RenderFrameInput.java
- Modify: engine/src/main/java/com/overlord/renderer/pass/RenderContext.java
- Create tests: InteractionFeedbackFrameTest.java, CrosshairGeometryTest.java, and CrosshairRenderPassTest.java
- Modify test: engine/src/test/java/com/overlord/renderer/RenderFrameInputTest.java
- Modify: engine/build.gradle
- Modify: game/build.gradle

**Interfaces:**

    public record FeedbackVisibility(
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean interactionBlocked) {
        public boolean showGameplayFeedback();
    }

public record InteractionFeedbackFrame(
            FeedbackVisibility visibility,
            Optional<BlockDamageVisual> blockDamage,
            List<WorldItemVisual> worldItems,
            ParticleRenderBatch particles) {
        public static InteractionFeedbackFrame hidden();
    }

    public record BlockDamageVisual(
            int blockX, int blockY, int blockZ, int crackStage) {}

    public record WorldItemVisual(
            WorldItemId id, long sourceRevision,
            double x, double y, double z,
            TextureRegion region) {}

    public record ParticleVisual(
            float x, float y, float z,
            float size, TextureRegion region,
            ParticleCategory category, long spawnSequence) {}

    public record ParticleRenderBatch(List<ParticleVisual> particles) {}

- [x] **Step 1: Write RED geometry and lifecycle tests**

For 1024 by 768 assert center 512,384 and four quads with horizontal ranges 504..510 and 514..520 and vertical equivalents. For 1001 by 701 assert center 500.5,350.5. For logical 1024 by 768 plus framebuffer 2048 by 1536 assert geometry uses 2048 by 1536. Parameterize running, cursor capture, focus, blocking UI, loading, recapture, and zero-sized framebuffer. Mutate constructor input lists after construction and assert immutable snapshots.

- [x] **Step 2: Run RED**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.*" --tests "com.overlord.renderer.pass.CrosshairRenderPassTest" --console=plain --no-daemon

- [x] **Step 3: Implement exact four-quad geometry**

    float cx = framebufferWidth / 2.0f;
    float cy = framebufferHeight / 2.0f;
    return List.of(
            new ScreenQuad(cx - 8, cy - 1, cx - 2, cy + 1),
            new ScreenQuad(cx + 2, cy - 1, cx + 8, cy + 1),
            new ScreenQuad(cx - 1, cy - 8, cx + 1, cy - 2),
            new ScreenQuad(cx - 1, cy + 2, cx + 1, cy + 8));

The pass draws only when showGameplayFeedback is true and the framebuffer is drawable. It disables depth test/write, blend, and cull, sets the full framebuffer viewport, uploads four quads, draws once, and restores state.

- [x] **Step 4: Prove packaged crosshair resources RED, then add GLSL 410 resources**

Before creating the shaders, add both crosshair paths to
verifyPackagedShaderResources and verifyInstalledShaderResources, run those
two tasks, and record their missing-entry failures. Then create the resources.

The vertex shader accepts a two-float pixel position and framebufferSize uniform, converts directly to NDC, and writes z zero. The fragment shader writes vec4(1.0). Neither shader accepts a texture, target, or raycast uniform.

- [x] **Step 5: Run GREEN**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.*" --tests "com.overlord.renderer.pass.CrosshairRenderPassTest" --tests "com.overlord.renderer.RenderFrameInputTest" --tests "com.overlord.renderer.state.*" --console=plain --no-daemon

- [x] **Step 6: Checkpoint**

Assert exact coordinates, one draw, zero draw on every hidden state, and complete normal/exception state equality. Run git diff --check. Do not stage.

---

### Task 3: Deterministic damage atlas and overlay

**Files:**

- Create: tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java
- Create: game/src/main/resources/assets/gaia/textures/effects/block_damage.png
- Create: DamageAtlasLayout.java, DamageAtlasResourceLoader.java, UnitCubeMesh.java, and OpenGlUnitCubeMesh.java under engine renderer/feedback.
- Create: engine/src/main/java/com/overlord/renderer/pass/BlockDamageOverlayPass.java
- Create shaders: block_damage.vert and block_damage.frag under engine feedback shader resources.
- Modify: engine/src/main/java/com/overlord/renderer/RenderAssets.java
- Modify: game/src/main/java/com/gaia/assets/GaiaResourceLoader.java
- Modify: engine/build.gradle
- Modify: game/build.gradle
- Create tests: CrackStageMapperTest.java, DamageAtlasResourceLoaderTest.java, BlockDamageOverlayPassTest.java
- Modify: game/src/test/java/com/gaia/assets/GaiaProductionAssetsTest.java

**Interfaces:**

    public final class CrackStageMapper {
        public static int map(double progress, int stageCount);
    }

    public record DamageAtlasLayout(TextureImage image, int stageCount) {
        public TextureRegion region(int stage);
    }

- [x] **Step 1: Write RED mapping/resource tests and packaged assertions**

Assert mapping cases 0/0.0999/0.1/0.9/1.0 for ten stages and final boundaries for eight and nine stages. Reject non-finite progress and stage counts outside 8..10. Assert the production image is 160 by 16, exposes ten 16 by 16 regions, missing/invalid bytes produce the explicit fallback plus one diagnostic, and loading does not modify block-atlas metadata.

    @ParameterizedTest
    @CsvSource({"0.0,10,0", "0.0999,10,0", "0.1,10,1",
                "0.9,10,9", "1.0,10,9", "0.875,8,7", "0.8889,9,8"})
    void mapsStageBoundaries(double progress, int count, int expected) {
        assertEquals(expected, CrackStageMapper.map(progress, count));
    }

Before creating the PNG or damage shaders, add their exact paths to the game
JAR, engine shader JAR, and installDist verification lists. Run all affected
verification tasks and record the expected missing-entry failures.

- [x] **Step 2: Run RED**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.CrackStageMapperTest" --tests "com.overlord.renderer.feedback.DamageAtlasResourceLoaderTest" --console=plain --no-daemon

- [x] **Step 3: Implement and run the deterministic generator**

The Java program uses BufferedImage, ImageIO, fixed seed 0x474149413942L, and fixed ordered segment groups. Stage n draws the first n+1 groups in tile n; untouched pixels remain alpha zero. It accepts exactly one output path.

    java tools/src/main/java/com/gaia/tools/BlockDamageAtlasGenerator.java game/src/main/resources/assets/gaia/textures/effects/block_damage.png
    Get-FileHash game/src/main/resources/assets/gaia/textures/effects/block_damage.png -Algorithm SHA256

- [x] **Step 4: Implement the loader, fallback, layout, and shaders**

Require 160 by 16 content. The in-memory fallback uses the same dimensions and visible black/magenta diagonal cracks. Sampling is nearest, clamp-to-edge, level zero, with half-texel regions. The fragment shader discards alpha below 0.1.

    TextureImage loaded = imageLoader.load(assets, location, diagnostics);
    if (loaded.width() != 160 || loaded.height() != 16) {
        diagnostics.accept(DamageAtlasDiagnostic.invalidDimensions(location));
        loaded = DamageAtlasResourceLoader.fallbackImage();
    }
    return new DamageAtlasLayout(loaded, 10);

- [x] **Step 5: Write RED overlay behavior tests**

Assert no draw for absent target, zero progress, Creative, cancellation, unload, focus/cursor/blocking state, or target replacement. Assert one shared-cube draw and exact block transform for a valid Survival view. Recording mutation, Chunk revision, dirty, and mesh-rebuild counters must remain zero. Shader and draw failures must both restore full state.

- [x] **Step 6: Implement minimal overlay and run GREEN**

Use authoritative crackStage when present. Configure depth LEQUAL, depth write false, blend/cull false, and only GL_POLYGON_OFFSET_FILL factor -1.0 and units -1.0. Do not expand the model.

    private static final RenderStateSpec OVERLAY_STATE =
            new RenderStateSpec(
                    true, DepthFunction.LEQUAL, false,
                    BlendMode.DISABLED, false,
                    true, -1.0f, -1.0f);

    if (context.feedback().blockDamage().isEmpty()) return;
    try (RenderStateScope ignored = RenderStateScope.open(states, OVERLAY_STATE)) {
        drawCube.draw(context.feedback().blockDamage().orElseThrow());
    }

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.feedback.*" --tests "com.overlord.renderer.pass.BlockDamageOverlayPassTest" --console=plain --no-daemon

- [x] **Step 7: Checkpoint**

Record PNG SHA-256. Scan the diff for Chunk repository, dirty tracker, mesh manager, shared atlas, and WorldMutationService writes; no such production change is allowed. Run git diff --check.

---

### Task 4: Fixed-step particles and renderer

**Files:**

- Create ParticleEmission.java and ParticleSystem.java under engine renderer/particle; consume the ParticleCategory created by Task 2.
- Create StreamingTexturedCubeBatch.java and OpenGlStreamingTexturedCubeBatch.java under engine renderer/feedback.
- Create: engine/src/main/java/com/overlord/renderer/pass/ParticleRenderPass.java
- Create shaders: particle.vert and particle.frag.
- Create tests: ParticleSystemTest.java, ParticleRenderPassTest.java, OpenGlFeedbackResourceStructureTest.java
- Modify: engine/build.gradle
- Modify: game/build.gradle

**Interfaces:**

    public record ParticleEmission(
            ParticleCategory category,
            float x, float y, float z,
            TextureRegion region,
            int count,
            long deterministicSeed) {}

    public final class ParticleSystem {
        public static final int MAX_PARTICLES = 512;
        public void emit(ParticleEmission emission);
        public void fixedUpdate(float fixedDeltaSeconds);
        public ParticleRenderBatch snapshot();
        public void clear();
    }

- [x] **Step 1: Write RED simulation tests and packaged shader assertions**

Assert one committed emission creates exactly 24 particles before cap handling; 513th insertion removes the oldest sequence and retains 512; identical seeds yield identical position/velocity/size/lifetime; lifetimes stay within 0.35..0.75; fixed updates remove all expired particles; returned batches are immutable; invalid update values are rejected.

    @Test
    void overflowReplacesOldestSequence() {
        ParticleSystem system = filledSystem(512);
        long oldest = system.snapshot().particles().get(0).spawnSequence();
        system.emit(continuousEmission(1, 99L));
        assertEquals(512, system.snapshot().particles().size());
        assertFalse(sequences(system).contains(oldest));
    }

Add the particle shader paths to both shader packaging tasks before creating
the files, run the tasks, and record the missing-entry RED failures.

- [x] **Step 2: Run RED**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.particle.ParticleSystemTest" --console=plain --no-daemon

- [x] **Step 3: Implement deterministic CPU state**

Use an insertion-ordered deque and monotonic long sequence. At capacity remove first, then add. Derive variation from a private integer mixing function over seed, category, and local index; never instantiate Random. Advance position by velocity times exactly 1/60 and remove age greater than or equal to lifetime.

    for (int index = 0; index < emission.count(); index++) {
        if (particles.size() == MAX_PARTICLES) particles.removeFirst();
        particles.addLast(createParticle(emission, index, nextSequence++));
    }

    particles.replaceAll(particle -> particle.advance(FIXED_STEP_SECONDS));
    particles.removeIf(particle -> particle.age() >= particle.lifetime());

- [x] **Step 4: Write RED GPU/pass tests**

Use recording backends to assert one upload/draw for non-empty batch, zero for empty, exact triangle count, main-thread guard use, state restoration after shader/upload/draw failures, initialization rollback, and cleanup exactly once.

- [x] **Step 5: Implement streaming cube batch and pass**

Expand immutable particles into one CPU float array, upload with GL_STREAM_DRAW, and draw once. Use block atlas, depth LEQUAL, depth write false, alpha blend enabled, and cull disabled. No sorting, collision, physics, executor, compute, or SSBO.

    if (context.feedback().particles().particles().isEmpty()) return;
    try (RenderStateScope ignored = RenderStateScope.open(states, PARTICLE_STATE)) {
        shader.use();
        atlas.bind(0);
        batch.upload(context.feedback().particles());
        batch.draw();
    }

- [x] **Step 6: Run GREEN**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.particle.*" --tests "com.overlord.renderer.pass.ParticleRenderPassTest" --tests "com.overlord.renderer.state.*" --console=plain --no-daemon

- [x] **Step 7: Checkpoint**

Scan particle production for Random, PhysicsBody, collision, executors, GL42+, compute, and SSBO. Expected result is empty. Run git diff --check.

---

### Task 5: Stable-ID world-item visuals

**Files:**

- Create: game/src/main/java/com/gaia/interaction/feedback/GaiaVisualRegionResolver.java
- Create: game/src/main/java/com/gaia/interaction/feedback/WorldItemVisualTracker.java
- Create: engine/src/main/java/com/overlord/renderer/pass/WorldItemVisualPass.java
- Create shaders: world_item.vert and world_item.frag.
- Create tests: GaiaVisualRegionResolverTest.java, WorldItemVisualTrackerTest.java, WorldItemVisualPassTest.java
- Modify: engine/build.gradle
- Modify: game/build.gradle

**Interfaces:**

    public final class WorldItemVisualTracker {
        public List<WorldItemVisual> reconcile(
                List<WorldItemSnapshot> snapshots);
        public void clear();
    }

- [x] **Step 1: Write RED tracker/resolver tests and packaged shader assertions**

With two stable IDs, assert add creates two instances, revision update preserves one identity, deletion removes it, reversed input order creates no duplicate, source positions are exact, and caller list mutation cannot change output. Unknown item uses gaia:missing. Reflection/architecture assertions forbid WorldItemService, mutable ItemStack storage, reservations, and a second ID field.

    @Test
    void reorderAndRevisionUpdatePreserveStableIdentity() {
        WorldItemVisualTracker tracker = tracker();
        tracker.reconcile(List.of(item(ID_1, 0), item(ID_2, 0)));
        List<WorldItemVisual> updated =
                tracker.reconcile(List.of(item(ID_2, 1), item(ID_1, 0)));
        assertEquals(List.of(ID_1, ID_2), ids(updated));
        assertEquals(2, updated.size());
    }

Add world-item shader paths to both packaging tasks before creating the shader
files, run the tasks, and record the missing-entry RED failures.

- [x] **Step 2: Run RED**

    .\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.WorldItemVisualTrackerTest" --tests "com.gaia.interaction.feedback.GaiaVisualRegionResolverTest" --console=plain --no-daemon

- [x] **Step 3: Implement canonical resolution and presentation diff**

Resolve snapshot.stack().itemId() through BlockRegistry.blockForItem(), choose the block TOP texture, and resolve it through GaiaAssetCatalog.blockAtlas(); otherwise use gaia:missing. Cache only ID, source revision, immutable position, and TextureRegion. Return a stable-ID-sorted immutable list.

    TextureRegion region = blocks.blockForItem(snapshot.stack().itemId())
            .map(block -> block.textures().get(BlockFace.TOP))
            .map(atlas.regions()::get)
            .orElse(missingRegion);
    visuals.put(snapshot.id(), WorldItemVisual.from(snapshot, region));
    return visuals.values().stream()
            .sorted(comparingLong(item -> item.id().value()))
            .toList();

- [x] **Step 4: Write RED pass tests and implement the pass**

Assert one instance per stable ID, exact edge length 0.25, exact logical position, fallback UV, no snapshot mutation, no service/mutation dependency, and full state restoration. Draw with shared unit cube and existing block atlas. Do not write logical positions or create PhysicsBody.

    try (RenderStateScope ignored = RenderStateScope.open(states, WORLD_ITEM_STATE)) {
        shader.use();
        atlas.bind(0);
        for (WorldItemVisual item : context.feedback().worldItems()) {
            shader.setMatrix4("model", modelAt(item.x(), item.y(), item.z(), 0.25f));
            cube.draw(item.region());
        }
    }

- [x] **Step 5: Run GREEN**

    .\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*WorldItem*" --console=plain --no-daemon
    .\gradlew.bat :engine:test --tests "com.overlord.renderer.pass.WorldItemVisualPassTest" --tests "com.overlord.worlditem.*" --console=plain --no-daemon

- [x] **Step 6: Checkpoint**

Run repository declaration scans proving one production ItemStack and one logical world-item service/store. Run git diff --check.

---

### Task 6: Committed event adapter and coordinator

**Files:**

- Create VisualFeedbackDiagnostics.java, CommittedBreakVisualAdapter.java, and InteractionFeedbackCoordinator.java under game interaction/feedback.
- Create: game/src/main/java/com/gaia/interaction/feedback/InteractionBlockState.java
- Create tests: CommittedBreakVisualAdapterTest.java, InteractionFeedbackCoordinatorTest.java, FeedbackTransactionIsolationTest.java

**Interfaces:**

    public final class InteractionFeedbackCoordinator {
        public void onBlockChanged(BlockChangedEvent event);
        public void fixedUpdate(
                BlockInteractionViewModel view,
                boolean interactionEnabled,
                long tick);
        public InteractionFeedbackFrame snapshot(
                BlockInteractionViewModel view,
                List<WorldItemSnapshot> worldItems,
                FeedbackVisibility visibility);
        public void clearTransient();
        public void clearAll();
    }

- [x] **Step 1: Write RED committed-only tests**

Assert PRIMARY non-air-to-air produces one 24-particle burst using previousBlock material. Assert placement, no-change, Before cancellation, mutation rejection, reservation rejection, and ordinary session cancellation produce zero bursts. Do not demand cross-call dedupe because BlockChangedEvent has no stable event ID.

    @Test
    void primaryCommittedBreakEmitsOneBurstFromPreviousBlock() {
        adapter.onBlockChanged(changed(PRIMARY, STONE, AIR));
        assertEquals(1, emissions.size());
        assertEquals(24, emissions.get(0).count());
        assertEquals(STONE_REGION, emissions.get(0).region());
    }

- [x] **Step 2: Write RED failure isolation**

Inject a resolver or particle sink that throws a RuntimeException. Invoke
through SynchronousBlockChangeEventPublisher and assert authoritative mutation
remains applied, one diagnostic receives the throwable, and the recoverable
failure does not escape as BlockChangeDispatchException. Add a fatal Error case
that is diagnosed, escapes as mutationApplied=true, and still exercises Phase
9A's guaranteed reservation commits without rollback.

- [x] **Step 3: Run RED**

    .\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.CommittedBreakVisualAdapterTest" --tests "com.gaia.interaction.feedback.FeedbackTransactionIsolationTest" --console=plain --no-daemon

- [x] **Step 4: Implement event filtering without touching transactions**

Use only request context/action/coordinates, previousBlock, and currentBlock.
Accept PRIMARY exact non-air-to-air transitions. Resolve old material from
previousBlock. Diagnose and contain RuntimeException. Diagnose then rethrow
Error so the existing mutationApplied=true path remains observable. Never
rollback or automatically retry.

    try {
        if (isCommittedBreak(event)) {
            particles.emit(committedEmission(event, regions.resolve(event.previousBlock())));
        }
    } catch (RuntimeException failure) {
        diagnostics.report(event, failure);
    } catch (Error failure) {
        diagnostics.report(event, failure);
        throw failure;
    }

- [x] **Step 5: Write RED coordinator lifecycle tests**

Assert one temporary particle after ten valid Survival fixed updates; none in Creative or disabled interaction; target change resets cadence; F1, focus, loading, mode switch, and blocking clear overlay and continuous eligibility even with zero fixed steps; committed particles continue aging; world snapshots reconcile; output is immutable.

    for (int step = 0; step < 10; step++) {
        coordinator.fixedUpdate(breakingView(TARGET), true, step);
    }
    assertEquals(1, continuousParticles(coordinator.snapshot()).size());
    coordinator.clearTransient();
    assertTrue(coordinator.snapshot(hiddenVisibility()).blockDamage().isEmpty());

- [x] **Step 6: Implement coordinator and run GREEN**

    .\gradlew.bat :game:test --tests "com.gaia.interaction.feedback.*" --tests "com.gaia.interaction.*" --console=plain --no-daemon

- [x] **Step 7: Checkpoint**

Diff BlockBreakTransaction, BlockPlacementTransaction, DefaultWorldMutationService, and Chunk lifecycle files against origin/main; they must remain unchanged. Scan feedback production for changeBlock, inventory mutations, raycast, dirty, and mesh rebuild calls. Run git diff --check.

---

### Task 7: Renderer and game-loop integration

**Files:**

- Create: engine/src/main/java/com/overlord/renderer/feedback/InteractionFeedbackAssets.java
- Modify: RenderAssets.java, Renderer.java, RenderContext.java, RenderFrameInput.java, and RenderPipeline.java.
- Modify: GameBootstrap.java, GameContext.java, GameLoop.java, GaiaAssetCatalog.java, and GaiaResourceLoader.java.
- Modify: engine/src/main/java/com/overlord/core/input/InputManager.java
- Modify: engine/src/test/java/com/overlord/core/input/InputManagerTest.java
- Modify tests: RenderAssetsTest.java, RendererStructureTest.java, RenderPipelineTest.java, RenderPipelineArchitectureTest.java, GameBootstrapStructureTest.java, GameLoopStructureTest.java, GaiaResourceLoaderTest.java, GaiaProductionAssetsTest.java.
- Create tests: InteractionFeedbackRendererLifecycleTest.java and InteractionFeedbackGameLoopTest.java.

**Interfaces:**

- Exact pass IDs: sky, world, block-damage, world-items, particles, debug, crosshair.
- Renderer consumes only InteractionFeedbackFrame and existing rendering assets.
- GameBootstrap owns one coordinator and one changed-event adapter that
  contains recoverable runtime failures while preserving fatal-error delivery.
- InputManager adds only a main-thread read-only isWindowFocused() accessor over
  its existing GLFW callback state; it does not add another focus state.
- GameContext receives InteractionBlockState, bound to false in Phase 9B and
  replaceable by Phase 10.
- Preserve the existing three-argument RenderFrameInput constructor by
  delegating to InteractionFeedbackFrame.hidden(). Preserve existing
  RenderContext constructors the same way so engine demos and Phase 5 tests do
  not need presentation knowledge.
- Preserve existing RenderAssets constructors and RenderAssets.missing() by
  supplying explicit fallback feedback assets; game production supplies the
  loaded damage image and canonical shader paths.

- [x] **Step 1: Write RED pass-order and resource-lifecycle tests**

Assert exact seven-pass list. Inject failures at every new shader, texture, mesh, and streaming buffer creation position; assert earlier resources clean once in reverse order. Normal repeated cleanup must not double-release. Every GPU call must hit MainThreadGuard.

- [x] **Step 2: Write RED game ordering and zero-step lifecycle tests**

Assert input lifecycle, interaction fixed update, committed visual callback,
particle fixed update, immutable feedback snapshot, then render. Verify other
fixed systems still run when feedback is blocked. First add focused
InputManager tests proving the read-only accessor tracks the existing focus
callback and enforces the main-thread guard. On F1/focus loss with fixedSteps
zero, the rendered frame immediately hides crosshair/overlay and stops
continuous emission. Regain/recapture cannot restore old progress. Cover
loading, mode switch, injected blocking UI, resize, and shutdown.

- [x] **Step 3: Run RED**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.InteractionFeedbackRendererLifecycleTest" --tests "com.overlord.renderer.pass.RenderPipelineTest" --console=plain --no-daemon
    .\gradlew.bat :game:test --tests "com.gaia.InteractionFeedbackGameLoopTest" --tests "com.gaia.GameLoopStructureTest" --tests "com.gaia.GameBootstrapStructureTest" --console=plain --no-daemon

- [x] **Step 4: Implement Renderer integration**

Group damage image and feedback shader ResourceLocations in InteractionFeedbackAssets. Create all new resources locally, assign fields only after full success, and mirror reverse cleanup. Pass latest RenderSurfaceMetrics and exact feedback frame into RenderContext. A non-drawable framebuffer skips every pass.

    InteractionFeedbackAssets feedbackAssets = renderAssets.feedback();
    ShaderProgram damage = createProgram(feedbackAssets.damageShaders());
    Texture damageTexture = createTexture(feedbackAssets.damageAtlas());
    RenderPipeline pipeline = new RenderPipeline(List.of(
            skyPass, worldPass, damagePass, worldItemPass,
            particlePass, debugPass, crosshairPass));

- [x] **Step 5: Implement game composition**

Build the resolver from existing catalog, one particle system, tracker,
diagnostics sink, coordinator, and constant-unblocked InteractionBlockState.
Replace noSubscribers only with a publisher whose Before and dirty behavior
remains unchanged and whose changed consumer follows the approved failure
boundary. Each fixed step updates feedback after interaction. Each render frame
uses List.copyOf(worldItems.snapshots()), InputManager.isWindowFocused(), the
injected block state, and an explicit FeedbackVisibility. Renderer receives no
service.

    FeedbackVisibility visibility = new FeedbackVisibility(
            state == State.RUNNING,
            cursorCaptured,
            context.inputManager().isWindowFocused(),
            context.interactionBlockState().blocked());
    InteractionFeedbackFrame feedback = context.interactionFeedback().snapshot(
            context.blockInteraction().viewModel(),
            List.copyOf(context.worldItems().snapshots()),
            visibility);
    renderer.renderFrame(new RenderFrameInput(chunks, frameDelta, queueDepth, feedback));

- [x] **Step 6: Run GREEN and related suites**

    .\gradlew.bat :engine:test --tests "com.overlord.renderer.*" --console=plain --no-daemon
    .\gradlew.bat :game:test --tests "com.gaia.InteractionFeedbackGameLoopTest" --tests "com.gaia.GameLoopStructureTest" --tests "com.gaia.GameBootstrapStructureTest" --tests "com.gaia.interaction.*" --tests "com.gaia.inventory.*" --console=plain --no-daemon

- [x] **Step 7: Checkpoint**

Run git diff --check. Scan engine production for com.gaia and feedback production for service calls. Confirm Phase 9A transaction files and all Chunk files remain unchanged.

---

### Task 8: Packaging, documentation, verification, and reviews

**Files:**

- Modify: engine/build.gradle
- Modify: game/build.gradle
- Finalize: docs/architecture/block-interaction-feedback.md
- Create: docs/agent-handoffs/phase-09b-handoff.md
- Modify Phase 9B production/tests only for review-proven defects.

- [x] **Step 1: Audit cumulative packaged-resource assertions**

Confirm the lists accumulated during Tasks 2 through 5 contain the damage PNG
and every feedback vertex/fragment shader exactly once. Confirm each entry has
recorded missing-resource RED evidence from the task that introduced it.

- [x] **Step 2: Run cumulative packaged GREEN**

    .\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
    .\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
    .\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon

- [x] **Step 3: Complete architecture, provenance, and handoff**

Record generator command, fixed seed, atlas dimensions/SHA-256, project-owned status, pass order, pixel rules, state fields, event/shutdown semantics, RED/GREEN evidence, full changed-file inventory, risks, next-phase protected interfaces, manual checklist, macOS status, suggested commit, and suggested PR.

- [x] **Step 4: Run complete automated verification**

    .\gradlew.bat :engine:test --console=plain --no-daemon
    .\gradlew.bat :game:test --console=plain --no-daemon
    .\gradlew.bat clean test build --console=plain --no-daemon
    .\gradlew.bat :game:verifyPackagedResources --rerun-tasks --console=plain --no-daemon
    .\gradlew.bat :engine:verifyPackagedShaderResources --rerun-tasks --console=plain --no-daemon
    .\gradlew.bat :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon

Read JUnit XML and record exact Engine, Game, and total counts with failures/errors.

- [x] **Step 5: Run hygiene and architecture scans**

    git diff --check
    git status --short --untracked-files=all
    git diff --stat
    git diff --name-status
    git ls-files | rg "(^|/)(build|bin)/|\.class$|hs_err_pid|replay_pid"
    rg -n --glob "*.java" "import com\.gaia\." engine/src/main
    rg -n --glob "*.java" --glob "*.vert" --glob "*.frag" "GL4[2-9]|glDispatchCompute|GL_SHADER_STORAGE_BUFFER|#version (42[0-9]|4[3-9][0-9])" engine/src/main game/src/main

Also scan for absolute JDK paths, Renderer service calls, Phase 9B mutation calls, overlay mesh rebuilds, duplicate ItemStack declarations, and a second world-item store. Forbidden production scans must be empty.

- [ ] **Step 6: Run Windows development and installDist acceptance**

Status: partial. Launch/exit, selected crosshair lifecycle, resize/maximize and
one Creative break path are recorded in the handoff; sustained overlay,
particle, stable stand-off world-item, Alt+Tab and the full DPI matrix remain
open.

    .\gradlew.bat :game --console=plain --no-daemon
    .\gradlew.bat :game:installDist --console=plain --no-daemon
    .\game\build\install\game\bin\game.bat

Check crosshair center/resize/maximize/DPI/F1/focus, raycast alignment, Survival overlay progression/cancellation/Z-fighting, Creative no residue, continuous and committed particles, failure exclusion, world-item stable visuals, movement/jump/F4/Escape, shader diagnostics, GL errors, and exit codes.

- [x] **Step 7: Record macOS status honestly**

If no native Mac is available, record NOT RUN for build, launch, Retina, resize, overlay, alpha cutout, particles, world items, F1/focus, and shutdown. Do not infer success.

- [x] **Step 8: Independent reviews and corrections**

Status: Engine-owner and Game/render-owner reviews are complete and clean. The
first final branch-wide review returned two Important and two Minor findings;
all were corrected through focused RED/GREEN cycles. A follow-up cache-identity
Minor and one documentation Minor were also corrected. The final branch-wide
re-review is READY with 0 Critical / 0 Important / 0 Minor.

Request separate Sol High Engine-owner and Game/render-owner read-only reviews with exact file/line/scenario for every severity. Resolve every valid Critical, Important, and Minor finding with its own RED/GREEN cycle and rerun related suites. Then request a final branch-wide read-only review. READY requires zero findings.

- [x] **Step 9: Final unstaged evidence checkpoint**

Update the handoff with final HEAD, complete diff, exact test counts, resource hash, Windows results, macOS status, owner verdicts, and known risks. Confirm no stage, commit, push, PR, or merge occurred, then stop.
