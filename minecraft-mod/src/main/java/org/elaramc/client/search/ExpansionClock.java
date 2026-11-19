package org.elaramc.client.search;

import org.elaramc.client.config.PlayMode;

/** Runs at the Minecraft client tick rate. The fractional remainder survives between ticks. */
final class ExpansionClock {
    private int remainder;

    int budget(PlayMode mode, int speed) {
        return switch (mode) {
            case STEP -> 0;
            case INSTANT -> 128;
            case ANIMATED -> {
                int total = remainder + speed;
                remainder = total % 20;
                yield total / 20;
            }
        };
    }

    void reset() {
        remainder = 0;
    }
}
