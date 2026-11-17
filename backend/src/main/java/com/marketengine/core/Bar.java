package com.marketengine.core;

/** OHLCV candle. start is the bucket start in epoch millis. */
public record Bar(String symbol, long start, double open, double high, double low, double close,
                  long volume, int ticks) {
}
