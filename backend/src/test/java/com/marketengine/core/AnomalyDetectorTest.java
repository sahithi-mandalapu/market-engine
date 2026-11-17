package com.marketengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

    private static Bar bar(long i, double close, long volume) {
        return new Bar("AAPL", i * 60_000, close, close, close, close, volume, 10);
    }

    /** 100 calm bars: tiny random moves, volume around 1000 */
    private static List<Bar> calm(Random rng, int n, double startPrice) {
        List<Bar> bars = new ArrayList<>();
        double px = startPrice;
        for (int i = 0; i < n; i++) {
            px *= Math.exp(0.0005 * rng.nextGaussian());
            bars.add(bar(i, px, 1000 + rng.nextInt(100)));
        }
        return bars;
    }

    private static List<Anomaly> run(AnomalyDetector d, List<Bar> bars) {
        List<Anomaly> all = new ArrayList<>();
        for (Bar b : bars) all.addAll(d.update(b));
        return all;
    }

    @Test
    void quietMarketRaisesNothing() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        assertEquals(0, run(d, calm(new Random(1), 300, 100.0)).size());
    }

    @Test
    void noAlertsBeforeEnoughHistory() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = calm(new Random(2), 10, 100.0);
        bars.add(bar(10, 150.0, 999_999));  // absurd, but only 10 bars of history
        assertEquals(0, run(d, bars).size());
    }

    @Test
    void bigPriceMoveIsFlaggedAsJump() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = calm(new Random(3), 100, 100.0);
        double last = bars.get(bars.size() - 1).close();
        bars.add(bar(100, last * 1.03, 1000));  // +3% in one bar when the usual move is ~0.05%
        List<Anomaly> found = run(d, bars);
        assertTrue(found.stream().anyMatch(a -> a.type() == Anomaly.Type.PRICE_JUMP && a.score() > 0));
        assertTrue(found.stream().allMatch(a -> a.barStart() == 100 * 60_000L || a.type() == Anomaly.Type.MA_DEVIATION));
    }

    @Test
    void dropIsFlaggedWithNegativeScore() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = calm(new Random(4), 100, 100.0);
        double last = bars.get(bars.size() - 1).close();
        bars.add(bar(100, last * 0.96, 1000));
        List<Anomaly> found = run(d, bars);
        Anomaly jump = found.stream().filter(a -> a.type() == Anomaly.Type.PRICE_JUMP).findFirst().orElseThrow();
        assertTrue(jump.score() < 0);
    }

    @Test
    void volumeSpikeIsFlaggedButQuietVolumeIsNot() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = calm(new Random(5), 100, 100.0);
        double last = bars.get(bars.size() - 1).close();
        bars.add(bar(100, last, 20_000));   // 20x normal volume, price unchanged
        bars.add(bar(101, last, 10));       // very low volume is not an alert
        List<Anomaly> found = run(d, bars);
        assertEquals(1, found.stream().filter(a -> a.type() == Anomaly.Type.VOLUME_SPIKE).count());
        assertEquals(100 * 60_000L, found.stream().filter(a -> a.type() == Anomaly.Type.VOLUME_SPIKE).findFirst().get().barStart());
    }

    @Test
    void sustainedWildSwingsTriggerVolatilityBurst() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = calm(new Random(6), 100, 100.0);
        double px = bars.get(bars.size() - 1).close();
        // alternate +-0.6%: each move is under the jump threshold but together they're way above normal
        for (int i = 0; i < 6; i++) {
            px *= (i % 2 == 0) ? 1.006 : 0.994;
            bars.add(bar(100 + i, px, 1000));
        }
        List<Anomaly> found = run(d, bars);
        assertTrue(found.stream().anyMatch(a -> a.type() == Anomaly.Type.VOLATILITY_BURST));
    }

    @Test
    void priceFarFromMovingAverageIsFlagged() {
        AnomalyDetector d = new AnomalyDetector("AAPL", 60, 20, 100.0 /* z never fires */, 100.0, 0.02);
        List<Bar> bars = calm(new Random(7), 60, 100.0);
        double last = bars.get(bars.size() - 1).close();
        bars.add(bar(60, last * 1.05, 1000));
        List<Anomaly> found = run(d, bars);
        assertEquals(1, found.size());
        assertEquals(Anomaly.Type.MA_DEVIATION, found.get(0).type());
    }

    @Test
    void flatMarketDoesNotDivideByZero() {
        AnomalyDetector d = new AnomalyDetector("AAPL");
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) bars.add(bar(i, 100.0, 500));
        assertEquals(0, run(d, bars).size());
        // after a perfectly flat stretch, a 1% move is still a real jump
        List<Anomaly> found = d.update(bar(60, 101.0, 500));
        assertTrue(found.stream().anyMatch(a -> a.type() == Anomaly.Type.PRICE_JUMP));
    }
}
