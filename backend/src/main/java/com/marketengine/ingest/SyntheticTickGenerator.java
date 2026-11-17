package com.marketengine.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.marketengine.core.Tick;

/**
 * Fake but reasonably realistic market data so the whole thing is demoable without a data vendor.
 *
 * Price follows a geometric random walk. Every so often there's a jump or a volume spike, so the
 * anomaly detector has something to find. Optionally some ticks are duplicated or delivered out of
 * order, the way a real feed misbehaves. Seeded, so the same seed gives the same data.
 */
public final class SyntheticTickGenerator {

    private final Random rng;
    private final long stepMs;
    private final double sigma;
    private final double jumpProb;
    private final double volumeSpikeProb;
    private final double dupProb;
    private final double outOfOrderProb;

    private final Map<String, Double> prices = new LinkedHashMap<>();
    private final Map<String, Long> seqs = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final ArrayDeque<Tick> pending = new ArrayDeque<>();
    private final Map<String, Tick> heldBack = new LinkedHashMap<>();  // out-of-order ticks waiting for their turn
    private long clock;
    private int turn = 0;

    public SyntheticTickGenerator(Map<String, Double> startPrices, long seed, long startTs, long stepMs,
                                  double sigma, double jumpProb, double volumeSpikeProb, double dupProb,
                                  double outOfOrderProb) {
        this.rng = new Random(seed);
        this.clock = startTs;
        this.stepMs = stepMs;
        this.sigma = sigma;
        this.jumpProb = jumpProb;
        this.volumeSpikeProb = volumeSpikeProb;
        this.dupProb = dupProb;
        this.outOfOrderProb = outOfOrderProb;
        for (var e : startPrices.entrySet()) {
            prices.put(e.getKey(), e.getValue());
            seqs.put(e.getKey(), 0L);
            order.add(e.getKey());
        }
    }

    /** sensible defaults: clean data, rare jumps/volume spikes */
    public static SyntheticTickGenerator clean(Map<String, Double> startPrices, long seed, long startTs, long stepMs) {
        return new SyntheticTickGenerator(startPrices, seed, startTs, stepMs, 0.0004, 0.0008, 0.002, 0, 0);
    }

    /** same but with duplicates and out of order delivery */
    public static SyntheticTickGenerator messy(Map<String, Double> startPrices, long seed, long startTs, long stepMs) {
        return new SyntheticTickGenerator(startPrices, seed, startTs, stepMs, 0.0004, 0.0008, 0.002, 0.01, 0.02);
    }

    public Tick next() {
        if (!pending.isEmpty()) return pending.poll();

        String sym = order.get(turn % order.size());
        turn++;
        if (turn % order.size() == 0) clock += stepMs;

        double px = prices.get(sym);
        double move = sigma * rng.nextGaussian();
        if (rng.nextDouble() < jumpProb) move += (rng.nextBoolean() ? 1 : -1) * (0.012 + 0.01 * rng.nextDouble());
        px = Math.max(0.01, px * Math.exp(move));
        px = Math.round(px * 100.0) / 100.0;
        prices.put(sym, px);

        long size = 20 + Math.round(Math.exp(3.0 + 0.8 * rng.nextGaussian()));
        if (rng.nextDouble() < volumeSpikeProb) size *= 15;

        long seq = seqs.merge(sym, 1L, Long::sum);
        Tick t = new Tick(sym, clock + rng.nextInt((int) Math.max(1, stepMs)), px, size, seq);

        if (rng.nextDouble() < dupProb) pending.add(t);  // same tick again right after

        // out of order: hold this tick back and deliver it right after the NEXT tick of the same symbol,
        // so that symbol's ticks arrive in the wrong order (a different symbol arriving first wouldn't count)
        Tick release = heldBack.remove(sym);
        if (release != null) pending.add(release);
        if (rng.nextDouble() < outOfOrderProb && release == null) {
            heldBack.put(sym, t);
            return next();
        }
        return t;
    }

    public List<Tick> generate(int n) {
        List<Tick> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(next());
        return out;
    }
}
