package org.elaramc.client.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchTimerTest {
    @Test
    void accumulatesActiveIntervalsAndFreezesDuringPause() {
        var now = new AtomicLong(1_000_000_000L);
        var timer = new SearchTimer(now::get);
        assertEquals(0, timer.tenths());
        timer.start();
        now.addAndGet(290_000_000L);
        timer.start();
        assertEquals(2, timer.tenths());
        timer.stop();
        now.addAndGet(8_000_000_000L);
        timer.stop();
        assertEquals(2, timer.tenths());
        timer.start();
        now.addAndGet(320_000_000L);
        timer.stop();
        assertEquals(6, timer.tenths());
    }

    @Test
    void resetDiscardsBothElapsedAndRunningTime() {
        var now = new AtomicLong();
        var timer = new SearchTimer(now::get);
        timer.start();
        now.addAndGet(340_000_000L);
        timer.reset();
        now.addAndGet(1_000_000_000L);
        assertEquals(0, timer.tenths());
        timer.start();
        now.addAndGet(100_000_000L);
        assertEquals(1, timer.tenths());
    }
}
