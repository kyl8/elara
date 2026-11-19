package org.elaramc.client.search;

import java.util.function.LongSupplier;

/** Measures active search time with a monotonic clock. */
final class SearchTimer {
    private final LongSupplier now;
    private long startedAt;
    private long elapsed;
    private boolean running;

    SearchTimer(LongSupplier now) {
        this.now = now;
    }

    void start() {
        if (!running) {
            startedAt = now.getAsLong();
            running = true;
        }
    }

    void stop() {
        if (running) {
            elapsed += now.getAsLong() - startedAt;
            running = false;
        }
    }

    void reset() {
        running = false;
        elapsed = 0;
    }

    long tenths() {
        long nanos = running ? elapsed + now.getAsLong() - startedAt : elapsed;
        return nanos / 100_000_000L;
    }
}
