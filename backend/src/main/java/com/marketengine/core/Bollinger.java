package com.marketengine.core;

/** Bollinger Bands: SMA +- k standard deviations (population std dev, like most charting tools). */
public final class Bollinger {
    private final int period;
    private final double k;
    private final double[] buf;
    private int n = 0;
    private int head = 0;

    public Bollinger(int period, double k) {
        if (period < 2) throw new IllegalArgumentException("period must be >= 2");
        this.period = period;
        this.k = k;
        this.buf = new double[period];
    }

    public Bands update(double x) {
        if (n < period) {
            buf[n++] = x;
        } else {
            buf[head] = x;
            head = (head + 1) % period;
        }
        if (n < period) return Bands.EMPTY;

        double mean = 0;
        for (double v : buf) mean += v;
        mean /= period;
        double var = 0;
        for (double v : buf) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / period);
        return new Bands(mean, mean + k * sd, mean - k * sd);
    }
}
