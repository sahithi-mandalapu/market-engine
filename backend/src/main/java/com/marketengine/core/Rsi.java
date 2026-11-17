package com.marketengine.core;

/**
 * Relative Strength Index with Wilder smoothing.
 * Needs period+1 prices before it gives a number (NaN before that).
 */
public final class Rsi {
    private final int period;
    private double prev = Double.NaN;
    private int changes = 0;
    private double gainSum = 0, lossSum = 0;
    private double avgGain = 0, avgLoss = 0;

    public Rsi(int period) {
        if (period < 1) throw new IllegalArgumentException("period must be >= 1");
        this.period = period;
    }

    public double update(double price) {
        if (Double.isNaN(prev)) {
            prev = price;
            return Double.NaN;
        }
        double change = price - prev;
        prev = price;
        double gain = Math.max(change, 0);
        double loss = Math.max(-change, 0);

        if (changes < period) {
            changes++;
            gainSum += gain;
            lossSum += loss;
            if (changes < period) return Double.NaN;
            avgGain = gainSum / period;
            avgLoss = lossSum / period;
        } else {
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }
        return toRsi();
    }

    private double toRsi() {
        if (avgLoss == 0) return avgGain == 0 ? 50.0 : 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }
}
