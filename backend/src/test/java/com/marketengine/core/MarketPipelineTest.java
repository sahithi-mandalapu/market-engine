package com.marketengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.marketengine.ingest.SyntheticTickGenerator;

class MarketPipelineTest {

    private static Map<String, Double> prices() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("AAPL", 190.0);
        m.put("MSFT", 410.0);
        m.put("NVDA", 125.0);
        return m;
    }

    @Test
    void ticksBecomeBarsWithIndicators() {
        MarketPipeline p = new MarketPipeline(60_000, 0);
        List<Tick> ticks = SyntheticTickGenerator.clean(prices(), 1L, 0L, 5_000).generate(3 * 12 * 120);  // ~120 minutes
        List<MarketPipeline.BarEvent> events = new ArrayList<>();
        for (Tick t : ticks) events.addAll(p.onTick(t));
        events.addAll(p.flush());

        assertTrue(events.size() >= 3 * 100);
        assertEquals(List.of("AAPL", "MSFT", "NVDA"), p.symbols());
        var last = p.latest("AAPL").orElseThrow();
        assertTrue(!Double.isNaN(last.indicators().sma50()));  // plenty of bars by now
        assertTrue(p.lastPrice("AAPL").isPresent());
        assertEquals(events.size(), p.stats().bars());
    }

    @Test
    void messyFeedDoesNotChangeTheBars() {
        // same underlying market, once clean, once with duplicates + out of order delivery:
        // with enough allowed lateness both should yield the same bars
        List<Tick> clean = SyntheticTickGenerator.clean(prices(), 9L, 0L, 5_000).generate(6000);
        List<Tick> messy = new ArrayList<>(clean);
        // ticks round-robin over 3 symbols, so i and i+3 are consecutive ticks of the SAME symbol.
        // swapping them delivers that symbol's ticks out of order (5s apart, well inside the 30s lateness)
        for (int i = 0; i + 3 < messy.size(); i += 7) {
            Tick a = messy.get(i);
            messy.set(i, messy.get(i + 3));
            messy.set(i + 3, a);
        }
        List<Tick> withDups = new ArrayList<>();
        for (int i = 0; i < messy.size(); i++) {
            withDups.add(messy.get(i));
            if (i % 10 == 0) withDups.add(messy.get(i));
        }

        List<Bar> a = barsOf(new MarketPipeline(60_000, 30_000), clean);
        List<Bar> b = barsOf(new MarketPipeline(60_000, 30_000), withDups);
        assertEquals(a, b);
    }

    private static List<Bar> barsOf(MarketPipeline p, List<Tick> ticks) {
        List<MarketPipeline.BarEvent> evs = new ArrayList<>();
        for (Tick t : ticks) evs.addAll(p.onTick(t));
        evs.addAll(p.flush());
        List<Bar> bars = new ArrayList<>();
        for (var e : evs) bars.add(e.bar());
        bars.sort((x, y) -> x.symbol().equals(y.symbol()) ? Long.compare(x.start(), y.start()) : x.symbol().compareTo(y.symbol()));
        return bars;
    }

    @Test
    void symbolsCanBeProcessedConcurrently() throws Exception {
        MarketPipeline p = new MarketPipeline(60_000, 0);
        Map<String, Double> start = prices();
        List<Tick> ticks = SyntheticTickGenerator.clean(start, 3L, 0L, 5_000).generate(3 * 3000);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> fs = new ArrayList<>();
        for (String sym : start.keySet()) {
            fs.add(pool.submit(() -> {
                int bars = 0;
                for (Tick t : ticks) {
                    if (t.symbol().equals(sym)) bars += p.onTick(t).size();
                }
                return bars;
            }));
        }
        int total = 0;
        for (Future<Integer> f : fs) total += f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // single threaded reference
        MarketPipeline ref = new MarketPipeline(60_000, 0);
        int refBars = 0;
        for (Tick t : ticks) refBars += ref.onTick(t).size();
        assertEquals(refBars, total);
        assertEquals(ref.stats().ticks(), p.stats().ticks());
    }

    @Test
    void lateTickDoesNotMoveLastPriceBackwards() {
        MarketPipeline p = new MarketPipeline(60_000, 120_000);
        p.onTick(new Tick("AAPL", 100_000, 200.0, 1, 1));
        p.onTick(new Tick("AAPL", 50_000, 150.0, 1, 2));  // older timestamp arrives later
        assertEquals(200.0, p.lastPrice("AAPL").getAsDouble(), 1e-9);
    }

    @Test
    void unknownSymbolHasNoPriceOrHistory() {
        MarketPipeline p = new MarketPipeline(60_000, 0);
        assertTrue(p.lastPrice("NOPE").isEmpty());
        assertTrue(p.recent("NOPE", 10).isEmpty());
        assertTrue(p.latest("NOPE").isEmpty());
    }

    @Test
    void resetLetsTheSameDataBeReplayed() {
        MarketPipeline p = new MarketPipeline(1_000, 0);
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 50; i++) ticks.add(new Tick("AAPL", i * 400L, 100.0 + i % 3, 1, i + 1));
        int first = 0;
        for (Tick t : ticks) first += p.onTick(t).size();
        assertTrue(first > 0);

        int withoutReset = 0;
        for (Tick t : ticks) withoutReset += p.onTick(t).size();
        assertEquals(0, withoutReset);   // every seq was already seen

        p.reset();
        assertTrue(p.symbols().isEmpty());
        int second = 0;
        for (Tick t : ticks) second += p.onTick(t).size();
        assertEquals(first, second);
    }

    @Test
    void historyIsBounded() {
        MarketPipeline p = new MarketPipeline(1_000, 0);
        for (int i = 0; i < 2000; i++) p.onTick(new Tick("AAPL", i * 1000L, 100.0 + (i % 7), 1, i + 1));
        assertEquals(500, p.recent("AAPL", 100_000).size());
        assertEquals(10, p.recent("AAPL", 10).size());
    }
}
