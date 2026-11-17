package com.marketengine.trading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.marketengine.core.OrderType;
import com.marketengine.core.Portfolio;
import com.marketengine.core.Side;
import com.marketengine.exchange.ExchangeGateway;
import com.marketengine.exchange.ExecutionReport;
import com.marketengine.exchange.Fill;

/**
 * The user's side of trading: pre-trade checks, sending orders to the exchange, and turning fills
 * into portfolio changes.
 *
 * Fills can show up in two ways: in the reply to our own order, or later when somebody else's order
 * (the liquidity bot) crosses one of our resting limit orders. Both go through applyFill().
 *
 * Rules: long only (no short selling), cash and shares that are tied up in resting orders can't be
 * spent twice, market buys need 10% headroom over the last price.
 */
public final class TradingDesk {

    public static final double MARKET_BUY_BUFFER = 1.10;

    public record OpenOrder(long id, String symbol, Side side, OrderType type, double price, long remaining) {
    }

    public record OrderResponse(long orderId, String status, String reason, List<Fill> fills, long remaining,
                                boolean resting) {
    }

    public record UserFill(long orderId, String symbol, Side side, double price, long qty) {
    }

    private final ExchangeGateway exchange;
    private final Portfolio portfolio;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, OpenOrder> open = new LinkedHashMap<>();
    private Consumer<UserFill> fillListener = f -> { };

    public TradingDesk(ExchangeGateway exchange, Portfolio portfolio) {
        this.exchange = exchange;
        this.portfolio = portfolio;
    }

    public void setFillListener(Consumer<UserFill> listener) {
        this.fillListener = listener;
    }

    /** ids for orders that don't belong to the user (the bot), so they never collide with ours */
    public long nextId() {
        return ids.getAndIncrement();
    }

    public synchronized OrderResponse place(String symbol, Side side, OrderType type, long qty, double price,
                                            double markPrice) {
        long id = nextId();
        if (qty <= 0) return reject(id, "quantity must be positive");
        if (type == OrderType.LIMIT && !(price > 0)) return reject(id, "limit price must be positive");
        if (type == OrderType.MARKET && !(markPrice > 0)) return reject(id, "no market price available");

        if (side == Side.BUY) {
            double need = qty * (type == OrderType.LIMIT ? price : markPrice * MARKET_BUY_BUFFER);
            double available = portfolio.cash() - reservedCash();
            if (need > available + 1e-9) return reject(id, "insufficient cash");
        } else {
            long free = portfolio.qty(symbol) - reservedShares(symbol);
            if (qty > free) return reject(id, "insufficient shares (no short selling)");
        }

        // register first: fills for this order come back inside the submit() reply
        open.put(id, new OpenOrder(id, symbol, side, type, price, qty));
        ExecutionReport rep;
        try {
            rep = exchange.submit(id, symbol, side, type, qty, price);
        } catch (RuntimeException e) {
            open.remove(id);
            throw e;
        }
        if (!rep.accepted()) {
            open.remove(id);
            return reject(id, rep.reason());
        }

        applyFills(rep.fills());

        if (rep.resting()) {
            OpenOrder o = open.get(id);
            if (o != null) open.put(id, new OpenOrder(id, symbol, side, type, price, rep.remaining()));
        } else {
            open.remove(id);  // fully filled, or a market order whose leftover was dropped
        }
        String status = rep.resting() ? "RESTING" : (rep.remaining() > 0 ? "PARTIAL_DROPPED" : "FILLED");
        return new OrderResponse(id, status, "", rep.fills(), rep.remaining(), rep.resting());
    }

    /** cancel one of the user's resting orders. returns the remaining qty that was cancelled, -1 if it wasn't open */
    public synchronized long cancel(long orderId) {
        OpenOrder o = open.get(orderId);
        if (o == null) return -1;
        long remaining = exchange.cancel(orderId, o.symbol());
        open.remove(orderId);
        return remaining;
    }

    /** fills that happened because someone else's order hit ours */
    public synchronized void onExternalFills(List<Fill> fills) {
        applyFills(fills);
    }

    public synchronized List<OpenOrder> openOrders() {
        return new ArrayList<>(open.values());
    }

    // ------------------------------------------------------------------ internals

    private OrderResponse reject(long id, String reason) {
        return new OrderResponse(id, "REJECTED", reason, List.of(), 0, false);
    }

    private double reservedCash() {
        double r = 0;
        for (OpenOrder o : open.values()) {
            if (o.side() == Side.BUY && o.type() == OrderType.LIMIT) r += o.remaining() * o.price();
        }
        return r;
    }

    private long reservedShares(String symbol) {
        long r = 0;
        for (OpenOrder o : open.values()) {
            if (o.side() == Side.SELL && o.symbol().equals(symbol)) r += o.remaining();
        }
        return r;
    }

    private void applyFills(List<Fill> fills) {
        for (Fill f : fills) {
            OpenOrder buy = open.get(f.buyId());
            if (buy != null) {
                portfolio.buy(f.symbol(), f.qty(), f.price());
                shrink(buy, f.qty());
                fillListener.accept(new UserFill(buy.id(), f.symbol(), Side.BUY, f.price(), f.qty()));
            }
            OpenOrder sell = open.get(f.sellId());
            if (sell != null) {
                portfolio.sell(f.symbol(), f.qty(), f.price());
                shrink(sell, f.qty());
                fillListener.accept(new UserFill(sell.id(), f.symbol(), Side.SELL, f.price(), f.qty()));
            }
        }
    }

    private void shrink(OpenOrder o, long qty) {
        long left = o.remaining() - qty;
        if (left <= 0) {
            open.remove(o.id());
        } else {
            open.put(o.id(), new OpenOrder(o.id(), o.symbol(), o.side(), o.type(), o.price(), left));
        }
    }
}
