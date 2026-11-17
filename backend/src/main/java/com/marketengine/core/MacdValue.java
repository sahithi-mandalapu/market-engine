package com.marketengine.core;

public record MacdValue(double macd, double signal, double histogram) {
    public static final MacdValue EMPTY = new MacdValue(Double.NaN, Double.NaN, Double.NaN);
}
