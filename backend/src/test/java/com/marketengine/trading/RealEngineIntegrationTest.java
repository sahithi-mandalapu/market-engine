package com.marketengine.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import com.marketengine.core.OrderType;
import com.marketengine.core.Portfolio;
import com.marketengine.core.Side;
import com.marketengine.exchange.BookSnapshot;
import com.marketengine.exchange.ExecutionReport;
import com.marketengine.exchange.MatchingEngineClient;

/**
 * Integration test against the REAL C++ engine. Skipped unless ME_HOST / ME_PORT are set, e.g.
 *   ./matching-engine/build/me_server 9099 &
 *   ME_PORT=9099 mvn test
 */
class RealEngineIntegrationTest {

    private static MatchingEngineClient connect() {
        String port = System.getenv("ME_PORT");
        assumeTrue(port != null, "ME_PORT not set, skipping real engine test");
        String host = System.getenv().getOrDefault("ME_HOST", "127.0.0.1");
        MatchingEngineClient c = new MatchingEngineClient(host, Integer.parseInt(port));
        assumeTrue(c.ping(), "engine not reachable");
        return c;
    }

    // every test uses its own symbol because the engine keeps its books between tests
    @Test
    void orderRestsThenMatchesAndShowsInBook() {
        try (MatchingEngineClient c = connect()) {
            ExecutionReport sell = c.submit(1_000_001, "IT1", Side.SELL, OrderType.LIMIT, 100, 50.25);
            assertTrue(sell.accepted() && sell.resting());
            ExecutionReport buy = c.submit(1_000_002, "IT1", Side.BUY, OrderType.MARKET, 40, 0);
            assertEquals(1, buy.fills().size());
            assertEquals(50.25, buy.fills().get(0).price(), 1e-9);
            assertEquals(40L, buy.fills().get(0).qty());
            BookSnapshot book = c.book("IT1", 5);
            assertEquals(60L, book.asks().get(0).qty());
            assertEquals(60L, c.cancel(1_000_001, "IT1"));
            assertEquals(-1L, c.cancel(1_000_001, "IT1"));
        }
    }

    @Test
    void tradingDeskEndToEndWithLiquidityBot() {
        try (MatchingEngineClient c = connect()) {
            Portfolio pf = new Portfolio(100_000);
            TradingDesk desk = new TradingDesk(c, pf);
            LiquidityBot bot = new LiquidityBot(c, desk, 5L);
            bot.refresh("IT2", 100.0);

            var buy = desk.place("IT2", Side.BUY, OrderType.MARKET, 20, 0, 100.0);
            assertTrue(buy.status().equals("FILLED") || buy.status().equals("PARTIAL_DROPPED"));
            long got = pf.qty("IT2");
            assertTrue(got > 0);
            assertTrue(pf.cash() < 100_000);

            var sell = desk.place("IT2", Side.SELL, OrderType.MARKET, got, 0, 100.0);
            assertEquals("FILLED", sell.status());
            assertEquals(0L, pf.qty("IT2"));
            // round trip against a spread costs money: bought at the ask, sold at the bid
            assertTrue(pf.realizedPnl() < 0);
        }
    }

    @Test
    void userRestingOrderGetsFilledWhenBotQuotesCrossIt() {
        try (MatchingEngineClient c = connect()) {
            Portfolio pf = new Portfolio(100_000);
            TradingDesk desk = new TradingDesk(c, pf);
            LiquidityBot bot = new LiquidityBot(c, desk, 6L);

            // user bids 101 into an empty book, so it rests
            var order = desk.place("IT3", Side.BUY, OrderType.LIMIT, 10, 101.0, 100.0);
            assertEquals("RESTING", order.status());
            // the bot quotes around 100, its asks (100.05, 100.10, 100.20) are below our bid so they hit it.
            // that fill comes back in the bot's reply and has to reach the user's portfolio
            bot.refresh("IT3", 100.0);
            assertEquals(10L, pf.qty("IT3"));
            assertEquals(100_000 - 10 * 101.0, pf.cash(), 1e-6);  // filled at OUR resting price, 101.00
            assertTrue(desk.openOrders().isEmpty());
        }
    }
}
