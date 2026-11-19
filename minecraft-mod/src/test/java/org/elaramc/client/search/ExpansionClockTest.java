package org.elaramc.client.search;

import org.elaramc.client.config.PlayMode;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpansionClockTest {
    @Test
    void animatesAtTheConfiguredRateUsingTicks() {
        var clock = new ExpansionClock();
        for (int speed : new int[] {1, 19, 20, 23, 240}) {
            assertEquals(speed, IntStream.range(0, 20).map(tick -> clock.budget(PlayMode.ANIMATED, speed)).sum());
        }
    }

    @Test
    void stepsAndInstantRunsHaveExplicitBudgets() {
        var clock = new ExpansionClock();
        assertEquals(0, clock.budget(PlayMode.STEP, 240));
        assertEquals(128, clock.budget(PlayMode.INSTANT, 1));
        clock.budget(PlayMode.ANIMATED, 19);
        clock.reset();
        assertEquals(0, clock.budget(PlayMode.ANIMATED, 1));
    }
}
