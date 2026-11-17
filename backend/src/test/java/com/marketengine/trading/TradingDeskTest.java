package com.marketengine.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import com.marketengine.core.OrderType;
import com.marketengine.core.Portfolio;
import com.marketengine.core.Side;
import com.marketengine.exchange.BookSnapshot;
import com.marketengine.exchange.ExchangeException;
import com.marketengine.exchange.ExchangeGateway;
import com.marketengine.exchange.ExecutionReport;
import com.marketengine.exchange.Fill;

class TradingDeskTest {

    /** scripted exchange: you decide what submit() answers */
    private static final class FakeExchange implements ExchangeGateway {
        final List<String> log = new ArrayList<>();
        // default: the order simply rests with its full quantity
        BiFunction<Long, Long, ExecutionReport> onSubmit = (id, q) -> new ExecutionReport(id, true, "", List.of(), q, true);
        long cancelResult = 0;

        @Override
        public ExecutionReport submit(long id, String symbol, Side side, OrderType type, long qty, double price) {
            log.add("submit " + id + " " + side + " " + qty);
            return onSubmit.apply(id, qty);
        }

        @Override
        public long cancel(long id, String symbol) {
            log.add("cancel " + id);
            return cancelResult;
        }

        @Override
        public BookSnapshot book(String symbol, int depth) {
            return new BookSnapshot(List.of(), List.of());
        }
    }

    private static final double EPS = 1e-9;

    @Test
    void marketBuyFillsAndUpdatesPortfolio() {
        FakeExchange ex = new FakeExchange();
        ex.onSubmit = (id, q) -> new ExecutionReport(id, true, "", List.of(new Fill(id, 900, "AAPL", 100.0, 10)), 0, false);
        Portfolio pf = new Portfolio(10_000);
        TradingDesk desk = new TradingDesk(ex, pf);

        var resp = desk.place("AAPL", Side.BUY, OrderType.MARKET, 10, 0, 100.0);
        assertEquals("FILLED", resp.status());
        assertEquals(10L, pf.qty("AAPL"));
        assertEquals(9_000.0, pf.cash(), EPS);
        assertTrue(desk.openOrders().isEmpty());
    }

    @Test
    void restingLimitOrderStaysOpenAndReservesCash() {
        FakeExchange ex = new FakeExchange();  // default: rests with 0 fills
        Portfolio pf = new Portfolio(1_000);
        TradingDesk desk = new TradingDesk(ex, pf);

        var first = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 8, 100.0, 100.0);  // reserves 800
        assertEquals("RESTING", first.status());
        assertEquals(1, desk.openOrders().size());

        var second = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 3, 100.0, 100.0);  // only 200 free
        assertEquals("REJECTED", second.status());
        assertEquals("insufficient cash", second.reason());
        assertEquals(1, ex.log.size());  // the rejected one never reached the exchange
    }

    @Test
    void cantSellMoreThanFreeShares() {
        FakeExchange ex = new FakeExchange();
        Portfolio pf = new Portfolio(10_000);
        pf.buy("AAPL", 10, 100.0);
        TradingDesk desk = new TradingDesk(ex, pf);

        assertEquals("RESTING", desk.place("AAPL", Side.SELL, OrderType.LIMIT, 6, 120.0, 100.0).status());
        var r = desk.place("AAPL", Side.SELL, OrderType.LIMIT, 5, 120.0, 100.0);  // 10 held - 6 reserved = 4 free
        assertEquals("REJECTED", r.status());
        assertTrue(r.reason().contains("no short selling"));
        assertEquals("RESTING", desk.place("AAPL", Side.SELL, OrderType.LIMIT, 4, 120.0, 100.0).status());
    }

    @Test
    void marketBuyNeedsHeadroomOverLastPrice() {
        TradingDesk desk = new TradingDesk(new FakeExchange(), new Portfolio(1_050));
        // 10 shares at mark 100 = 1000, but we require 10% buffer -> 1100 > 1050
        assertEquals("REJECTED", desk.place("AAPL", Side.BUY, OrderType.MARKET, 10, 0, 100.0).status());
        assertEquals("REJECTED", desk.place("AAPL", Side.BUY, OrderType.MARKET, 1, 0, 0.0).status());  // no price known
    }

    @Test
    void basicValidation() {
        TradingDesk desk = new TradingDesk(new FakeExchange(), new Portfolio(1_000));
        assertEquals("REJECTED", desk.place("AAPL", Side.BUY, OrderType.LIMIT, 0, 10.0, 10.0).status());
        assertEquals("REJECTED", desk.place("AAPL", Side.BUY, OrderType.LIMIT, 1, 0.0, 10.0).status());
    }

    @Test
    void partialFillThenRestingRemainder() {
        FakeExchange ex = new FakeExchange();
        ex.onSubmit = (id, q) -> new ExecutionReport(id, true, "", List.of(new Fill(id, 900, "AAPL", 99.0, 4)), 6, true);
        Portfolio pf = new Portfolio(10_000);
        TradingDesk desk = new TradingDesk(ex, pf);

        var resp = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 10, 100.0, 100.0);
        assertEquals("RESTING", resp.status());
        assertEquals(4L, pf.qty("AAPL"));
        assertEquals(10_000 - 4 * 99.0, pf.cash(), EPS);
        assertEquals(6L, desk.openOrders().get(0).remaining());
    }

    @Test
    void externalFillOnRestingOrderIsApplied() {
        FakeExchange ex = new FakeExchange();
        Portfolio pf = new Portfolio(10_000);
        TradingDesk desk = new TradingDesk(ex, pf);
        List<TradingDesk.UserFill> seen = new ArrayList<>();
        desk.setFillListener(seen::add);

        long id = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 10, 100.0, 100.0).orderId();
        desk.onExternalFills(List.of(new Fill(id, 777, "AAPL", 100.0, 4)));  // bot sells into our bid
        assertEquals(4L, pf.qty("AAPL"));
        assertEquals(6L, desk.openOrders().get(0).remaining());
        desk.onExternalFills(List.of(new Fill(id, 778, "AAPL", 99.5, 6)));
        assertTrue(desk.openOrders().isEmpty());
        assertEquals(10L, pf.qty("AAPL"));
        assertEquals(2, seen.size());
        assertEquals(Side.BUY, seen.get(0).side());
        assertEquals(id, seen.get(0).orderId());
    }

    @Test
    void fillsForOtherPeoplesOrdersAreIgnored() {
        Portfolio pf = new Portfolio(10_000);
        TradingDesk desk = new TradingDesk(new FakeExchange(), pf);
        desk.onExternalFills(List.of(new Fill(5000, 5001, "AAPL", 100.0, 10)));
        assertEquals(0L, pf.qty("AAPL"));
        assertEquals(10_000.0, pf.cash(), EPS);
    }

    @Test
    void cancelReleasesReservation() {
        FakeExchange ex = new FakeExchange();
        ex.cancelResult = 8;
        TradingDesk desk = new TradingDesk(ex, new Portfolio(1_000));
        long id = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 8, 100.0, 100.0).orderId();
        assertEquals(8L, desk.cancel(id));
        assertTrue(desk.openOrders().isEmpty());
        assertEquals("RESTING", desk.place("AAPL", Side.BUY, OrderType.LIMIT, 8, 100.0, 100.0).status());  // cash is free again
        assertEquals(-1L, desk.cancel(424242));  // unknown order
    }

    @Test
    void exchangeRejectionAndFailureLeaveNothingBehind() {
        FakeExchange ex = new FakeExchange();
        ex.onSubmit = (id, q) -> ExecutionReport.rejected(id, "duplicate order id");
        TradingDesk desk = new TradingDesk(ex, new Portfolio(1_000));
        var r = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 1, 10.0, 10.0);
        assertEquals("REJECTED", r.status());
        assertTrue(desk.openOrders().isEmpty());

        ex.onSubmit = (id, q) -> {
            throw new ExchangeException("engine down");
        };
        assertThrows(ExchangeException.class, () -> desk.place("AAPL", Side.BUY, OrderType.LIMIT, 1, 10.0, 10.0));
        assertTrue(desk.openOrders().isEmpty());
    }

    @Test
    void botQuotesAreRefreshedAndOldOnesCancelled() {
        FakeExchange ex = new FakeExchange();
        TradingDesk desk = new TradingDesk(ex, new Portfolio(1_000));
        LiquidityBot bot = new LiquidityBot(ex, desk, 1L);
        bot.refresh("AAPL", 100.0);
        long submits = ex.log.stream().filter(s -> s.startsWith("submit")).count();
        assertEquals(6, submits);  // 3 levels x (bid + ask)
        bot.refresh("AAPL", 101.0);
        assertEquals(6, ex.log.stream().filter(s -> s.startsWith("cancel")).count());
        assertEquals(12, ex.log.stream().filter(s -> s.startsWith("submit")).count());
        bot.refresh("AAPL", Double.NaN);  // garbage price is ignored
        assertEquals(12, ex.log.stream().filter(s -> s.startsWith("submit")).count());
    }

    @Test
    void botQuotesNeverCollideWithUserOrderIds() {
        FakeExchange ex = new FakeExchange();
        TradingDesk desk = new TradingDesk(ex, new Portfolio(100_000));
        LiquidityBot bot = new LiquidityBot(ex, desk, 2L);
        bot.refresh("AAPL", 100.0);
        long userId = desk.place("AAPL", Side.BUY, OrderType.LIMIT, 1, 50.0, 100.0).orderId();
        List<Long> all = new ArrayList<>();
        for (String line : ex.log) {
            if (line.startsWith("submit")) all.add(Long.parseLong(line.split(" ")[1]));
        }
        assertEquals(all.size(), new java.util.HashSet<>(all).size());  // no id used twice
        assertTrue(all.contains(userId));
    }
}
