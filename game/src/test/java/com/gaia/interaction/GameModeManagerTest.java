package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.input.InputSnapshot;
import com.overlord.event.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameModeManagerTest {
    @Test
    void actualTransitionPublishesOneCommittedNotification() {
        List<Event> events = new ArrayList<>();
        GameModeManager manager = new GameModeManager(GameMode.SURVIVAL, events::add);

        assertFalse(manager.setMode(GameMode.SURVIVAL, 4));
        assertTrue(manager.setMode(GameMode.CREATIVE, 5));

        assertEquals(GameMode.CREATIVE, manager.mode());
        assertEquals(1, events.size());
        GameModeChanged changed = (GameModeChanged) events.get(0);
        assertEquals(GameMode.SURVIVAL, changed.previousMode());
        assertEquals(GameMode.CREATIVE, changed.mode());
        assertEquals(5, changed.tick());
        changed.cancel();
        assertFalse(changed.isCancelled());
    }

    @Test
    void f4EdgeCancelsBeforeTogglingAndDoesNotRepeatDuringCatchUp() {
        List<String> order = new ArrayList<>();
        GameModeManager manager = new GameModeManager(
                GameMode.SURVIVAL, event -> order.add("event"));
        GameModeInputController input = new GameModeInputController(manager);
        AtomicInteger cancellations = new AtomicInteger();
        InputSnapshot pressed = new InputSnapshot(
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE),
                Set.of(GameConfig.Input.KEY_TOGGLE_GAME_MODE));

        boolean changed = input.handle(
                pressed,
                9,
                () -> {
                    cancellations.incrementAndGet();
                    order.add("cancel");
                });
        boolean repeated = input.handle(pressed.heldOnly(), 10, cancellations::incrementAndGet);

        assertTrue(changed);
        assertFalse(repeated);
        assertEquals(1, cancellations.get());
        assertEquals(List.of("cancel", "event"), order);
        assertEquals(GameMode.CREATIVE, manager.mode());
    }
}
