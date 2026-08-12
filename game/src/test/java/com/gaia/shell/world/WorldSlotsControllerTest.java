package com.gaia.shell.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldSlotsControllerTest {
    @Test
    void emptyCatalogProducesOneEmptyPageWithNoSelectionOrNavigation() {
        WorldSlotsSnapshot snapshot = new WorldSlotsController(() -> List.of(), 3).snapshot();

        assertEquals(List.of(), snapshot.rows());
        assertEquals(0, snapshot.pageIndex());
        assertEquals(1, snapshot.pageCount());
        assertTrue(snapshot.selectedId().isEmpty());
        assertFalse(snapshot.hasPreviousPage());
        assertFalse(snapshot.hasNextPage());
    }

    @Test
    void pagesUseModifiedDescendingThenStableIdAscendingAndClampAfterRefresh() {
        ArrayList<SaveSummary> source = new ArrayList<>(List.of(
                summary(3, "older", "2026-08-10T00:00:00Z", SaveSummary.Health.VALID),
                summary(2, "tie-b", "2026-08-12T00:00:00Z", SaveSummary.Health.VALID),
                summary(1, "tie-a", "2026-08-12T00:00:00Z", SaveSummary.Health.VALID),
                summary(4, "oldest", "2026-08-09T00:00:00Z", SaveSummary.Health.VALID)));
        SaveCatalog catalog = () -> List.copyOf(source);
        WorldSlotsController controller = new WorldSlotsController(catalog, 2);

        assertEquals(List.of(id(1), id(2)), ids(controller.snapshot()));
        controller.nextPage();
        assertEquals(List.of(id(3), id(4)), ids(controller.snapshot()));

        source.removeIf(row -> row.id().equals(id(3)) || row.id().equals(id(4)));
        controller.refresh();
        assertEquals(0, controller.snapshot().pageIndex());
        assertEquals(List.of(id(1), id(2)), ids(controller.snapshot()));

        source.add(summary(5, "newest", "2026-08-13T00:00:00Z", SaveSummary.Health.VALID));
        controller.refresh();
        assertEquals(List.of(id(5), id(1)), ids(controller.snapshot()));
        assertTrue(controller.snapshot().hasNextPage());
    }

    @Test
    void selectionFollowsStableIdAcrossReorderAndClearsWhenTheRowDisappears() {
        ArrayList<SaveSummary> source = new ArrayList<>(List.of(
                summary(1, "a", "2026-08-12T00:00:00Z", SaveSummary.Health.VALID),
                summary(2, "b", "2026-08-11T00:00:00Z", SaveSummary.Health.VALID)));
        WorldSlotsController controller = new WorldSlotsController(() -> List.copyOf(source), 4);
        controller.select(id(2));

        source.set(1, summary(2, "b", "2026-08-13T00:00:00Z", SaveSummary.Health.VALID));
        controller.refresh();
        assertEquals(Optional.of(id(2)), controller.snapshot().selectedId());
        assertEquals(id(2), controller.snapshot().rows().get(0).id());

        source.removeIf(row -> row.id().equals(id(2)));
        controller.refresh();
        assertTrue(controller.snapshot().selectedId().isEmpty());
    }

    @Test
    void healthMapsToClosedPrimaryActionsWhileDeleteAlwaysTargetsTheStableId() {
        for (SaveSummary.Health health : SaveSummary.Health.values()) {
            SaveSummary row = summary(health.ordinal() + 1, health.name(),
                    "2026-08-12T00:00:00Z", health);
            WorldSlotsController controller = new WorldSlotsController(() -> List.of(row), 4);

            Optional<ScreenCommand> expected = switch (health) {
                case VALID -> Optional.of(new ScreenCommand.LoadWorld(row.id()));
                case RECOVERABLE_BACKUP -> Optional.of(
                        new ScreenCommand.RecoverBackup(row.id()));
                case CORRUPT, UNSUPPORTED_VERSION -> Optional.empty();
            };
            assertEquals(expected, controller.primaryCommand(row.id()), health.name());
            assertEquals(new ScreenCommand.DeleteWorld(row.id()),
                    controller.deleteCommand(row.id()));
        }
    }

    private static List<SaveGameId> ids(WorldSlotsSnapshot snapshot) {
        return snapshot.rows().stream().map(SaveSummary::id).toList();
    }

    private static SaveSummary summary(
            int suffix, String name, String modified, SaveSummary.Health health) {
        return new SaveSummary(
                id(suffix),
                name,
                Optional.empty(),
                Instant.parse(modified),
                Optional.of(100L + suffix),
                Optional.empty(),
                health,
                List.of());
    }

    private static SaveGameId id(int suffix) {
        return SaveGameId.parse(String.format(
                "00000000-0000-0000-0000-%012d", suffix));
    }
}
