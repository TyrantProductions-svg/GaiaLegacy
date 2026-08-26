package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.assets.GaiaResourceLoader;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.ui.GaiaUiAssetLoader;
import com.overlord.assets.AssetManager;
import com.overlord.core.Engine;
import com.overlord.core.input.InputManager;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkRepositorySnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameSessionFactoryStreamingRestoreValidationTest {
    @Test
    void productionStreamingRestorePassesSparseCheckpointToBackendAssembly() {
        SaveGameSnapshot base = GameSessionSaveLifecycleTest.snapshot();
        SaveGameSnapshot sparse = new SaveGameSnapshot(
                base.metadata(),
                base.fixedTick(),
                new ChunkRepositorySnapshot(
                        base.chunks().worldHeight(),
                        base.chunks().revisionHighWater(),
                        List.of()),
                base.player(),
                base.inventory(),
                base.worldItems());
        MainThreadGuard owner = MainThreadGuard.captureCurrentThread();
        AssetManager assets = new AssetManager(
                GameSessionFactoryStreamingRestoreValidationTest.class
                        .getClassLoader());
        RuntimeException backendOpened = new RuntimeException(
                "streaming backend opened");
        GameSessionFactory factory = new GameSessionFactory(
                new Engine(owner),
                new InputManager(owner),
                owner,
                new GaiaResourceLoader(assets).load(),
                new GaiaUiAssetLoader(assets).load(),
                false,
                (GameSessionFactory.StreamingBackendFactory) ignored -> {
                    throw backendOpened;
                });

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> factory.restore(sparse));

        assertSame(
                backendOpened,
                thrown,
                "sparse streamed checkpoints must reach backend restore assembly");
    }
}
