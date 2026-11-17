package com.marketengine.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.marketengine.core.Tick;

class IngestTest {

    private static Map<String, Double> prices() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("AAPL", 190.0);
        m.put("MSFT", 410.0);
        return m;
    }

    @Test
    void csvRoundTrip() throws IOException {
        List<Tick> ticks = SyntheticTickGenerator.clean(prices(), 1L, 1_000_000L, 1000).generate(50);
        StringWriter w = new StringWriter();
        CsvTicks.write(ticks, w);
        List<Tick> back = CsvTicks.read(new StringReader(w.toString()));
        assertEquals(ticks, back);
    }

    @Test
    void csvAcceptsHeaderCommentsBlankLinesAndLowercaseSymbols() throws IOException {
        String csv = "ts,symbol,price,size,seq\n# a comment\n\n1000,aapl,190.5,10,1\n2000,AAPL,190.6,5,2\n";
        List<Tick> ticks = CsvTicks.read(new StringReader(csv));
        assertEquals(2, ticks.size());
        assertEquals("AAPL", ticks.get(0).symbol());
        assertEquals(190.5, ticks.get(0).price(), 1e-9);
    }

    @Test
    void csvReportsTheBadLine() {
        IOException e1 = assertThrows(IOException.class, () -> CsvTicks.read(new StringReader("1,AAPL,10\n")));
        assertTrue(e1.getMessage().contains("line 1"));
        IOException e2 = assertThrows(IOException.class, () -> CsvTicks.read(new StringReader("1,AAPL,-5,1,1\n")));
        assertTrue(e2.getMessage().contains("bad price"));
        assertThrows(IOException.class, () -> CsvTicks.read(new StringReader("1,AAPL,abc,1,1\n")));
    }

    @Test
    void generatorIsDeterministicForASeed() {
        var a = SyntheticTickGenerator.messy(prices(), 5L, 0L, 1000).generate(500);
        var b = SyntheticTickGenerator.messy(prices(), 5L, 0L, 1000).generate(500);
        var c = SyntheticTickGenerator.messy(prices(), 6L, 0L, 1000).generate(500);
        assertEquals(a, b);
        assertTrue(!a.equals(c));
    }

    @Test
    void cleanGeneratorHasUniqueSeqsAndSensiblePrices() {
        var ticks = SyntheticTickGenerator.clean(prices(), 2L, 0L, 1000).generate(2000);
        Set<String> seen = new HashSet<>();
        for (Tick t : ticks) {
            assertTrue(seen.add(t.symbol() + "#" + t.seq()), "duplicate " + t);
            assertTrue(t.price() > 0 && t.size() > 0);
        }
    }

    @Test
    void messyGeneratorActuallyProducesDuplicatesAndDisorder() {
        var ticks = SyntheticTickGenerator.messy(prices(), 3L, 0L, 1000).generate(20_000);
        Set<String> seen = new HashSet<>();
        int dups = 0, outOfOrder = 0;
        Map<String, Long> lastSeq = new LinkedHashMap<>();
        for (Tick t : ticks) {
            if (!seen.add(t.symbol() + "#" + t.seq())) dups++;
            Long prev = lastSeq.get(t.symbol());
            if (prev != null && t.seq() < prev) outOfOrder++;
            lastSeq.merge(t.symbol(), t.seq(), Math::max);
        }
        assertTrue(dups > 50, "dups=" + dups);
        assertTrue(outOfOrder > 50, "outOfOrder=" + outOfOrder);
    }

    @Test
    void replayWithoutSpeedPlaysEverythingImmediately() {
        List<Tick> ticks = SyntheticTickGenerator.clean(prices(), 1L, 0L, 1000).generate(1000);
        List<Tick> got = new ArrayList<>();
        long t0 = System.nanoTime();
        long n = new ReplayPlayer().play(ticks, 0, got::add);
        assertEquals(1000L, n);
        assertEquals(ticks, got);
        assertTrue((System.nanoTime() - t0) < 2_000_000_000L);
    }

    @Test
    void replaySpeedScalesTheWallClockTime() {
        // 40 ticks, one every 100 ms of market time = 3.9 s of market time. at 20x that's ~195 ms.
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 40; i++) ticks.add(new Tick("AAPL", i * 100L, 100.0, 1, i + 1));
        long t0 = System.nanoTime();
        new ReplayPlayer().play(ticks, 20.0, t -> { });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 170, "finished too fast: " + ms + " ms");
        assertTrue(ms < 1000, "too slow: " + ms + " ms");
    }

    @Test
    void replayCanBeStopped() throws Exception {
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) ticks.add(new Tick("AAPL", i * 1000L, 100.0, 1, i + 1));  // 1000 s of market time
        ReplayPlayer player = new ReplayPlayer();
        long[] result = new long[1];
        Thread t = new Thread(() -> result[0] = player.play(ticks, 1.0, x -> { }));  // real time, would take 16 min
        t.start();
        Thread.sleep(150);
        player.stop();
        t.join(2000);
        assertTrue(!t.isAlive(), "player didn't stop");
        assertTrue(result[0] < 1000);
    }

    @Test
    void emptyReplayIsFine() {
        assertEquals(0L, new ReplayPlayer().play(List.of(), 1.0, t -> { }));
    }
}
