# Phase 13C Audio Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cross-platform OpenAL music/SFX foundation, package verified OGG derivatives of Gaia and Legacy, and integrate non-duplicating menu/gameplay/pause music behavior.

**Architecture:** `AudioDevice` owns one owner-thread `AudioBackend`, falling back to `SilentAudioBackend` on native failure. `OpenAlAudioBackend` streams bounded PCM buffers decoded from OGG through STB; `MusicManager` controls track intent and fade/duck state without touching gameplay.

**Tech Stack:** Java 17, LWJGL 3.3.3 OpenAL/STB, OGG Vorbis, existing `AssetManager`, JUnit 6.1.1, Gradle resource packaging, external verified FFmpeg conversion tool.

## Global Constraints

- Add only `org.lwjgl:lwjgl-openal` under the existing LWJGL 3.3.3 BOM plus matching platform natives.
- All OpenAL device/context/source/buffer operations run on the product owner thread.
- No audio callback mutates gameplay and no gameplay state depends on audio success.
- Use bounded streaming buffers; do not permanently decode both full songs to PCM.
- Do not claim gapless looping.
- Credit exactly: `Music by Leo Deng (Leosteeeve) and David Li (Omi Hurricane)`.
- Record only the confirmed GaiaLegacy redistribution authorization; do not invent a CC or standalone reuse license.
- Stop for user authorization if a verifiable MP3-to-OGG tool is not already available.
- Do not stage, commit, push, create a PR, or merge.

## File Structure

Audio engine:

- `engine/src/main/java/com/overlord/audio/AudioBackend.java`: playback backend interface.
- `engine/src/main/java/com/overlord/audio/AudioBackendFactory.java`: injectable production/failure creation.
- `engine/src/main/java/com/overlord/audio/AudioAssetSource.java`: injectable compressed-resource byte source.
- `engine/src/main/java/com/overlord/audio/AudioDevice.java`: fallback, bus state, owner-thread close.
- `engine/src/main/java/com/overlord/audio/AudioDiagnostic.java`: bounded diagnostic value.
- `engine/src/main/java/com/overlord/audio/AudioBusSettings.java`: immutable clamped gains.
- `engine/src/main/java/com/overlord/audio/MusicHandle.java`: backend handle value.
- `engine/src/main/java/com/overlord/audio/SoundEvent.java`: stable SFX event ID.
- `engine/src/main/java/com/overlord/audio/SoundCue.java`: resource/category/base-gain value.
- `engine/src/main/java/com/overlord/audio/SilentAudioBackend.java`: no-native fallback.
- `engine/src/main/java/com/overlord/audio/openal/OpenAlApi.java`: testable OpenAL call boundary.
- `engine/src/main/java/com/overlord/audio/openal/LwjglOpenAlApi.java`: LWJGL implementation.
- `engine/src/main/java/com/overlord/audio/openal/OpenAlAudioBackend.java`: device/context/source/stream buffers.
- `engine/src/main/java/com/overlord/audio/vorbis/VorbisDecoder.java`: bounded streaming decoder interface.
- `engine/src/main/java/com/overlord/audio/vorbis/StbVorbisDecoder.java`: STB implementation.

Game audio:

- `game/src/main/java/com/gaia/audio/GaiaMusicCatalog.java`: Gaia/Legacy resource descriptors.
- `game/src/main/java/com/gaia/audio/MusicRoute.java`: Main/Gameplay/Paused/Stopped intent.
- `game/src/main/java/com/gaia/audio/MusicManager.java`: track identity, envelope, duck/focus policy.
- `game/src/main/java/com/gaia/audio/MusicManagerSnapshot.java`: immutable diagnostics/measurement.
- `game/src/main/java/com/gaia/audio/GaiaAudioSettingsAdapter.java`: implements Gate 13B `AudioSettingsPort`.

Assets and documentation:

- `game/src/main/source-assets/audio/Gaia.mp3`
- `game/src/main/source-assets/audio/Legacy.mp3`
- `game/src/main/resources/assets/gaia/audio/music/gaia.ogg`
- `game/src/main/resources/assets/gaia/audio/music/legacy.ogg`
- `docs/audio-provenance.md`
- `game/build.gradle`: packaged-audio verification.
- `engine/build.gradle`: OpenAL dependency/native.

---

### Task 1: Verify conversion tooling, preserve sources, and produce derivatives

**Files:**
- Source read: human-supplied external `Gaia.mp3` (machine-local path omitted)
- Source read: human-supplied external `Legacy.mp3` (machine-local path omitted)
- Create binary: `game/src/main/source-assets/audio/Gaia.mp3`
- Create binary: `game/src/main/source-assets/audio/Legacy.mp3`
- Create binary: `game/src/main/resources/assets/gaia/audio/music/gaia.ogg`
- Create binary: `game/src/main/resources/assets/gaia/audio/music/legacy.ogg`
- Create: `docs/audio-provenance.md`

**Interfaces:**
- Produces: exact runtime resources `gaia:audio/music/gaia.ogg` and `gaia:audio/music/legacy.ogg`.
- Produces: source and derivative provenance with reproducible command.

- [ ] **Step 1: Check for a verifiable FFmpeg toolchain without modifying the machine**

Run: `ffmpeg -version`

Run: `ffprobe -version`

Expected: both commands report an identifiable version. If either command is unavailable, stop and ask the user to authorize installation of a specific tool/version. Do not copy or transcode assets before that decision.

- [ ] **Step 2: Reconfirm source identity before copying**

Run PowerShell `Get-FileHash -Algorithm SHA256` for both supplied MP3 paths.

Expected:

- Gaia: `D3F7CB27AE858E9982C7B7D75FFB3677A5BD338F5C0F9776DD1493CE72B1CFB4`
- Legacy: `7872FEC2E9C135411542F6690136EFEF1F63D1566E2D9A26D614FF0F7B6E23DB`

If either differs, stop and report the new hash; do not assume it is the approved source.

- [ ] **Step 3: Copy the exact approved MP3 sources to the non-runtime source-assets directory**

Use literal source and destination paths. Confirm the destination hashes exactly match Step 2. `source-assets` must not be added to Gradle runtime resources.

- [ ] **Step 4: Convert with fixed Vorbis parameters**

Run from a temporary directory, then copy verified outputs into the runtime paths:

```powershell
ffmpeg -nostdin -y -i "Gaia.mp3" -map_metadata -1 -vn -c:a libvorbis -q:a 6 -ar 44100 -ac 2 "gaia.ogg"
ffmpeg -nostdin -y -i "Legacy.mp3" -map_metadata -1 -vn -c:a libvorbis -q:a 6 -ar 44100 -ac 2 "legacy.ogg"
```

Do not overwrite the original MP3 files.

- [ ] **Step 5: Verify derivative audio properties**

Run `ffprobe -v error -show_entries format=duration:stream=codec_name,sample_rate,channels -of json` for each OGG.

Expected: codec `vorbis`, sample rate `44100`, channels `2`, duration within 0.10 seconds of 289.8285833 and 252.6824583 respectively.

Compute SHA-256 and exact byte size for both derivatives.

- [ ] **Step 6: Write complete provenance**

`docs/audio-provenance.md` records:

- public credit exactly as approved;
- original collaborative authorship and authorization for GaiaLegacy source/installers/public releases;
- no claim of a broader standalone license;
- original filenames, sizes, durations, sample rate, channels, bitrate, SHA-256;
- derivative paths, sizes, durations, sample rate, channels, codec, SHA-256;
- exact FFmpeg/FFprobe versions and commands from Steps 1/4;
- the date of conversion.

- [ ] **Step 7: Audit asset scope**

Run: `git status --short --untracked-files=all`

Expected: exactly four intended audio files and the provenance document are added for this task, in addition to already approved Phase 13 docs and the pre-existing ZIP. No temp conversion output appears.

---

### Task 2: OpenAL dependency and packaged-audio contracts

**Files:**
- Modify: `engine/build.gradle:50-59`
- Modify: `game/build.gradle:51-126`
- Test: `engine/src/test/java/com/overlord/audio/OpenAlDependencyContractTest.java`
- Test: `game/src/test/java/com/gaia/audio/PackagedAudioResourceTest.java`

**Interfaces:**
- Produces: compile/runtime OpenAL classes and matching platform native selection.
- Produces: build failures when either OGG is absent from JAR/installDist.

- [ ] **Step 1: Write RED dependency/resource structure tests**

The engine test reads `engine/build.gradle` and asserts both declarations exist:

```java
assertTrue(script.contains("api \"org.lwjgl:lwjgl-openal\""));
assertTrue(script.contains("runtimeOnly \"org.lwjgl:lwjgl-openal::$lwjglNatives\""));
```

The game resource test opens both exact classpath locations through `AssetManager` and asserts non-empty OggS headers.

- [ ] **Step 2: Run RED before Gradle changes**

Run: `./gradlew.bat :engine:test --tests com.overlord.audio.OpenAlDependencyContractTest --console=plain --no-daemon`

Expected: FAIL because OpenAL is not declared.

Run: `./gradlew.bat :game:test --tests com.gaia.audio.PackagedAudioResourceTest --console=plain --no-daemon`

Expected before runtime files are in place: FAIL for missing resources; after Task 1 it may already pass and is retained as the asset RED evidence boundary.

- [ ] **Step 3: Add the exact approved dependency**

Add next to the existing LWJGL modules:

```groovy
api "org.lwjgl:lwjgl-openal"
runtimeOnly "org.lwjgl:lwjgl-openal::$lwjglNatives"
```

Do not change the BOM version or native classifier logic.

- [ ] **Step 4: Extend packaged and installed resource checks**

Add both OGG paths to `verifyPackagedResources` and the installed game JAR list in `verifyInstalledShaderResources`. Do not add source MP3 files to runtime checks.

- [ ] **Step 5: Run GREEN and dependency resolution**

Run: `./gradlew.bat :engine:test --tests com.overlord.audio.OpenAlDependencyContractTest --console=plain --no-daemon`

Run: `./gradlew.bat :game:verifyPackagedResources :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon`

Expected: PASS and installDist contains one matching OpenAL native JAR for the current platform.

---

### Task 3: Audio values, gain buses, Silent fallback, and owner-thread device

**Files:**
- Create: `engine/src/main/java/com/overlord/audio/AudioBackend.java`
- Create: `engine/src/main/java/com/overlord/audio/AudioBackendFactory.java`
- Create: `engine/src/main/java/com/overlord/audio/AudioAssetSource.java`
- Create: `engine/src/main/java/com/overlord/audio/AudioDevice.java`
- Create: `engine/src/main/java/com/overlord/audio/AudioDiagnostic.java`
- Create: `engine/src/main/java/com/overlord/audio/AudioBusSettings.java`
- Create: `engine/src/main/java/com/overlord/audio/MusicHandle.java`
- Create: `engine/src/main/java/com/overlord/audio/SoundEvent.java`
- Create: `engine/src/main/java/com/overlord/audio/SoundCue.java`
- Create: `engine/src/main/java/com/overlord/audio/SilentAudioBackend.java`
- Test: `engine/src/test/java/com/overlord/audio/AudioBusSettingsTest.java`
- Test: `engine/src/test/java/com/overlord/audio/AudioDeviceTest.java`
- Test: `engine/src/test/java/com/overlord/audio/SilentAudioBackendTest.java`

**Interfaces:**
- Produces: `AudioDevice.open(factory, guard, diagnostics)`, `applyBusSettings`, `startMusic`, `setMusicEnvelope`, `isMusicPlaying`, `stopMusic`, `update`, and idempotent `close`.
- Produces: `AudioBackend.startMusic`, `setMusicGain`, `isMusicPlaying`, `stopMusic`, `update`, and `close`.

- [ ] **Step 1: Write RED gain/value tests**

```java
@ParameterizedTest
@CsvSource({"1.0,1.0,1.0,1.0", "0.5,0.4,0.25,0.05", "0.0,1.0,1.0,0.0"})
void effectiveGainIsMasterTimesChannelTimesCue(
        float master, float channel, float cue, float expected) {
    assertEquals(expected, new AudioBusSettings(master, channel, 1.0f)
            .effectiveMusicGain(cue), 1.0e-6f);
}
```

Reject non-finite gains; clamp user-facing values to 0..1 at the settings boundary and require already-valid values in engine records.

- [ ] **Step 2: Write RED fallback/close tests**

Inject a factory that throws during OpenAL construction. Assert one diagnostic, Silent backend selection, update no-op, close twice, wrong-thread failure, and no post-close start/update.

- [ ] **Step 3: Run RED**

Run: `./gradlew.bat :engine:test --tests 'com.overlord.audio.*' --console=plain --no-daemon`

Expected: FAIL because audio types do not exist.

- [ ] **Step 4: Implement minimal backend/device values**

Use this backend surface:

```java
public interface AudioBackend extends AutoCloseable {
    MusicHandle startMusic(ResourceLocation track, boolean loop);
    void setMusicGain(MusicHandle handle, float gain);
    boolean isMusicPlaying(MusicHandle handle);
    void stopMusic(MusicHandle handle);
    void update();
    @Override void close();
}
```

`AudioDevice` catches only initialization failure at `open`, reports it once, and installs `SilentAudioBackend`. `AudioAssetSource` exposes `ByteBuffer read(ResourceLocation)`; the production adapter reads through `AssetManager` and returns a caller-owned direct buffer. `setMusicEnvelope(handle, cueGain, envelope)` multiplies the validated cue/envelope by the current Master and Music buses exactly once before forwarding effective gain to the backend. Operations after close throw except repeated close. Every public operation asserts the captured `MainThreadGuard`.

- [ ] **Step 5: Run GREEN**

Run the Task 3 test command. Expected: PASS.

---

### Task 4: Bounded STB Vorbis streaming and OpenAL backend

**Files:**
- Create: `engine/src/main/java/com/overlord/audio/openal/OpenAlApi.java`
- Create: `engine/src/main/java/com/overlord/audio/openal/LwjglOpenAlApi.java`
- Create: `engine/src/main/java/com/overlord/audio/openal/OpenAlAudioBackend.java`
- Create: `engine/src/main/java/com/overlord/audio/vorbis/VorbisDecoder.java`
- Create: `engine/src/main/java/com/overlord/audio/vorbis/StbVorbisDecoder.java`
- Test: `engine/src/test/java/com/overlord/audio/openal/OpenAlAudioBackendTest.java`
- Test: `engine/src/test/java/com/overlord/audio/vorbis/StbVorbisDecoderTest.java`
- Test: `engine/src/test/java/com/overlord/audio/openal/OpenAlAudioBackendOwnerThreadTest.java`
- Test: `game/src/test/java/com/gaia/audio/GaiaMusicAssetDecodeTest.java`

**Interfaces:**
- Consumes: an `AudioAssetSource` adapter backed by `AssetManager.open(ResourceLocation)` and Task 3 `AudioBackend`.
- Produces: production backend with one source and exactly three streaming buffers per active music voice.

- [ ] **Step 1: RED-test real OGG decoder metadata and bounded reads**

In the game-module `GaiaMusicAssetDecodeTest`, open both production OGG resources through `AssetManager`, pass their compressed bytes to the public STB decoder boundary, and assert stereo/44.1 kHz plus a bounded PCM block. In the engine decoder test, use corrupt bytes to prove typed diagnostic failure and no native leak. This preserves the engine-to-game dependency direction.

- [ ] **Step 2: RED-test streaming buffer behavior through recording OpenAlApi**

```java
@Test
void musicUsesThreeBuffersAndRefillsOnlyProcessedBuffers() {
    backend.startMusic(GAIA, true);
    assertEquals(3, api.generatedBufferCount());
    api.reportProcessedBuffers(1);
    backend.update();
    assertEquals(1, api.unqueuedCount());
    assertEquals(1, api.requeuedCount());
    assertTrue(decoder.maximumRequestedFrames() <= FRAMES_PER_BUFFER);
}
```

Also assert zero processed buffers perform no upload, end-of-track ordinary replay, stop deletes source/buffers, close reverses context/device lifetime, close twice, and no arbitrary voice growth.

- [ ] **Step 3: Run RED**

Run: `./gradlew.bat :engine:test --tests 'com.overlord.audio.openal.*' --tests 'com.overlord.audio.vorbis.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests com.gaia.audio.GaiaMusicAssetDecodeTest --console=plain --no-daemon`

Expected: FAIL because production decoder/backend types do not exist.

- [ ] **Step 4: Implement STB decoder lifetime**

Read compressed OGG bytes into an owned direct buffer, open `stb_vorbis`, validate exactly 1 or 2 channels and a positive sample rate, expose bounded interleaved signed-16 PCM reads, and free the decoder and compressed buffer exactly once. Decoder operations after close throw.

- [ ] **Step 5: Implement OpenAL device/context and streaming**

Open default device, create context, make it current on the owner thread, create capabilities, then allocate a source plus exactly three PCM buffers for music. Each update unqueues only the reported processed buffers, refills them, and requeues them. At EOF with loop requested, reopen/seek the decoder and fill from the start; allow an ordinary boundary and make no gapless assertion.

On any partial initialization failure, close created source/buffers/context/device in reverse order and attach cleanup failures as suppressed.

- [ ] **Step 6: Run focused GREEN**

Run the Task 4 test command. Expected: PASS without requiring a live audio device for recording-API unit tests.

- [ ] **Step 7: Run a live device initialization probe only through the game runtime**

Do not add a CI test that assumes an audio device. The real runtime checkpoint in Task 7 is the authoritative native probe.

---

### Task 5: MusicManager state, fades, ducking, and duplicate suppression

**Files:**
- Create: `game/src/main/java/com/gaia/audio/GaiaMusicCatalog.java`
- Create: `game/src/main/java/com/gaia/audio/MusicRoute.java`
- Create: `game/src/main/java/com/gaia/audio/MusicManager.java`
- Create: `game/src/main/java/com/gaia/audio/MusicManagerSnapshot.java`
- Create: `game/src/main/java/com/gaia/audio/GaiaAudioSettingsAdapter.java`
- Test: `game/src/test/java/com/gaia/audio/GaiaMusicCatalogTest.java`
- Test: `game/src/test/java/com/gaia/audio/MusicManagerTest.java`
- Test: `game/src/test/java/com/gaia/audio/GaiaAudioSettingsAdapterTest.java`

**Interfaces:**
- Consumes: `AudioDevice`, settings volume/focus policy, and product route/focus state.
- Produces: `MusicManager.requestRoute`, `setFocused`, `update(double)`, snapshot, and idempotent close.

- [ ] **Step 1: Write RED catalog and state-transition tests**

Assert exact resources:

```java
assertEquals(ResourceLocation.parse("gaia:audio/music/gaia.ogg"), catalog.gaia());
assertEquals(ResourceLocation.parse("gaia:audio/music/legacy.ogg"), catalog.legacy());
```

Then assert Main starts one Gaia handle, Gameplay retains the same handle/position, Pause never starts another handle, Return Main restores the target, and Legacy is selectable only by explicit catalog request in tests.

- [ ] **Step 2: Write RED envelope tests**

Use deterministic deltas to assert 2.0-second startup fade, 0.35-second pause duck to 70%, 0.20-second focus mute, bounded delta handling, exact settle, no overshoot, and no updates after close.

- [ ] **Step 3: Run RED**

Run: `./gradlew.bat :game:test --tests 'com.gaia.audio.*' --console=plain --no-daemon`

Expected: FAIL because game music types do not exist.

- [ ] **Step 4: Implement deterministic presentation envelopes**

Track current route, desired track, one optional active handle, current envelope, target envelope, and fade rate. Re-requesting the same desired track is a no-op. Compute the presentation target only as route duck × focus multiplier; `AudioDevice.setMusicEnvelope` applies Master × Music × cue exactly once. Clamp presentation delta to the existing maximum frame delta and approach the target without overshoot.

- [ ] **Step 5: Implement Settings adapter and run GREEN**

`GaiaAudioSettingsAdapter` applies Master/Music/SFX buses to AudioDevice and mute-when-unfocused policy to MusicManager without exposing either object to Settings UI.

Run the Task 5 test command. Expected: PASS.

---

### Task 6: Product-loop audio integration and packaged-resource regression

**Files:**
- Modify: `game/src/main/java/com/gaia/shell/ProductLoop.java`
- Modify: `game/src/main/java/com/gaia/GameBootstrap.java`
- Modify: `game/src/main/java/com/gaia/settings/SettingsApplier.java`
- Modify: `game/src/test/java/com/gaia/shell/ProductLoopTest.java`
- Test: `game/src/test/java/com/gaia/audio/ProductMusicLifecycleIntegrationTest.java`
- Modify: `game/src/test/java/com/gaia/ui/UiPackagedResourceContractTest.java` only if shared packaging enumeration is used

**Interfaces:**
- Consumes: Product route/focus state and Gate 13B audio settings port.
- Produces: one global music lifetime across zero or more game sessions.

- [ ] **Step 1: Write RED full route lifecycle test**

Drive Main Menu → Loading → Playing → Paused → Settings → Paused → Playing → Main Menu → Quit. Assert exactly one Gaia start, no stop until product close, expected gain targets, audio update once per product frame, and close before engine/window shutdown.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :game:test --tests com.gaia.audio.ProductMusicLifecycleIntegrationTest --tests com.gaia.shell.ProductLoopTest --console=plain --no-daemon`

Expected: FAIL because ProductLoop does not yet own MusicManager.

- [ ] **Step 3: Integrate owner-thread audio composition**

Bootstrap opens `AudioDevice` after GLFW/engine owner capture and before ProductLoop. Register cleanup so MusicManager closes before AudioDevice and AudioDevice before Engine/window. ProductLoop maps route/focus to music intent and calls one `musicManager.update(frameDeltaSeconds)` before gameplay fixed steps.

- [ ] **Step 4: Run focused GREEN and packaged checks**

Run the Task 6 test command.

Run: `./gradlew.bat :engine:test --tests 'com.overlord.audio.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.audio.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:verifyPackagedResources :game:verifyInstalledShaderResources --rerun-tasks --console=plain --no-daemon`

Expected: PASS.

---

### Task 7: Gate 13C real Windows audio acceptance

**Files:**
- Update after evidence: `docs/agent-handoffs/phase-13-handoff.md`
- Update after actual hashes: `docs/audio-provenance.md`

**Interfaces:**
- Produces: real native playback/cleanup evidence.

- [ ] **Step 1: Run pre-runtime checks**

Run: `git diff --check`

Run: `git status --short --untracked-files=all`

Run: `./gradlew.bat :engine:test --tests 'com.overlord.audio.*' --console=plain --no-daemon`

Run: `./gradlew.bat :game:test --tests 'com.gaia.audio.*' --console=plain --no-daemon`

- [ ] **Step 2: Launch the real Windows game and keep it open**

Run: `./gradlew.bat :game:run --console=plain --no-daemon`

Interactively verify:

1. Gaia audibly starts at Main Menu and fades in;
2. New World does not restart the track;
3. Pause ducks smoothly and Resume restores smoothly;
4. Settings Master/Music volume changes apply; SFX remains a valid silent bus without fabricated gameplay cues;
5. Alt+Tab fades to mute when enabled and restores on focus;
6. Return Main does not duplicate/restart music;
7. repeated New World/Pause/Main transitions do not add voices or resources;
8. clean Quit produces no OpenAL/native exception;
9. relaunch opens and closes the native backend again cleanly.

- [ ] **Step 3: Distinguish fallback from playback success**

If startup selected Silent backend, record the diagnostic and mark native audio acceptance FAIL/PENDING even if the game remained playable. Do not claim Gaia playback from automated tests.

- [ ] **Step 4: Record actual evidence only**

Record OS, device diagnostic, track behavior, settings behavior, exit/relaunch behavior, and actual PASS/FAIL in the Phase 13 handoff. If the agent cannot hear/operate the runtime, keep it available and state `WINDOWS AUDIO RETEST REQUIRED BY USER`.

Any native failure triggers systematic debugging plus TDD, focused GREEN, and an immediate repeat of this live path before Phase 13D.
