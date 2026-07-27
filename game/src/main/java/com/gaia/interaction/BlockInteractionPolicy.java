package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import java.util.Objects;

public interface BlockInteractionPolicy {
    BreakRule breakRule(BlockDefinition block, double baseBreakSpeed);

    boolean producesDrops();

    boolean consumesPlacement();

    static BlockInteractionPolicy forMode(GameMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == GameMode.SURVIVAL
                ? StandardPolicy.SURVIVAL
                : StandardPolicy.CREATIVE;
    }

    enum StandardPolicy implements BlockInteractionPolicy {
        SURVIVAL {
            @Override
            public BreakRule breakRule(BlockDefinition block, double baseBreakSpeed) {
                BlockDefinition definition = requireRuleInput(block, baseBreakSpeed);
                if (definition.id() == 0) {
                    return BreakRule.unbreakable();
                }
                return new BreakRule(
                        true, definition.hardness() / baseBreakSpeed);
            }

            @Override
            public boolean producesDrops() {
                return true;
            }

            @Override
            public boolean consumesPlacement() {
                return true;
            }
        },
        CREATIVE {
            @Override
            public BreakRule breakRule(BlockDefinition block, double baseBreakSpeed) {
                BlockDefinition definition = requireRuleInput(block, baseBreakSpeed);
                return definition.id() == 0
                        ? BreakRule.unbreakable()
                        : new BreakRule(true, 0);
            }

            @Override
            public boolean producesDrops() {
                return false;
            }

            @Override
            public boolean consumesPlacement() {
                return false;
            }
        };

        static BlockDefinition requireRuleInput(
                BlockDefinition block, double baseBreakSpeed) {
            BlockDefinition definition = Objects.requireNonNull(block, "block");
            if (!Double.isFinite(baseBreakSpeed) || baseBreakSpeed <= 0) {
                throw new IllegalArgumentException(
                        "baseBreakSpeed must be finite and positive");
            }
            return definition;
        }
    }
}
