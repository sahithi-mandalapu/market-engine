package com.marketengine.core;

public record Bands(double mid, double upper, double lower) {
    public static final Bands EMPTY = new Bands(Double.NaN, Double.NaN, Double.NaN);
}
