package com.marketengine.exchange;

/** One execution reported by the matching engine. */
public record Fill(long buyId, long sellId, String symbol, double price, long qty) {
}
