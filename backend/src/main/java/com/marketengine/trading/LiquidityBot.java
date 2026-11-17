package com.marketengine.trading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.marketengine.core.OrderType;
import com.marketengine.core.Side;
import com.marketengine.exchange.ExchangeGateway;
import com.marketengine.exchange.ExecutionReport;

/**
 * A fake market maker. A simulator needs someone on the other side of the user's orders, so this
 * keeps a few bid and ask levels around the latest market price for each symbol.
 * Each refresh cancels the old quotes and posts new ones centered on the new price.
 */
public final class LiquidityBot {

    private static final double[] HALF_SPREADS = {0.0005, 0.0010, 0.0020};  // 5, 10 and 20 bps from mid

    private final ExchangeGateway exchange;
    private final TradingDesk desk;
    private final Random rng;
    private final Map<String, List<Long>> liveQuotes = new HashMap<>();

    public LiquidityBot(ExchangeGateway exchange, TradingDesk desk, long seed) {
        this.exchange = exchange;
        this.desk = desk;
        this.rng = new Random(seed);
    }

    public synchronized void refresh(String symbol, double mid) {
        if (!(mid > 0)) return;

        for (long id : liveQuotes.getOrDefault(symbol, List.of())) {
            exchange.cancel(id, symbol);  // -1 just means somebody already took it, fine
        }
        List<Long> fresh = new ArrayList<>();

        for (double hs : HALF_SPREADS) {
            for (Side side : Side.values()) {
                double px = side == Side.BUY ? mid * (1 - hs) : mid * (1 + hs);
                px = Math.max(0.01, Math.round(px * 100.0) / 100.0);
                long qty = 50 + rng.nextInt(151);
                long id = desk.nextId();
                ExecutionReport rep = exchange.submit(id, symbol, side, OrderType.LIMIT, qty, px);
                if (!rep.fills().isEmpty()) desk.onExternalFills(rep.fills());  // we crossed one of the user's resting orders
                if (rep.accepted() && rep.resting()) fresh.add(id);
            }
        }
        liveQuotes.put(symbol, fresh);
    }
}
