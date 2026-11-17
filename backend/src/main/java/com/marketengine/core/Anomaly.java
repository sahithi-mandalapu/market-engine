package com.marketengine.core;

public record Anomaly(Type type, String symbol, long barStart, double score, String message) {
    public enum Type { PRICE_JUMP, VOLUME_SPIKE, VOLATILITY_BURST, MA_DEVIATION }
}
