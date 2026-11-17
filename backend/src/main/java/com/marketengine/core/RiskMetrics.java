package com.marketengine.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Basic risk numbers computed from an equity curve or a list of returns. All static, no state. */
public final class RiskMetrics {
    private RiskMetrics() {}

    /** simple returns between consecutive points: (v[i] / v[i-1]) - 1 */
    public static List<Double> returns(List<Double> equityCurve) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            double prev = equityCurve.get(i - 1);
            if (prev > 0) out.add(equityCurve.get(i) / prev - 1.0);
        }
        return out;
    }

    /** sample std dev of the returns, scaled by sqrt(periodsPerYear) to annualize */
    public static double volatility(List<Double> returns, double periodsPerYear) {
        int n = returns.size();
        if (n < 2) return 0;
        double mean = 0;
        for (double r : returns) mean += r;
        mean /= n;
        double s = 0;
        for (double r : returns) s += (r - mean) * (r - mean);
        return Math.sqrt(s / (n - 1)) * Math.sqrt(periodsPerYear);
    }

    /** biggest peak-to-trough fall as a positive fraction (0.12 = the curve fell 12% from a peak) */
    public static double maxDrawdown(List<Double> equityCurve) {
        double peak = Double.NEGATIVE_INFINITY;
        double worst = 0;
        for (double v : equityCurve) {
            peak = Math.max(peak, v);
            if (peak > 0) worst = Math.max(worst, (peak - v) / peak);
        }
        return worst;
    }

    /**
     * Historical Value at Risk: with `confidence` (e.g. 0.95) the one-period loss should not be bigger
     * than this number, based on the empirical return distribution. Returned as a positive fraction.
     */
    public static double historicalVaR(List<Double> returns, double confidence) {
        if (returns.isEmpty()) return 0;
        if (confidence <= 0 || confidence >= 1) throw new IllegalArgumentException("confidence must be in (0,1)");
        double[] sorted = returns.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int idx = (int) Math.floor((1 - confidence) * sorted.length);
        idx = Math.min(Math.max(idx, 0), sorted.length - 1);
        return Math.max(0, -sorted[idx]);
    }

    /** Expected shortfall (CVaR): the average loss in the worst (1-confidence) of cases. */
    public static double expectedShortfall(List<Double> returns, double confidence) {
        if (returns.isEmpty()) return 0;
        double[] sorted = returns.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int cut = Math.max(1, (int) Math.floor((1 - confidence) * sorted.length));
        double sum = 0;
        for (int i = 0; i < cut; i++) sum += sorted[i];
        return Math.max(0, -sum / cut);
    }
}
