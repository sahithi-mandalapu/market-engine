package com.marketengine.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.marketengine.core.OrderType;
import com.marketengine.core.Side;

/** talks to a tiny fake server that replies with canned lines, so no C++ binary is needed */
class MatchingEngineClientTest {

    /** accepts connections; for each received line calls the handler, which returns reply lines (null = hang up). */
    private static final class FakeServer implements AutoCloseable {
        final ServerSocket server;
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile Function<String, List<String>> handler;
        volatile int connections = 0;

        FakeServer(Function<String, List<String>> handler) throws IOException {
            this.server = new ServerSocket(0);
            this.handler = handler;
            Thread t = new Thread(this::acceptLoop, "fake-engine");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        private void acceptLoop() {
            try {
                while (!server.isClosed()) {
                    Socket s = server.accept();
                    sockets.add(s);
                    connections++;
                    Thread h = new Thread(() -> serve(s));
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException ignored) {
                // closed
            }
        }

        private void serve(Socket s) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    List<String> reply = handler.apply(line);
                    if (reply == null) {
                        s.close();
                        return;
                    }
                    for (String r : reply) out.print(r + "\n");
                    out.print("OK\n");
                    out.flush();
                }
            } catch (IOException ignored) {
                // client went away
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : sockets) s.close();
        }
    }

    @Test
    void submitParsesTradesAndAck() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> List.of(
                "TRADE 5 1 AAPL 190.50 40", "TRADE 5 2 AAPL 190.55 10", "ACK 5 RESTING 50"));
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            ExecutionReport r = c.submit(5, "AAPL", Side.BUY, OrderType.LIMIT, 100, 190.55);
            assertTrue(r.accepted());
            assertEquals(2, r.fills().size());
            assertEquals(new Fill(5, 1, "AAPL", 190.50, 40), r.fills().get(0));
            assertEquals(50L, r.remaining());
            assertTrue(r.resting());
            assertEquals("NEW 5 AAPL BUY LIMIT 100 190.55", fake.received.get(0));
        }
    }

    @Test
    void marketOrdersSendZeroPrice() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> List.of("ACK 9 DONE 0"));
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            ExecutionReport r = c.submit(9, "MSFT", Side.SELL, OrderType.MARKET, 10, 123.0);
            assertFalse(r.resting());
            assertEquals("NEW 9 MSFT SELL MARKET 10 0.00", fake.received.get(0));
        }
    }

    @Test
    void rejectsAreTurnedIntoReports() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> List.of("REJECT 3 duplicate_order_id"));
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            ExecutionReport r = c.submit(3, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0);
            assertFalse(r.accepted());
            assertEquals("duplicate order id", r.reason());
        }
    }

    @Test
    void cancelAndBook() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> {
            if (cmd.startsWith("CANCEL 1 ")) return List.of("CANCELED 1 25");
            if (cmd.startsWith("CANCEL")) return List.of("NOTFOUND 2");
            return List.of("LEVEL BID 190.00 100 2", "LEVEL BID 189.90 50 1", "LEVEL ASK 190.10 70 1");
        });
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            assertEquals(25L, c.cancel(1, "AAPL"));
            assertEquals(-1L, c.cancel(2, "AAPL"));
            BookSnapshot b = c.book("AAPL", 5);
            assertEquals(2, b.bids().size());
            assertEquals(new BookSnapshot.Level(190.00, 100, 2), b.bids().get(0));
            assertEquals(1, b.asks().size());
        }
    }

    @Test
    void connectionDropDuringOrderIsNotRetried() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> null);  // hangs up on every command
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            assertThrows(ExchangeException.class, () -> c.submit(1, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0));
            // the order must have been sent exactly once, resending could double-trade
            assertEquals(1, fake.received.size());
        }
    }

    @Test
    void bookIsRetriedAfterConnectionDrop() throws Exception {
        final int[] calls = {0};
        try (FakeServer fake = new FakeServer(cmd -> {
            calls[0]++;
            return calls[0] == 1 ? null : List.of("LEVEL BID 1.00 1 1");
        });
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            BookSnapshot b = c.book("AAPL", 5);
            assertEquals(1, b.bids().size());
            assertEquals(2, fake.received.size());
            assertEquals(2, fake.connections);  // it reconnected
        }
    }

    @Test
    void nextCallAfterADropOpensANewConnection() throws Exception {
        final boolean[] first = {true};
        try (FakeServer fake = new FakeServer(cmd -> {
            if (first[0]) {
                first[0] = false;
                return null;
            }
            return List.of("ACK 2 DONE 0");
        });
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            assertThrows(ExchangeException.class, () -> c.submit(1, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0));
            assertTrue(c.submit(2, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0).accepted());
        }
    }

    @Test
    void unreachableEngineGivesExchangeException() {
        MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", 1, 300);  // nothing listens on port 1
        assertThrows(ExchangeException.class, () -> c.book("AAPL", 5));
        assertFalse(c.ping());
    }

    @Test
    void garbageRepliesAreReportedNotSwallowed() throws Exception {
        try (FakeServer fake = new FakeServer(cmd -> List.of("WAT 1 2 3"));
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            assertThrows(ExchangeException.class, () -> c.submit(1, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0));
        }
    }

    @Test
    void manyOrdersOverOneConnection() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (FakeServer fake = new FakeServer(cmd -> List.of("ACK " + cmd.split(" ")[1] + " RESTING 1"));
             MatchingEngineClient c = new MatchingEngineClient("127.0.0.1", fake.port())) {
            for (long i = 1; i <= 200; i++) {
                ids.add(c.submit(i, "AAPL", Side.BUY, OrderType.LIMIT, 1, 1.0).orderId());
            }
            assertEquals(200, ids.size());
            assertEquals(1, fake.connections);
        }
    }
}
