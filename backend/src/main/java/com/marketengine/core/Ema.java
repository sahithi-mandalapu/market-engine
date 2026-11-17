package com.marketengine.core;

/**
 * Exponential moving average, alpha = 2 / (period + 1).
 * The first value is seeded with the plain average of the first `period` inputs (the usual convention),
 * so update() returns NaN until then.
 */
public final class Ema {
    private final int period;
    private final double alpha;
    private int count = 0;
    private double seedSum = 0;
    private double value = Double.NaN;

    public Ema(int period) {
        if (period < 1) throw new IllegalArgumentException("period must be >= 1");
        this.period = period;
        this.alpha = 2.0 / (period + 1);
    }

    public double update(double x) {
        if (count < period) {
            count++;
            seedSum += x;
            if (count == period) value = seedSum / period;
            return value;  // NaN while still warming up
        }
        value = alpha * x + (1 - alpha) * value;
        return value;
    }
}
