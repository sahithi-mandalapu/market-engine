package com.marketengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PortfolioRiskTest {

    private static final double EPS = 1e-9;

    @Test
    void buyUpdatesCashAndAverageCost() {
        Portfolio p = new Portfolio(10_000);
        p.buy("AAPL", 10, 100.0);
        p.buy("AAPL", 30, 120.0);  // avg = (10*100 + 30*120) / 40 = 115
        assertEquals(40L, p.qty("AAPL"));
        assertEquals(10_000 - 1000 - 3600, p.cash(), EPS);
        assertEquals(115.0, p.positions(Map.of()).get(0).avgCost(), EPS);
    }

    @Test
    void sellRealizesPnlAgainstAverageCost() {
        Portfolio p = new Portfolio(10_000);
        p.buy("AAPL", 10, 100.0);
        p.buy("AAPL", 10, 110.0);  // avg 105
        p.sell("AAPL", 5, 120.0);  // realized (120-105)*5 = 75
        assertEquals(75.0, p.realizedPnl(), EPS);
        assertEquals(15L, p.qty("AAPL"));
        p.sell("AAPL", 15, 100.0); // realized (100-105)*15 = -75
        assertEquals(0.0, p.realizedPnl(), EPS);
        assertEquals(0L, p.qty("AAPL"));
        assertTrue(p.positions(Map.of()).isEmpty());
    }

    @Test
    void cantSellWhatYouDontHave() {
        Portfolio p = new Portfolio(1000);
        assertThrows(IllegalArgumentException.class, () -> p.sell("AAPL", 1, 10.0));
        p.buy("AAPL", 5, 10.0);
        assertThrows(IllegalArgumentException.class, () -> p.sell("AAPL", 6, 10.0));
        assertEquals(5L, p.qty("AAPL"));
    }

    @Test
    void unrealizedEquityAndAllocation() {
        Portfolio p = new Portfolio(10_000);
        p.buy("AAPL", 10, 100.0);   // cash 9000
        p.buy("MSFT", 5, 200.0);    // cash 8000
        Map<String, Double> marks = Map.of("AAPL", 110.0, "MSFT", 190.0);
        assertEquals(10 * 10 - 5 * 10, p.unrealizedPnl(marks), EPS);          // +100 -50
        assertEquals(8000 + 1100 + 950, p.equity(marks), EPS);
        Map<String, Double> alloc = p.allocation(marks);
        assertEquals(1.0, alloc.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        assertEquals(1100.0 / 10050.0, alloc.get("AAPL"), 1e-12);
        assertEquals(8000.0 / 10050.0, alloc.get("CASH"), 1e-12);
    }

    @Test
    void missingMarkFallsBackToCostSoNoFakePnl() {
        Portfolio p = new Portfolio(1000);
        p.buy("AAPL", 2, 50.0);
        assertEquals(0.0, p.unrealizedPnl(Map.of()), EPS);
        assertEquals(1000.0, p.equity(Map.of()), EPS);
    }

    @Test
    void maxDrawdown() {
        // peak 120, trough 90 -> 25%
        assertEquals(0.25, RiskMetrics.maxDrawdown(List.of(100.0, 120.0, 110.0, 90.0, 100.0, 115.0)), EPS);
        assertEquals(0.0, RiskMetrics.maxDrawdown(List.of(1.0, 2.0, 3.0)), EPS);
        assertEquals(0.0, RiskMetrics.maxDrawdown(List.of()), EPS);
    }

    @Test
    void returnsAndVolatility() {
        List<Double> r = RiskMetrics.returns(List.of(100.0, 110.0, 99.0));
        assertEquals(2, r.size());
        assertEquals(0.10, r.get(0), EPS);
        assertEquals(-0.10, r.get(1), EPS);
        // sample std of {0.1, -0.1} = sqrt(0.02) ; annualized with 252 periods
        assertEquals(Math.sqrt(0.02) * Math.sqrt(252), RiskMetrics.volatility(r, 252), 1e-9);
        assertEquals(0.0, RiskMetrics.volatility(List.of(0.05), 252), EPS);
    }

    @Test
    void historicalVarAndExpectedShortfall() {
        // 100 returns: -10%, -9%, ... roughly evenly spread, with the worst five known
        java.util.ArrayList<Double> r = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) r.add(-0.10 + i * 0.002);  // -10% ... +9.8%
        // 95% VaR -> index floor(0.05*100)=5 -> sorted[5] = -0.09
        assertEquals(0.09, RiskMetrics.historicalVaR(r, 0.95), 1e-9);
        // expected shortfall: mean of worst 5 = mean(-0.10,-0.098,-0.096,-0.094,-0.092) = -0.096
        assertEquals(0.096, RiskMetrics.expectedShortfall(r, 0.95), 1e-9);
        assertTrue(RiskMetrics.expectedShortfall(r, 0.95) >= RiskMetrics.historicalVaR(r, 0.95));
        assertEquals(0.0, RiskMetrics.historicalVaR(List.of(0.01, 0.02, 0.03), 0.95), EPS);  // no losses -> 0
        assertThrows(IllegalArgumentException.class, () -> RiskMetrics.historicalVaR(r, 1.5));
    }
}
