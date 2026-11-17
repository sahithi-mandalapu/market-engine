package com.marketengine.ingest;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.marketengine.core.MarketPipeline;
import com.marketengine.core.Tick;

/**
 * Writes a sample "trading day" CSV that the replay uses.
 * usage: java -cp ... com.marketengine.ingest.GenerateSample <out.csv> [ticksPerSymbol]
 */
public final class GenerateSample {
    public static void main(String[] args) throws IOException {
        String out = args.length > 0 ? args[0] : "sample_session.csv";
        int perSymbol = args.length > 1 ? Integer.parseInt(args[1]) : 3000;

        Map<String, Double> start = new LinkedHashMap<>();
        start.put("AAPL", 190.00);
        start.put("MSFT", 410.00);
        start.put("NVDA", 125.00);
        start.put("TSLA", 245.00);

        long open = 1_731_594_600_000L;  // 2024-11-14 14:30 UTC, the NYSE open
        SyntheticTickGenerator gen = SyntheticTickGenerator.messy(start, 42L, open, 8_000L);
        List<Tick> ticks = gen.generate(perSymbol * start.size());

        try (FileWriter w = new FileWriter(out, StandardCharsets.UTF_8)) {
            CsvTicks.write(ticks, w);
        }

        // sanity summary: what does the pipeline make of it?
        MarketPipeline p = new MarketPipeline(60_000, 15_000);
        long anomalies = 0;
        for (Tick t : ticks) {
            for (var ev : p.onTick(t)) anomalies += ev.anomalies().size();
        }
        for (var ev : p.flush()) anomalies += ev.anomalies().size();
        var s = p.stats();
        System.out.printf("wrote %d ticks to %s%n", ticks.size(), out);
        System.out.printf("pipeline: %d bars, %d duplicates dropped, %d late dropped, %d anomalies flagged%n",
                s.bars(), s.duplicates(), s.lateDropped(), anomalies);
    }
}
