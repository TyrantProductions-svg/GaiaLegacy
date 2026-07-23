package com.overlord.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class FrameClockTest {
    @Test
    void firstTickIsZeroAndNormalDeltaIsPreserved() {
        long[] times = {1_000_000_000L, 1_016_000_000L};
        AtomicInteger index = new AtomicInteger();
        FrameClock clock = new FrameClock(() -> times[index.getAndIncrement()], 0.25);

        assertEquals(0.0, clock.tick(), 1.0e-9);
        assertEquals(0.016, clock.tick(), 1.0e-9);
    }

    @Test
    void negativeDeltaIsClampedToZero() {
        LongSupplier time = new LongSupplier() {
            private final long[] values = {2_000_000_000L, 1_000_000_000L};
            private int index;

            @Override
            public long getAsLong() {
                return values[index++];
            }
        };
        FrameClock clock = new FrameClock(time, 0.25);

        clock.tick();

        assertEquals(0.0, clock.tick(), 1.0e-9);
    }

    @Test
    void longFrameIsClamped() {
        long[] times = {0L, 1_000_000_000L};
        AtomicInteger index = new AtomicInteger();
        FrameClock clock = new FrameClock(() -> times[index.getAndIncrement()], 0.25);

        clock.tick();

        assertEquals(0.25, clock.tick(), 1.0e-9);
    }
}
