package com.gaia.shell.ui;

import com.gaia.shell.ScreenCommand;
import com.gaia.save.format.SaveGameId;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.NewWorldDraftSnapshot;
import com.gaia.shell.world.WorldSlotsController;
import com.overlord.core.input.UiInputSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Routes immutable UI samples while retaining only product-screen focus state. */
public final class ProductScreenInputController {
    private static final int KEY_SPACE = 32;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int MOUSE_BUTTON_LEFT = 0;

    private UiControlId focusedControl;
    private UiControlId presentationHighlight;
    private InputModality modality = InputModality.POINTER;
    private double lastPointerX;
    private double lastPointerY;
    private boolean pointerInitialized;
    private long lastProcessedSampleId = Long.MIN_VALUE;

    public Optional<ScreenCommand> route(UiInputSnapshot input, ProductUiLayout layout) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(layout, "layout");
        if (input.sampleId() <= lastProcessedSampleId) {
            return Optional.empty();
        }
        lastProcessedSampleId = input.sampleId();
        boolean pointerMoved = pointerInitialized
                && (Double.compare(lastPointerX, input.pointerX()) != 0
                        || Double.compare(lastPointerY, input.pointerY()) != 0);
        lastPointerX = input.pointerX();
        lastPointerY = input.pointerY();
        pointerInitialized = true;
        if (pointerMoved) {
            modality = InputModality.POINTER;
        }
        if (!input.focused()) {
            presentationHighlight = null;
            return Optional.empty();
        }

        List<UiHitRegion> enabled = layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .toList();
        if (enabled.isEmpty()) {
            focusedControl = null;
            presentationHighlight = null;
            return Optional.empty();
        }
        reconcileFocus(enabled);

        double logicalPointerX = layout.canMapWindowPointer()
                ? layout.windowToLogicalX(input.pointerX())
                : Double.NaN;
        double logicalPointerY = layout.canMapWindowPointer()
                ? layout.windowToLogicalY(input.pointerY())
                : Double.NaN;
        Optional<UiHitRegion> hovered = layout.withinViewport(logicalPointerX, logicalPointerY)
                ? enabled.stream()
                        .filter(region -> region.contains(logicalPointerX, logicalPointerY))
                        .findFirst()
                : Optional.empty();
        if (modality == InputModality.POINTER) {
            presentationHighlight = hovered.map(UiHitRegion::id).orElse(null);
            hovered.ifPresent(region -> focusedControl = region.id());
        } else {
            presentationHighlight = focusedControl;
        }

        if (hovered.isPresent() && input.isMousePressed(MOUSE_BUTTON_LEFT)) {
            return activated(hovered.orElseThrow(), logicalPointerX, logicalPointerY);
        }
        if (input.isKeyPressed(KEY_ESCAPE)) {
            return escapeCommand(enabled);
        }
        if (input.isKeyPressed(KEY_TAB) || input.isKeyPressed(KEY_DOWN)) {
            moveFocus(enabled, 1);
            selectKeyboardFocus();
        } else if (input.isKeyPressed(KEY_UP)) {
            moveFocus(enabled, -1);
            selectKeyboardFocus();
        }
        if (input.isKeyPressed(KEY_ENTER) || input.isKeyPressed(KEY_SPACE)) {
            selectKeyboardFocus();
            return commandFor(enabled, focusedControl);
        }
        return Optional.empty();
    }

    /** Immutable presentation value captured after routing the current UI sample. */
    public Optional<UiActionId> presentationHighlight() {
        return presentationHighlight instanceof UiActionId action
                ? Optional.of(action)
                : Optional.empty();
    }

    /** Routes one New World form sample and applies its text editing atomically. */
    public Optional<ScreenCommand> routeNewWorld(
            UiInputSnapshot input,
            ProductUiLayout layout,
            NewWorldDraftController draft,
            Supplier<SaveGameId> ids) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(ids, "ids");
        long previouslyProcessed = lastProcessedSampleId;
        Optional<ScreenCommand> command = route(input, layout);
        if (input.sampleId() <= previouslyProcessed || !input.focused()) {
            return Optional.empty();
        }
        if (focusedControl == UiActionId.NEW_WORLD_NAME) {
            draft.selectField(NewWorldDraftSnapshot.Field.NAME);
        } else if (focusedControl == UiActionId.NEW_WORLD_SEED) {
            draft.selectField(NewWorldDraftSnapshot.Field.SEED);
        }
        if (input.isKeyPressed(KEY_ESCAPE)
                || command.orElse(null) instanceof ScreenCommand.Back) {
            draft.reset();
            return Optional.of(new ScreenCommand.Back());
        }
        if (input.isKeyPressed(KEY_BACKSPACE)) {
            draft.backspace();
        }
        if (!input.typedCodePoints().isEmpty()) {
            draft.acceptCodePoints(input.typedCodePoints());
        }
        if (command.orElse(null) instanceof ScreenCommand.NewWorld
                && focusedControl == UiActionId.CREATE_WORLD) {
            return draft.createRequest(ids).map(ScreenCommand.CreateWorld::new);
        }
        return command;
    }

    /** Routes one World Slots sample while keeping paging and row identity authoritative. */
    public Optional<ScreenCommand> routeWorldSlots(
            UiInputSnapshot input,
            ProductUiLayout layout,
            WorldSlotsController slots) {
        Objects.requireNonNull(slots, "slots");
        long previouslyProcessed = lastProcessedSampleId;
        Optional<ScreenCommand> command = route(input, layout);
        if (input.sampleId() <= previouslyProcessed || !input.focused()) {
            return Optional.empty();
        }
        if (command.orElse(null) instanceof ScreenCommand.PreviousWorldSlotsPage) {
            slots.previousPage();
            return Optional.empty();
        }
        if (command.orElse(null) instanceof ScreenCommand.NextWorldSlotsPage) {
            slots.nextPage();
            return Optional.empty();
        }
        if (focusedControl instanceof WorldSlotControlId worldSlot) {
            slots.select(worldSlot.saveGameId());
        }
        return command;
    }

    public Optional<UiControlId> highlightedControl() {
        return Optional.ofNullable(presentationHighlight);
    }

    private void selectKeyboardFocus() {
        modality = InputModality.KEYBOARD;
        presentationHighlight = focusedControl;
    }

    private void reconcileFocus(List<UiHitRegion> enabled) {
        if (focusedControl == null) {
            focusedControl = enabled.get(0).id();
            return;
        }
        if (enabled.stream().noneMatch(region -> region.id().equals(focusedControl))) {
            if (focusedControl instanceof WorldSlotControlId) {
                focusedControl = null;
                presentationHighlight = null;
            } else {
                focusedControl = enabled.get(0).id();
            }
        }
    }

    private void moveFocus(List<UiHitRegion> enabled, int delta) {
        int current = 0;
        for (int index = 0; index < enabled.size(); index++) {
            if (enabled.get(index).id().equals(focusedControl)) {
                current = index;
                break;
            }
        }
        focusedControl = enabled.get(Math.floorMod(current + delta, enabled.size())).id();
    }

    private static Optional<ScreenCommand> escapeCommand(List<UiHitRegion> enabled) {
        if (hasAction(enabled, UiActionId.CANCEL_SETTINGS)) {
            return Optional.of(new ScreenCommand.CancelSettings());
        }
        if (hasAction(enabled, UiActionId.DISMISS)) {
            return Optional.of(new ScreenCommand.Dismiss());
        }
        if (hasAction(enabled, UiActionId.BACK)) {
            return Optional.of(new ScreenCommand.Back());
        }
        if (hasAction(enabled, UiActionId.RESUME)) {
            return Optional.of(new ScreenCommand.Resume());
        }
        if (hasAction(enabled, UiActionId.QUIT)) {
            return Optional.of(new ScreenCommand.Quit());
        }
        return Optional.empty();
    }

    private static boolean hasAction(List<UiHitRegion> regions, UiActionId action) {
        return regions.stream().anyMatch(region -> region.id() == action);
    }

    private static Optional<ScreenCommand> activated(
            UiHitRegion region,
            double logicalX,
            double logicalY) {
        ScreenCommand command = region.activate(logicalX, logicalY);
        return command instanceof ScreenCommand.None
                ? Optional.empty()
                : Optional.of(command);
    }

    private static Optional<ScreenCommand> commandFor(
            List<UiHitRegion> enabled,
            UiControlId focusedControl) {
        if (focusedControl == null) {
            return Optional.empty();
        }
        return enabled.stream()
                .filter(region -> region.id().equals(focusedControl))
                .findFirst()
                .flatMap(region -> region.command() instanceof ScreenCommand.None
                        ? Optional.empty()
                        : Optional.of(region.command()));
    }

    private enum InputModality {
        POINTER,
        KEYBOARD
    }
}
