package com.gaia.session;

import com.gaia.save.snapshot.SaveGameSnapshot;
import java.util.Objects;

/** Test-only package bridge; it does not add production-visible authority. */
public final class SessionPersistenceTestFixture {
    private SessionPersistenceTestFixture() {}

    public static SessionPersistenceClock restoredClock(
            long fixedTick, long revision) {
        return SessionPersistenceClock.restored(fixedTick, revision);
    }

    public static SessionSaveCaptureResult captured(
            SaveGameSnapshot snapshot, long revision) {
        return restoredClock(0L, 0L).captured(
                Objects.requireNonNull(snapshot, "snapshot"), revision);
    }

    public static ClockHarness clockHarness(
            long fixedTick, long revision) {
        return new ClockHarness(restoredClock(fixedTick, revision));
    }

    public static final class ClockHarness {
        private final SessionPersistenceClock clock;

        private ClockHarness(SessionPersistenceClock clock) {
            this.clock = clock;
        }

        public void reserveFixedStepThenRun(Runnable mutation) {
            var reservation = clock.reserveFixedStep();
            mutation.run();
            reservation.commit();
        }

        public void reserveRevisionThenRun(Runnable mutation) {
            var reservation = clock.reserveRevisionMutation();
            mutation.run();
            reservation.commit();
        }

        public long fixedTick() {
            return clock.fixedTick();
        }

        public long revision() {
            return clock.revision();
        }
    }
}
