package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionPersistenceClockTest {
    @Test
    void pauseAndProcessDowntimeDoNotAdvanceAuthoritativeWorldTick() {
        SessionPersistenceClock running = SessionPersistenceClock.restored(0L, 7L);
        running.restoreAuthoritativeWorldTick(110_800L);

        // A paused frame reserves no fixed step, and process downtime has no wall-clock seam.
        SessionPersistenceClock relaunched = SessionPersistenceClock.restored(
                running.fixedTick(), running.revision());

        assertEquals(110_800L, relaunched.fixedTick());
        assertEquals(7L, relaunched.revision());
    }

    @Test
    void restoreAuthorityIsSingleUseAndCannotMoveTheClockBackward() {
        SessionPersistenceClock clock = SessionPersistenceClock.restored(0L, 0L);
        clock.restoreAuthoritativeWorldTick(110_800L);

        assertEquals(110_800L, clock.fixedTick());
        assertThrows(
                IllegalStateException.class,
                () -> clock.restoreAuthoritativeWorldTick(110_799L));
        assertThrows(
                IllegalStateException.class,
                () -> clock.restoreAuthoritativeWorldTick(110_800L));
        assertThrows(
                IllegalStateException.class,
                () -> clock.restoreAuthoritativeWorldTick(110_801L));
        assertEquals(110_800L, clock.fixedTick());
    }

    @Test
    void onlyCommittedFixedStepAdvancesTtlClockWhileRevisionMutationDoesNot() {
        SessionPersistenceClock clock = SessionPersistenceClock.restored(100L, 4L);
        clock.reserveFixedStep();
        assertEquals(100L, clock.fixedTick(), "an abandoned step must not advance time");

        SessionPersistenceClock.MutationReservation step = clock.reserveFixedStep();
        step.commit();
        step.commit();
        assertEquals(101L, clock.fixedTick());
        assertEquals(5L, clock.revision());

        clock.reserveRevisionMutation().commit();
        assertEquals(101L, clock.fixedTick());
        assertEquals(6L, clock.revision());
    }

    @Test
    void fixedTickAndRevisionOverflowFailClosedBeforeMutation() {
        SessionPersistenceClock tickMaximum =
                SessionPersistenceClock.restored(Long.MAX_VALUE, 0L);
        assertThrows(ArithmeticException.class, tickMaximum::reserveFixedStep);
        assertEquals(Long.MAX_VALUE, tickMaximum.fixedTick());

        SessionPersistenceClock revisionMaximum =
                SessionPersistenceClock.restored(9L, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, revisionMaximum::reserveRevisionMutation);
        assertEquals(9L, revisionMaximum.fixedTick());
        assertEquals(Long.MAX_VALUE, revisionMaximum.revision());
    }
}
