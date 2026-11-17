package com.marketengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BarAggregatorTest {

    private static Tick t(long ts, double price, long size, long seq) {
        return new Tick("AAPL", ts, price, size, seq);
    }

    @Test
    void buildsOhlcvAndClosesWhenNextBucketStarts() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 0);
        assertTrue(agg.accept(t(100, 10.0, 5, 1)).isEmpty());
        assertTrue(agg.accept(t(200, 12.0, 3, 2)).isEmpty());
        assertTrue(agg.accept(t(900, 9.0, 2, 3)).isEmpty());
        assertTrue(agg.accept(t(950, 11.0, 1, 4)).isEmpty());

        List<Bar> closed = agg.accept(t(1000, 20.0, 1, 5));  // first tick of the next bar
        assertEquals(1, closed.size());
        Bar b = closed.get(0);
        assertEquals(0L, b.start());
        assertEquals(10.0, b.open(), 1e-9);
        assertEquals(12.0, b.high(), 1e-9);
        assertEquals(9.0, b.low(), 1e-9);
        assertEquals(11.0, b.close(), 1e-9);
        assertEquals(11L, b.volume());
        assertEquals(4, b.ticks());
    }

    @Test
    void duplicatesAreIgnored() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 0);
        agg.accept(t(100, 10.0, 5, 1));
        agg.accept(t(100, 10.0, 5, 1));  // same seq again
        agg.accept(t(100, 10.0, 5, 1));
        Bar b = agg.flush().get(0);
        assertEquals(5L, b.volume());
        assertEquals(1, b.ticks());
        assertEquals(2L, agg.duplicates());
    }

    @Test
    void outOfOrderTickInsideAnOpenBarFixesOpenAndClose() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 500);
        agg.accept(t(300, 11.0, 1, 2));
        agg.accept(t(100, 10.0, 1, 1));   // arrived late but its timestamp is the earliest -> becomes the open
        agg.accept(t(400, 13.0, 1, 4));
        agg.accept(t(350, 12.0, 1, 3));   // late too, but 400 is still the latest -> close stays 13
        Bar b = agg.flush().get(0);
        assertEquals(10.0, b.open(), 1e-9);
        assertEquals(13.0, b.close(), 1e-9);
        assertEquals(13.0, b.high(), 1e-9);
        assertEquals(4, b.ticks());
    }

    @Test
    void barStaysOpenForTheLatenessWindow() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 300);
        agg.accept(t(500, 10.0, 1, 1));
        assertTrue(agg.accept(t(1100, 11.0, 1, 2)).isEmpty());    // 1100 - 300 = 800 < 1000, bar 0 still open
        assertTrue(agg.accept(t(900, 10.5, 1, 3)).isEmpty());     // late tick still gets in
        List<Bar> closed = agg.accept(t(1300, 12.0, 1, 4));       // 1300 - 300 = 1000 -> bar 0 closes
        assertEquals(1, closed.size());
        assertEquals(2, closed.get(0).ticks());
        assertEquals(10.5, closed.get(0).close(), 1e-9);
    }

    @Test
    void tickForAnAlreadyClosedBarIsDropped() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 0);
        agg.accept(t(500, 10.0, 1, 1));
        assertEquals(1, agg.accept(t(1500, 11.0, 1, 2)).size());  // bar 0 emitted
        assertTrue(agg.accept(t(600, 99.0, 1, 3)).isEmpty());     // too late
        assertEquals(1L, agg.lateDropped());
        assertEquals(1, agg.flush().size());                       // only bar 1, nothing resurrected
    }

    @Test
    void gapsProduceNoEmptyBars() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 0);
        agg.accept(t(100, 10.0, 1, 1));
        List<Bar> closed = agg.accept(t(5100, 11.0, 1, 2));
        assertEquals(1, closed.size());
        assertEquals(0L, closed.get(0).start());
    }

    @Test
    void closesSeveralBarsAtOnceWhenLatenessWindowIsLarge() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 5000);
        agg.accept(t(100, 1.0, 1, 1));
        agg.accept(t(1100, 2.0, 1, 2));
        agg.accept(t(2100, 3.0, 1, 3));
        List<Bar> closed = agg.accept(t(7100, 4.0, 1, 4));  // watermark - 5000 = 2100 -> bars 0 and 1 are done
        assertEquals(2, closed.size());
        assertEquals(0L, closed.get(0).start());
        assertEquals(1000L, closed.get(1).start());
    }

    @Test
    void rejectsTicksForOtherSymbolsAndBadConfig() {
        BarAggregator agg = new BarAggregator("AAPL", 1000, 0);
        assertThrows(IllegalArgumentException.class, () -> agg.accept(new Tick("MSFT", 1, 1.0, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new BarAggregator("X", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BarAggregator("X", 1000, -1));
    }

    @Test
    void tickValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Tick("A", 1, 0.0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Tick("A", 1, -3.0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Tick("A", 1, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Tick("A", 1, 1.0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Tick(" ", 1, 1.0, 1, 1));
    }

    @Test
    void remembersOnlyRecentSeqsSoMemoryStaysBounded() {
        BarAggregator agg = new BarAggregator("AAPL", 1_000_000_000L, 0);
        for (long i = 1; i <= 25_000; i++) agg.accept(t(i, 10.0, 1, i));
        agg.accept(t(30_000, 10.0, 1, 1));   // seq 1 is long forgotten so this one is accepted again
        assertEquals(0L, agg.duplicates());
        agg.accept(t(30_001, 10.0, 1, 25_000));  // a recent seq is still remembered
        assertEquals(1L, agg.duplicates());
    }
}
