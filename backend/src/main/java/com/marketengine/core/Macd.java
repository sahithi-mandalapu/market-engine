package com.marketengine.core;

/** MACD = EMA(fast) - EMA(slow), signal = EMA of the MACD line, histogram = macd - signal. Default 12/26/9. */
public final class Macd {
    private final Ema fast, slow, signal;

    public Macd(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod >= slowPeriod) throw new IllegalArgumentException("fast must be shorter than slow");
        fast = new Ema(fastPeriod);
        slow = new Ema(slowPeriod);
        signal = new Ema(signalPeriod);
    }

    public Macd() {
        this(12, 26, 9);
    }

    public MacdValue update(double price) {
        double f = fast.update(price);
        double s = slow.update(price);
        if (Double.isNaN(s)) return MacdValue.EMPTY;  // slow is always the last one to warm up
        double line = f - s;
        double sig = signal.update(line);
        if (Double.isNaN(sig)) return new MacdValue(line, Double.NaN, Double.NaN);
        return new MacdValue(line, sig, line - sig);
    }
}
