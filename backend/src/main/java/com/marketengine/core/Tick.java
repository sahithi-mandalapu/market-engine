package com.marketengine.core;

import java.util.Objects;

/**
 * One trade print. seq is a per-symbol counter from the source, we use it to throw away duplicates.
 * ingestMs is the wall clock time when it was published to Kafka (0 if unknown), only used to
 * measure end-to-end latency.
 */
public record Tick(String symbol, long ts, double price, long size, long seq, long ingestMs) {

    public Tick {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol is blank");
        if (!(price > 0) || Double.isInfinite(price)) throw new IllegalArgumentException("bad price: " + price);
        if (size < 0) throw new IllegalArgumentException("negative size: " + size);
    }

    public Tick(String symbol, long ts, double price, long size, long seq) {
        this(symbol, ts, price, size, seq, 0L);
    }

    public Tick withIngestMs(long ms) {
        return new Tick(symbol, ts, price, size, seq, ms);
    }
}
