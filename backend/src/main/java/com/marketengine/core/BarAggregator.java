package com.marketengine.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns ticks for ONE symbol into fixed-size OHLCV bars.
 *
 * Real feeds are messy so this handles:
 *  - duplicates: same seq seen twice -> ignored (remembers the last few thousand seqs)
 *  - out of order ticks: a tick can still land in a bar that's open. A bar stays open until the
 *    watermark (newest timestamp seen) is more than allowedLatenessMs past the end of the bar.
 *  - too-late ticks: if the bar is already emitted we drop the tick and count it
 *
 * Not thread safe, wrap it (MarketPipeline does).
 */
public final class BarAggregator {

    private static final int SEQ_MEMORY = 10_000;

    private static final class Acc {
        double open, high, low, close;
        long openTs, openSeq, closeTs, closeSeq;
        long volume;
        int count;

        Acc(Tick t) {
            open = high = low = close = t.price();
            openTs = closeTs = t.ts();
            openSeq = closeSeq = t.seq();
            volume = t.size();
            count = 1;
        }

        void add(Tick t) {
            if (t.ts() < openTs || (t.ts() == openTs && t.seq() < openSeq)) {
                open = t.price();
                openTs = t.ts();
                openSeq = t.seq();
            }
            if (t.ts() > closeTs || (t.ts() == closeTs && t.seq() > closeSeq)) {
                close = t.price();
                closeTs = t.ts();
                closeSeq = t.seq();
            }
            high = Math.max(high, t.price());
            low = Math.min(low, t.price());
            volume += t.size();
            count++;
        }
    }

    private final String symbol;
    private final long barMs;
    private final long latenessMs;
    private final TreeMap<Long, Acc> open = new TreeMap<>();
    private final LinkedHashSet<Long> seenSeq = new LinkedHashSet<>();
    private long watermark = Long.MIN_VALUE;

    private long accepted, duplicates, lateDropped;

    public BarAggregator(String symbol, long barMs, long allowedLatenessMs) {
        if (barMs <= 0) throw new IllegalArgumentException("barMs must be positive");
        if (allowedLatenessMs < 0) throw new IllegalArgumentException("lateness can't be negative");
        this.symbol = symbol;
        this.barMs = barMs;
        this.latenessMs = allowedLatenessMs;
    }

    /** returns the bars that got closed because of this tick (usually none, sometimes one) */
    public List<Bar> accept(Tick t) {
        if (!t.symbol().equals(symbol)) throw new IllegalArgumentException("wrong symbol " + t.symbol());

        if (seenSeq.contains(t.seq())) {
            duplicates++;
            return List.of();
        }

        long bucket = Math.floorDiv(t.ts(), barMs) * barMs;
        if (watermark != Long.MIN_VALUE && bucket + barMs <= watermark - latenessMs) {
            lateDropped++;
            return List.of();
        }

        remember(t.seq());
        accepted++;
        watermark = Math.max(watermark, t.ts());
        Acc acc = open.get(bucket);
        if (acc == null) {
            open.put(bucket, new Acc(t));
        } else {
            acc.add(t);
        }

        List<Bar> closed = new ArrayList<>(1);
        Iterator<Map.Entry<Long, Acc>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Acc> e = it.next();
            if (e.getKey() + barMs <= watermark - latenessMs) {
                closed.add(toBar(e.getKey(), e.getValue()));
                it.remove();
            } else {
                break;  // TreeMap is sorted, the rest are newer
            }
        }
        return closed;
    }

    /** close everything that's still open (end of a replay / shutdown) */
    public List<Bar> flush() {
        List<Bar> out = new ArrayList<>(open.size());
        for (Map.Entry<Long, Acc> e : open.entrySet()) out.add(toBar(e.getKey(), e.getValue()));
        open.clear();
        return out;
    }

    private Bar toBar(long start, Acc a) {
        return new Bar(symbol, start, a.open, a.high, a.low, a.close, a.volume, a.count);
    }

    private void remember(long seq) {
        seenSeq.add(seq);
        if (seenSeq.size() > SEQ_MEMORY) {
            Iterator<Long> it = seenSeq.iterator();
            it.next();
            it.remove();
        }
    }

    public long accepted() { return accepted; }
    public long duplicates() { return duplicates; }
    public long lateDropped() { return lateDropped; }
}
