package com.marketengine.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Virtual portfolio: cash + long-only positions with average cost accounting.
 * Uses doubles which is fine for a simulator (a real system would use fixed point / BigDecimal).
 */
public final class Portfolio {

    public record PositionView(String symbol, long qty, double avgCost, double mark, double marketValue,
                               double unrealizedPnl) {
    }

    private static final class Pos {
        long qty;
        double avgCost;
    }

    private final double startingCash;
    private double cash;
    private double realized;
    private final Map<String, Pos> positions = new TreeMap<>();

    public Portfolio(double startingCash) {
        if (startingCash < 0) throw new IllegalArgumentException("negative starting cash");
        this.startingCash = startingCash;
        this.cash = startingCash;
    }

    public synchronized void buy(String symbol, long qty, double price) {
        if (qty <= 0 || price <= 0) throw new IllegalArgumentException("bad buy " + qty + " @ " + price);
        Pos p = positions.computeIfAbsent(symbol, s -> new Pos());
        double newQty = p.qty + qty;
        p.avgCost = (p.avgCost * p.qty + price * qty) / newQty;
        p.qty += qty;
        cash -= qty * price;
    }

    public synchronized void sell(String symbol, long qty, double price) {
        if (qty <= 0 || price <= 0) throw new IllegalArgumentException("bad sell " + qty + " @ " + price);
        Pos p = positions.get(symbol);
        if (p == null || p.qty < qty) throw new IllegalArgumentException("can't sell " + qty + " " + symbol + ", only hold " + (p == null ? 0 : p.qty));
        realized += (price - p.avgCost) * qty;
        cash += qty * price;
        p.qty -= qty;
        if (p.qty == 0) positions.remove(symbol);
    }

    public synchronized double cash() { return cash; }
    public synchronized double startingCash() { return startingCash; }
    public synchronized double realizedPnl() { return realized; }

    public synchronized long qty(String symbol) {
        Pos p = positions.get(symbol);
        return p == null ? 0 : p.qty;
    }

    private static double mark(Map<String, Double> marks, String symbol, double fallback) {
        Double m = marks.get(symbol);
        return m == null ? fallback : m;
    }

    public synchronized double unrealizedPnl(Map<String, Double> marks) {
        double u = 0;
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            Pos p = e.getValue();
            u += (mark(marks, e.getKey(), p.avgCost) - p.avgCost) * p.qty;
        }
        return u;
    }

    public synchronized double equity(Map<String, Double> marks) {
        double v = cash;
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            v += mark(marks, e.getKey(), e.getValue().avgCost) * e.getValue().qty;
        }
        return v;
    }

    public synchronized List<PositionView> positions(Map<String, Double> marks) {
        List<PositionView> out = new ArrayList<>();
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            Pos p = e.getValue();
            double m = mark(marks, e.getKey(), p.avgCost);
            out.add(new PositionView(e.getKey(), p.qty, p.avgCost, m, m * p.qty, (m - p.avgCost) * p.qty));
        }
        return out;
    }

    /** share of total equity per symbol, plus CASH. adds up to 1 (unless equity is 0). */
    public synchronized Map<String, Double> allocation(Map<String, Double> marks) {
        Map<String, Double> out = new LinkedHashMap<>();
        double eq = equity(marks);
        if (eq <= 0) return out;
        for (Map.Entry<String, Pos> e : positions.entrySet()) {
            out.put(e.getKey(), mark(marks, e.getKey(), e.getValue().avgCost) * e.getValue().qty / eq);
        }
        out.put("CASH", cash / eq);
        return out;
    }
}
