package com.marketengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndicatorTest {

    private static final double EPS = 1e-9;

    @Test
    void smaIsNaNUntilWindowIsFullThenRolls() {
        Sma sma = new Sma(3);
        assertTrue(Double.isNaN(sma.update(1)));
        assertTrue(Double.isNaN(sma.update(2)));
        assertEquals(2.0, sma.update(3), EPS);   // (1+2+3)/3
        assertEquals(3.0, sma.update(4), EPS);   // (2+3+4)/3
        assertEquals(4.0, sma.update(5), EPS);
        assertEquals(20.0 / 3.0, sma.update(11), EPS);  // (4+5+11)/3
    }

    @Test
    void emaIsSeededWithSmaThenSmooths() {
        Ema ema = new Ema(3);  // alpha = 0.5
        assertTrue(Double.isNaN(ema.update(1)));
        assertTrue(Double.isNaN(ema.update(2)));
        assertEquals(2.0, ema.update(3), EPS);  // seed = SMA(1,2,3)
        assertEquals(3.0, ema.update(4), EPS);  // .5*4 + .5*2
        assertEquals(4.0, ema.update(5), EPS);  // .5*5 + .5*3
    }

    @Test
    void rsiMatchesHandComputedWilderValues() {
        Rsi rsi = new Rsi(2);
        assertTrue(Double.isNaN(rsi.update(10)));
        assertTrue(Double.isNaN(rsi.update(11)));
        // changes +1, -1 -> avgGain 0.5, avgLoss 0.5 -> RS 1 -> RSI 50
        assertEquals(50.0, rsi.update(10), EPS);
        // next change +2: avgGain = (0.5*1 + 2)/2 = 1.25, avgLoss = (0.5*1 + 0)/2 = 0.25 -> RS 5
        assertEquals(100.0 - 100.0 / 6.0, rsi.update(12), 1e-9);
    }

    @Test
    void rsiExtremes() {
        Rsi up = new Rsi(5);
        double last = Double.NaN;
        for (int i = 1; i <= 20; i++) last = up.update(i);
        assertEquals(100.0, last, EPS);

        Rsi down = new Rsi(5);
        for (int i = 20; i >= 1; i--) last = down.update(i);
        assertEquals(0.0, last, EPS);

        Rsi flat = new Rsi(5);
        for (int i = 0; i < 20; i++) last = flat.update(50);
        assertEquals(50.0, last, EPS);
    }

    @Test
    void macdIsZeroOnAConstantSeriesAndPositiveOnARisingOne() {
        Macd flat = new Macd();
        MacdValue v = MacdValue.EMPTY;
        for (int i = 0; i < 60; i++) v = flat.update(100);
        assertEquals(0.0, v.macd(), EPS);
        assertEquals(0.0, v.signal(), EPS);
        assertEquals(0.0, v.histogram(), EPS);

        Macd up = new Macd();
        for (int i = 0; i < 60; i++) v = up.update(100 + i);
        assertTrue(v.macd() > 0);
    }

    @Test
    void macdWarmupOrder() {
        Macd m = new Macd();
        MacdValue v = null;
        for (int i = 1; i <= 25; i++) v = m.update(i);
        assertTrue(Double.isNaN(v.macd()));                    // slow EMA (26) not ready yet
        v = m.update(26);
        assertTrue(!Double.isNaN(v.macd()) && Double.isNaN(v.signal()));   // line ready, signal not yet
        for (int i = 27; i <= 34; i++) v = m.update(i);        // 9th macd value arrives at price 34
        assertTrue(!Double.isNaN(v.signal()));
        assertEquals(v.macd() - v.signal(), v.histogram(), EPS);
    }

    @Test
    void macdEqualsFastMinusSlowEma() {
        Macd m = new Macd();
        Ema fast = new Ema(12), slow = new Ema(26);
        double[] prices = new double[80];
        for (int i = 0; i < prices.length; i++) prices[i] = 100 + 5 * Math.sin(i / 4.0);
        MacdValue v = null;
        double f = 0, s = 0;
        for (double p : prices) {
            v = m.update(p);
            f = fast.update(p);
            s = slow.update(p);
        }
        assertEquals(f - s, v.macd(), EPS);
    }

    @Test
    void bollingerOnKnownNumbers() {
        Bollinger bb = new Bollinger(5, 2.0);
        Bands b = null;
        for (double x : new double[]{1, 2, 3, 4, 5}) b = bb.update(x);
        double sd = Math.sqrt(2.0);  // population variance of 1..5 is 2
        assertEquals(3.0, b.mid(), EPS);
        assertEquals(3.0 + 2 * sd, b.upper(), EPS);
        assertEquals(3.0 - 2 * sd, b.lower(), EPS);
    }

    @Test
    void bollingerBandsCollapseOnConstantPrice() {
        Bollinger bb = new Bollinger(20, 2.0);
        Bands b = Bands.EMPTY;
        for (int i = 0; i < 30; i++) b = bb.update(42);
        assertEquals(42.0, b.upper(), EPS);
        assertEquals(42.0, b.lower(), EPS);
    }

    @Test
    void indicatorSetJsonUsesNullForWarmupValues() {
        IndicatorSet set = new IndicatorSet();
        var json = set.update(100).toJson();
        assertTrue(json.containsKey("rsi14"));
        assertTrue(json.get("rsi14") == null);
        assertTrue(json.get("sma20") == null);
        var later = new IndicatorSet();
        IndicatorSet.Snapshot snap = null;
        for (int i = 0; i < 60; i++) snap = later.update(100 + i % 5);
        var j2 = snap.toJson();
        assertTrue(j2.get("sma50") != null && j2.get("macdHist") != null);
    }

    @Test
    void badPeriodsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Sma(0));
        assertThrows(IllegalArgumentException.class, () -> new Ema(0));
        assertThrows(IllegalArgumentException.class, () -> new Rsi(0));
        assertThrows(IllegalArgumentException.class, () -> new Macd(26, 12, 9));
        assertThrows(IllegalArgumentException.class, () -> new Bollinger(1, 2));
    }
}
