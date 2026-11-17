package com.marketengine.core;

/** Simple moving average. update() returns NaN until we have a full window. */
public final class Sma {
    private final int period;
    private final double[] buf;
    private int n = 0;
    private int head = 0;
    private double sum = 0;

    public Sma(int period) {
        if (period < 1) throw new IllegalArgumentException("period must be >= 1");
        this.period = period;
        this.buf = new double[period];
    }

    public double update(double x) {
        if (n < period) {
            buf[n++] = x;
            sum += x;
        } else {
            sum -= buf[head];
            buf[head] = x;
            sum += x;
            head = (head + 1) % period;
        }
        return n < period ? Double.NaN : sum / period;
    }
}
