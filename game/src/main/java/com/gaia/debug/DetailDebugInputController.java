package com.gaia.debug;

import com.gaia.interaction.BlockTargetProvider;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.DetailMutationResult;
import com.overlord.interaction.api.InteractionContext;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Development-only keyboard adapter over canonical targeting and DETAIL mutation services. */
public final class DetailDebugInputController {
    private static final int MAX_MESSAGE_CHARACTERS = 2_048;

    private final BlockTargetProvider targeting;
    private final DetailDebugTools tools;
    private final ResourceLocation primaryMaterial;
    private final ResourceLocation secondaryMaterial;
    private DetailFixturePattern selectedPattern = DetailFixturePattern.SINGLE_QUARTER;

    public DetailDebugInputController(
            BlockTargetProvider targeting,
            DetailDebugTools tools,
            ResourceLocation primaryMaterial,
            ResourceLocation secondaryMaterial) {
        this.targeting = Objects.requireNonNull(targeting, "targeting");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.primaryMaterial = Objects.requireNonNull(primaryMaterial, "primaryMaterial");
        this.secondaryMaterial = Objects.requireNonNull(secondaryMaterial, "secondaryMaterial");
    }

    public Optional<String> handle(InputSnapshot input, InteractionContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        Command command = command(input);
        if (command == Command.NONE) {
            return Optional.empty();
        }
        if (command == Command.CYCLE_FIXTURE) {
            selectedPattern = next(selectedPattern);
            return Optional.of("fixtureSelected=" + selectedPattern);
        }

        SpatialQueryResult<BlockHitResult> query =
                Objects.requireNonNull(targeting.target(), "target result");
        if (query.status() != SpatialQueryResult.Status.AVAILABLE) {
            return Optional.of(limit("target=" + query.status()
                    + " chunk=" + query.unavailableKey().orElseThrow()));
        }
        if (query.result().isEmpty()) {
            return Optional.of("target=NO_TARGET");
        }

        BlockHitResult hit = query.result().orElseThrow();
        LocalSubVoxelPosition selected = hit.target() instanceof DetailRaycastTarget detail
                ? detail.position()
                : LocalSubVoxelPosition.fromIndex(0);
        try {
            DetailDebugTools.Selection selection = tools.inspect(
                    hit.blockX(), hit.blockY(), hit.blockZ(), selected);
            return Optional.of(limit(execute(command, selection, context)));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return Optional.of(limit("status=REJECTED reason=" + failure.getMessage()));
        }
    }

    public DetailFixturePattern selectedPattern() {
        return selectedPattern;
    }

    private String execute(
            Command command,
            DetailDebugTools.Selection selection,
            InteractionContext context) {
        return switch (command) {
            case INSPECT -> tools.format(selection);
            case CONVERT -> mutationMessage(
                    "convert", tools.convert(selection, context), selection.selected());
            case FILL -> {
                requireDetail(selection);
                yield mutationMessage(
                        "fill", tools.fill(selection, context, primaryMaterial),
                        selection.selected());
            }
            case CLEAR -> {
                requireDetail(selection);
                yield mutationMessage(
                        "clear", tools.clear(selection, context), selection.selected());
            }
            case APPLY_FIXTURE -> {
                DetailDebugTools.FixtureApplication applied = tools.applyFixture(
                        selection.x(), selection.y(), selection.z(), selectedPattern,
                        primaryMaterial, secondaryMaterial, context);
                yield "fixture=" + selectedPattern
                        + " mutations=" + applied.mutations().size()
                        + " " + tools.format(applied.finalSelection());
            }
            case NONE, CYCLE_FIXTURE -> throw new IllegalStateException(
                    "non-target command reached target execution");
        };
    }

    private String mutationMessage(
            String operation,
            DetailMutationResult result,
            LocalSubVoxelPosition selected) {
        DetailMutationResult value = Objects.requireNonNull(result, "mutation result");
        return String.format(
                Locale.ROOT,
                "operation=%s status=%s selected=[%d,%d,%d] resultingRevision=%s",
                operation,
                value.status(),
                selected.x(), selected.y(), selected.z(),
                value.resultingChunkRevision());
    }

    private static void requireDetail(DetailDebugTools.Selection selection) {
        if (!(selection.state() instanceof DetailCellState)) {
            throw new IllegalArgumentException(
                    "fill/clear requires a targeted DETAIL parent");
        }
    }

    private static Command command(InputSnapshot input) {
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_NEXT)) {
            return Command.CYCLE_FIXTURE;
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_FIXTURE_APPLY)) {
            return Command.APPLY_FIXTURE;
        }
        boolean modifier = input.isKeyDown(GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_LEFT)
                || input.isKeyDown(GameConfig.Input.KEY_DEBUG_DETAIL_MODIFIER_RIGHT);
        if (modifier && input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_INSPECT)) {
            return Command.CYCLE_FIXTURE;
        }
        if (modifier && input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_CONVERT)) {
            return Command.APPLY_FIXTURE;
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_INSPECT)) {
            return Command.INSPECT;
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_CONVERT)) {
            return Command.CONVERT;
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_FILL)) {
            return Command.FILL;
        }
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_DETAIL_CLEAR)) {
            return Command.CLEAR;
        }
        return Command.NONE;
    }

    private static DetailFixturePattern next(DetailFixturePattern current) {
        DetailFixturePattern[] patterns = DetailFixturePattern.values();
        return patterns[(current.ordinal() + 1) % patterns.length];
    }

    private static String limit(String message) {
        String value = Objects.requireNonNull(message, "message");
        return value.length() <= MAX_MESSAGE_CHARACTERS
                ? value
                : value.substring(0, MAX_MESSAGE_CHARACTERS);
    }

    private enum Command {
        NONE,
        INSPECT,
        CONVERT,
        FILL,
        CLEAR,
        CYCLE_FIXTURE,
        APPLY_FIXTURE
    }
}
