package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.feedback.DetailPlacementGhostAdapter;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.interaction.api.BlockRaycastService;
import com.overlord.interaction.api.DetailMutationService;
import com.overlord.interaction.api.SpatialBlockRaycastService;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.inventory.api.InventoryService;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.World;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailInteractionArchitectureTest {
    @Test
    void previewAndPreviewOwnerCarryNoWorldMutationAuthority() {
        List<Class<?>> forbiddenAuthorities = List.of(
                ChunkRepository.class,
                World.class,
                DetailMutationService.class,
                WorldMutationService.class,
                BodyInventoryService.class,
                InventoryService.class,
                WorldItemService.class,
                WorldItemSpawnReservations.class,
                CollisionWorld.class,
                ChunkMeshManager.class,
                BlockRaycast.class,
                BlockRaycastService.class,
                SpatialBlockRaycastService.class,
                BlockTargetProvider.class);
        for (Class<?> previewType : List.of(
                DetailPlacementPreview.class,
                DetailPreviewController.class,
                DetailTargeting.class,
                DetailPlacementGhostAdapter.class)) {
            for (Class<?> forbidden : forbiddenAuthorities) {
                assertFalse(
                        surfaceCarries(previewType, forbidden),
                        () -> previewType.getSimpleName()
                                + " must not carry "
                                + forbidden.getSimpleName());
            }
        }
        assertFalse(Arrays.stream(DetailTargeting.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType().getSimpleName().contains("Mutation")));
    }

    @Test
    void oneExistingControllerAndTargetingHelperRemainTheOnlyAuthorities() {
        assertTrue(java.lang.reflect.Modifier.isFinal(BlockInteractionController.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(DetailTargeting.class.getModifiers()));
        assertFalse(Arrays.stream(DetailTargeting.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getSimpleName().contains("Raycast")));
    }

    private static boolean hasComponentAssignableTo(Class<?> recordType, Class<?> forbidden) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getType)
                .anyMatch(forbidden::isAssignableFrom);
    }

    private static boolean surfaceCarries(Class<?> type, Class<?> forbidden) {
        boolean recordComponent = type.isRecord()
                && hasComponentAssignableTo(type, forbidden);
        boolean field = Arrays.stream(type.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(forbidden::isAssignableFrom);
        boolean constructorParameter = Arrays.stream(type.getDeclaredConstructors())
                .map(Executable::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(forbidden::isAssignableFrom);
        boolean methodSurface = Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> methodCarries(method, forbidden));
        return recordComponent || field || constructorParameter || methodSurface;
    }

    private static boolean methodCarries(Method method, Class<?> forbidden) {
        return forbidden.isAssignableFrom(method.getReturnType())
                || Arrays.stream(method.getParameterTypes())
                        .anyMatch(forbidden::isAssignableFrom);
    }
}
