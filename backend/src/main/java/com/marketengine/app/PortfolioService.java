package com.marketengine.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.marketengine.core.MarketPipeline;
import com.marketengine.core.Portfolio;
import com.marketengine.core.RiskMetrics;
import com.marketengine.trading.TradingDesk;

/** Portfolio numbers for the dashboard. Samples equity once a second to build a curve for the risk metrics. */
@Service
public class PortfolioService {
    private static final int MAX_SAMPLES = 5000;

    private final Portfolio portfolio;
    private final MarketPipeline pipeline;
    private final TradingDesk desk;
    private final ArrayDeque<Double> curve = new ArrayDeque<>();

    public PortfolioService(Portfolio portfolio, MarketPipeline pipeline, TradingDesk desk) {
        this.portfolio = portfolio;
        this.pipeline = pipeline;
        this.desk = desk;
    }

    public Map<String, Double> marks() {
        Map<String, Double> marks = new HashMap<>();
        for (String sym : pipeline.symbols()) {
            pipeline.lastPrice(sym).ifPresent(p -> marks.put(sym, p));
        }
        return marks;
    }

    @Scheduled(fixedRate = 1000)
    public void sample() {
        double equity = portfolio.equity(marks());
        synchronized (curve) {
            curve.addLast(equity);
            if (curve.size() > MAX_SAMPLES) curve.removeFirst();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Double> marks = marks();
        double equity = portfolio.equity(marks);

        List<Double> samples;
        synchronized (curve) {
            samples = new ArrayList<>(curve);
        }
        List<Double> returns = RiskMetrics.returns(samples);

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("samples", samples.size());
        // per 1-second sample. not annualized, because under replay the wall clock isn't market time
        risk.put("volatilityPerSample", RiskMetrics.volatility(returns, 1));
        risk.put("maxDrawdown", RiskMetrics.maxDrawdown(samples));
        risk.put("var95", RiskMetrics.historicalVaR(returns, 0.95));
        risk.put("expectedShortfall95", RiskMetrics.expectedShortfall(returns, 0.95));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startingCash", portfolio.startingCash());
        m.put("cash", portfolio.cash());
        m.put("equity", equity);
        m.put("realizedPnl", portfolio.realizedPnl());
        m.put("unrealizedPnl", portfolio.unrealizedPnl(marks));
        m.put("totalPnl", equity - portfolio.startingCash());
        m.put("positions", portfolio.positions(marks));
        m.put("allocation", portfolio.allocation(marks));
        m.put("openOrders", desk.openOrders());
        m.put("risk", risk);
        return m;
    }
}
