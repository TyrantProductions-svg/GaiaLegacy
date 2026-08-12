package com.overlord.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputManagerCharacterInputTest {
    @Test
    void capturesUnicodeCodePointsOnceAndClearsOnNextSample() {
        InputManager manager = new InputManager();
        manager.onCharacter('G');
        manager.onCharacter(0x4E16);
        manager.onCharacter(0x1F30D);

        assertEquals(
                List.of((int) 'G', 0x4E16, 0x1F30D),
                manager.captureUiInput(1L).typedCodePoints());
        assertTrue(manager.captureUiInput(2L).typedCodePoints().isEmpty());
    }

    @Test
    void rejectsInvalidAndSurrogateCodePoints() {
        InputManager manager = new InputManager();

        manager.onCharacter(-1);
        manager.onCharacter(0x110000);
        manager.onCharacter(0xD800);
        manager.onCharacter(0xDFFF);
        manager.onCharacter('A');

        assertEquals(
                List.of((int) 'A'),
                manager.captureUiInput(1L).typedCodePoints());
    }

    @Test
    void boundsOneFrameAndDropsOverflowWithoutReplay() {
        InputManager manager = new InputManager();
        List<Integer> expected = new ArrayList<>();
        for (int index = 0;
                index < InputManager.MAX_TYPED_CODE_POINTS_PER_SAMPLE;
                index++) {
            int codePoint = 'A' + (index % 26);
            expected.add(codePoint);
            manager.onCharacter(codePoint);
        }
        manager.onCharacter('Z');

        assertEquals(expected, manager.captureUiInput(1L).typedCodePoints());
        assertTrue(manager.captureUiInput(2L).typedCodePoints().isEmpty());
    }

    @Test
    void focusLossAndInputInvalidationClearPendingTextIdempotently() {
        InputManager manager = new InputManager();
        manager.onCharacter('A');
        manager.onWindowFocus(false);
        manager.onWindowFocus(false);
        assertTrue(manager.captureUiInput(1L).typedCodePoints().isEmpty());

        manager.onWindowFocus(true);
        manager.onCharacter('B');
        manager.invalidateGameplayInput();
        manager.invalidateGameplayInput();
        assertTrue(manager.captureUiInput(2L).typedCodePoints().isEmpty());

        manager.onCharacter('C');
        manager.discardFixedInputEdges();
        manager.discardFixedInputEdges();
        assertTrue(manager.captureUiInput(3L).typedCodePoints().isEmpty());
    }

    @Test
    void fixedGameplayConsumptionDoesNotConsumeOrCopyUiText() {
        InputManager manager = new InputManager();
        manager.onCharacter(0x4E16);

        InputSnapshot gameplay = manager.consumeFixedInput();

        assertEquals(
                new InputSnapshot(
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        List.of()),
                gameplay);
        assertEquals(gameplay, gameplay.heldOnly());
        assertEquals(List.of(0x4E16), manager.captureUiInput(1L).typedCodePoints());
    }
}
