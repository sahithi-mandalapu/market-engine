package com.marketengine.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Statistical anomaly detection on closed bars, one detector per symbol.
 *
 *  PRICE_JUMP        |z-score| of the bar's log return vs the previous `window` returns
 *  VOLUME_SPIKE      z-score of the bar's volume vs the previous `window` volumes (upside only)
 *  VOLATILITY_BURST  std dev of the last 5 returns is >= volRatio times the long-run std dev
 *  MA_DEVIATION      close is more than maDeviationPct away from the 20-bar average
 *
 * Every z-score is computed against the window BEFORE the current bar is added, otherwise a big
 * spike inflates its own std dev and hides itself.
 * Nothing fires until there are minSamples bars of history.
 */
public final class AnomalyDetector {

    private static final int SHORT_VOL_WINDOW = 5;
    private static final int MA_PERIOD = 20;
    private static final double RETURN_STD_FLOOR = 1e-5;  // flat markets have ~0 std, don't divide by it

    private final String symbol;
    private final int window;
    private final int minSamples;
    private final double zThreshold;
    private final double volRatio;
    private final double maDeviationPct;

    private final ArrayDeque<Double> returns = new ArrayDeque<>();
    private final ArrayDeque<Double> volumes = new ArrayDeque<>();
    private final ArrayDeque<Double> closes = new ArrayDeque<>();
    private double prevClose = Double.NaN;

    public AnomalyDetector(String symbol, int window, int minSamples, double zThreshold, double volRatio,
                           double maDeviationPct) {
        this.symbol = symbol;
        this.window = window;
        this.minSamples = minSamples;
        this.zThreshold = zThreshold;
        this.volRatio = volRatio;
        this.maDeviationPct = maDeviationPct;
    }

    public AnomalyDetector(String symbol) {
        this(symbol, 60, 20, 4.0, 3.0, 0.02);
    }

    public List<Anomaly> update(Bar bar) {
        List<Anomaly> out = new ArrayList<>(2);
        double r = Double.NaN;
        if (!Double.isNaN(prevClose)) r = Math.log(bar.close() / prevClose);

        boolean jumped = false;
        if (!Double.isNaN(r) && returns.size() >= minSamples) {
            double mean = mean(returns);
            double sd = Math.max(std(returns, mean), RETURN_STD_FLOOR);
            double z = (r - mean) / sd;
            if (Math.abs(z) >= zThreshold) {
                jumped = true;
                out.add(new Anomaly(Anomaly.Type.PRICE_JUMP, symbol, bar.start(), z,
                        String.format(Locale.ROOT, "%s moved %+.2f%% in one bar (z=%.1f)", symbol, r * 100, z)));
            }
        }

        if (volumes.size() >= minSamples) {
            double mean = mean(volumes);
            double sd = Math.max(std(volumes, mean), 0.01 * mean + 1.0);
            double z = (bar.volume() - mean) / sd;
            if (z >= zThreshold) {
                out.add(new Anomaly(Anomaly.Type.VOLUME_SPIKE, symbol, bar.start(), z,
                        String.format(Locale.ROOT, "%s volume %d is %.1f std devs above normal (avg %.0f)",
                                symbol, bar.volume(), z, mean)));
            }
        }

        if (!jumped && !Double.isNaN(r) && returns.size() >= minSamples) {
            double longSd = Math.max(std(returns, mean(returns)), RETURN_STD_FLOOR);
            double shortSd = shortStd(r);
            if (!Double.isNaN(shortSd) && shortSd / longSd >= volRatio) {
                out.add(new Anomaly(Anomaly.Type.VOLATILITY_BURST, symbol, bar.start(), shortSd / longSd,
                        String.format(Locale.ROOT, "%s short-term volatility is %.1fx the normal level",
                                symbol, shortSd / longSd)));
            }
        }

        if (closes.size() >= MA_PERIOD) {
            double ma = 0;
            int skip = closes.size() - MA_PERIOD;
            int i = 0;
            for (double c : closes) {
                if (i++ >= skip) ma += c;
            }
            ma /= MA_PERIOD;
            double dev = (bar.close() - ma) / ma;
            if (Math.abs(dev) >= maDeviationPct) {
                out.add(new Anomaly(Anomaly.Type.MA_DEVIATION, symbol, bar.start(), dev,
                        String.format(Locale.ROOT, "%s is %+.2f%% away from its 20-bar average", symbol, dev * 100)));
            }
        }

        // now add the new bar to history
        if (!Double.isNaN(r)) push(returns, r);
        push(volumes, (double) bar.volume());
        push(closes, bar.close());
        prevClose = bar.close();
        return out;
    }

    private double shortStd(double latest) {
        if (returns.size() < SHORT_VOL_WINDOW - 1) return Double.NaN;
        double[] last = new double[SHORT_VOL_WINDOW];
        last[SHORT_VOL_WINDOW - 1] = latest;
        Double[] hist = returns.toArray(new Double[0]);
        for (int i = 0; i < SHORT_VOL_WINDOW - 1; i++) last[i] = hist[hist.length - (SHORT_VOL_WINDOW - 1) + i];
        double m = 0;
        for (double v : last) m += v;
        m /= last.length;
        double s = 0;
        for (double v : last) s += (v - m) * (v - m);
        return Math.sqrt(s / (last.length - 1));
    }

    private void push(ArrayDeque<Double> q, double v) {
        q.addLast(v);
        if (q.size() > window) q.removeFirst();
    }

    private static double mean(ArrayDeque<Double> q) {
        double s = 0;
        for (double v : q) s += v;
        return s / q.size();
    }

    private static double std(ArrayDeque<Double> q, double mean) {
        if (q.size() < 2) return 0;
        double s = 0;
        for (double v : q) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (q.size() - 1));
    }
}
