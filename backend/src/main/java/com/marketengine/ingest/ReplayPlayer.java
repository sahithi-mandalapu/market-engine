package com.marketengine.ingest;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import com.marketengine.core.Tick;

/**
 * Plays ticks back using their timestamps. speed = 1 is real time, 10 is ten times faster,
 * speed <= 0 means "no waiting at all" (as fast as the consumer can take them, used for benchmarks).
 *
 * Timing is scheduled against the start time instead of sleeping between ticks, so small sleep
 * errors don't pile up into drift.
 */
public final class ReplayPlayer {

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile long played = 0;

    public void stop() {
        stopped.set(true);
    }

    public long played() {
        return played;
    }

    /** blocks until everything was played or stop() was called. returns how many ticks were sent */
    public long play(List<Tick> ticks, double speed, Consumer<Tick> sink) {
        if (ticks.isEmpty()) return 0;
        long firstTs = ticks.get(0).ts();
        long startNanos = System.nanoTime();
        played = 0;

        for (Tick t : ticks) {
            if (stopped.get()) break;
            if (speed > 0) {
                long dueNanos = startNanos + (long) ((t.ts() - firstTs) * 1_000_000.0 / speed);
                long wait = dueNanos - System.nanoTime();
                while (wait > 0 && !stopped.get()) {
                    LockSupport.parkNanos(Math.min(wait, TimeUnit.MILLISECONDS.toNanos(50)));
                    wait = dueNanos - System.nanoTime();
                }
                if (stopped.get()) break;
            }
            sink.accept(t);
            played++;
        }
        return played;
    }
}
