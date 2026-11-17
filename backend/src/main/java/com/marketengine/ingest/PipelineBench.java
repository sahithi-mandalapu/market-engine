package com.marketengine.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.marketengine.core.MarketPipeline;
import com.marketengine.core.Tick;

/**
 * Measures how fast the analytics pipeline alone (bars + indicators + anomaly detection) chews through
 * ticks. NO Kafka, NO network, so this is the ceiling for one thread, not an end-to-end number.
 * usage: java -cp ... com.marketengine.ingest.PipelineBench [numTicks]
 */
public final class PipelineBench {
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;

        Map<String, Double> start = new LinkedHashMap<>();
        String[] names = {"AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "GOOG", "META", "AMD"};
        for (int i = 0; i < names.length; i++) start.put(names[i], 50.0 + i * 40);
        List<Tick> ticks = SyntheticTickGenerator.messy(start, 7L, 0L, 1000L).generate(n);

        // warm up the JIT on a throwaway pipeline
        MarketPipeline warm = new MarketPipeline(60_000, 15_000);
        for (int i = 0; i < Math.min(n, 200_000); i++) warm.onTick(ticks.get(i));

        MarketPipeline p = new MarketPipeline(60_000, 15_000);
        long t0 = System.nanoTime();
        for (Tick t : ticks) p.onTick(t);
        p.flush();
        double secs = (System.nanoTime() - t0) / 1e9;

        var s = p.stats();
        System.out.printf("ticks:      %d (%d symbols)%n", n, names.length);
        System.out.printf("bars:       %d  duplicates dropped: %d  late dropped: %d%n", s.bars(), s.duplicates(), s.lateDropped());
        System.out.printf("throughput: %.0f ticks/sec (single thread)%n", n / secs);
    }
}
