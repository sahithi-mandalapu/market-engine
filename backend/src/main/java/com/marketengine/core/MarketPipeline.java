package com.marketengine.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tick -> bar -> indicators + anomalies, per symbol.
 *
 * Thread safety: different symbols can be processed by different threads at the same time (that's
 * how the Kafka consumers use it, since the topic is keyed by symbol). Within a symbol everything is
 * synchronized.
 */
public final class MarketPipeline {

    public record BarEvent(Bar bar, IndicatorSet.Snapshot indicators, List<Anomaly> anomalies) {
    }

    public record Stats(long ticks, long bars, long duplicates, long lateDropped) {
    }

    private static final int HISTORY = 500;

    private static final class SymbolState {
        final BarAggregator aggregator;
        final IndicatorSet indicators = new IndicatorSet();
        final AnomalyDetector anomalies;
        final ArrayDeque<BarEvent> history = new ArrayDeque<>();
        double lastPrice = Double.NaN;
        long lastTs;
        long bars;

        SymbolState(String symbol, long barMs, long latenessMs) {
            aggregator = new BarAggregator(symbol, barMs, latenessMs);
            anomalies = new AnomalyDetector(symbol);
        }
    }

    private final long barMs;
    private final long latenessMs;
    private final Map<String, SymbolState> symbols = new ConcurrentHashMap<>();

    public MarketPipeline(long barMs, long allowedLatenessMs) {
        this.barMs = barMs;
        this.latenessMs = allowedLatenessMs;
    }

    private SymbolState state(String symbol) {
        return symbols.computeIfAbsent(symbol, s -> new SymbolState(s, barMs, latenessMs));
    }

    /** feeds one tick, returns events for the bars that closed because of it */
    public List<BarEvent> onTick(Tick t) {
        SymbolState st = state(t.symbol());
        synchronized (st) {
            List<Bar> closed = st.aggregator.accept(t);
            if (t.ts() >= st.lastTs || Double.isNaN(st.lastPrice)) {  // don't let a late tick move "last price" backwards
                st.lastPrice = t.price();
                st.lastTs = t.ts();
            }
            return toEvents(st, closed);
        }
    }

    /** close all open bars, used at the end of a replay */
    public List<BarEvent> flush() {
        List<BarEvent> out = new ArrayList<>();
        for (String sym : new TreeSet<>(symbols.keySet())) {
            SymbolState st = symbols.get(sym);
            synchronized (st) {
                out.addAll(toEvents(st, st.aggregator.flush()));
            }
        }
        return out;
    }

    private List<BarEvent> toEvents(SymbolState st, List<Bar> closed) {
        if (closed.isEmpty()) return List.of();
        List<BarEvent> events = new ArrayList<>(closed.size());
        for (Bar b : closed) {
            IndicatorSet.Snapshot snap = st.indicators.update(b.close());
            List<Anomaly> an = st.anomalies.update(b);
            BarEvent ev = new BarEvent(b, snap, an);
            st.history.addLast(ev);
            if (st.history.size() > HISTORY) st.history.removeFirst();
            st.bars++;
            events.add(ev);
        }
        return events;
    }

    public OptionalDouble lastPrice(String symbol) {
        SymbolState st = symbols.get(symbol);
        if (st == null) return OptionalDouble.empty();
        synchronized (st) {
            return Double.isNaN(st.lastPrice) ? OptionalDouble.empty() : OptionalDouble.of(st.lastPrice);
        }
    }

    public List<BarEvent> recent(String symbol, int limit) {
        SymbolState st = symbols.get(symbol);
        if (st == null) return List.of();
        synchronized (st) {
            List<BarEvent> all = new ArrayList<>(st.history);
            return all.subList(Math.max(0, all.size() - limit), all.size());
        }
    }

    public Optional<BarEvent> latest(String symbol) {
        List<BarEvent> r = recent(symbol, 1);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** forget everything (bars, indicators, dedupe memory). used before starting a fresh replay */
    public void reset() {
        symbols.clear();
    }

    public List<String> symbols() {
        return new ArrayList<>(new TreeSet<>(symbols.keySet()));
    }

    public Stats stats() {
        long ticks = 0, bars = 0, dup = 0, late = 0;
        for (SymbolState st : symbols.values()) {
            synchronized (st) {
                ticks += st.aggregator.accepted();
                bars += st.bars;
                dup += st.aggregator.duplicates();
                late += st.aggregator.lateDropped();
            }
        }
        return new Stats(ticks, bars, dup, late);
    }
}
