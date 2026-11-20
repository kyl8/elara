package org.elaramc.client.search;

import java.util.function.LongSupplier;

/** Measures active time with a monotonic clock. */
public final class SearchTimer {
    private final LongSupplier now;
    private long startedAt;
    private long elapsed;
    private boolean running;

    public SearchTimer(LongSupplier now) {
        this.now = now;
    }

    public void start() {
        if (!running) {
            startedAt = now.getAsLong();
            running = true;
        }
    }

    public void stop() {
        if (running) {
            elapsed += now.getAsLong() - startedAt;
            running = false;
        }
    }

    public void reset() {
        running = false;
        elapsed = 0;
    }

    long tenths() {
        return milliseconds() / 100;
    }

    long milliseconds() {
        long nanos = running ? elapsed + now.getAsLong() - startedAt : elapsed;
        return nanos / 1_000_000;
    }

    public String label() {
        long millis = milliseconds();
        if (millis < 1000) {
            return millis == 0 ? "<1 ms" : millis + " ms";
        }
        long tenths = millis / 100;
        return tenths / 10 + "," + tenths % 10 + " s";
    }
}
