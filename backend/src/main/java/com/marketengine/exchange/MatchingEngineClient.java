package com.marketengine.exchange;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.marketengine.core.OrderType;
import com.marketengine.core.Side;

/**
 * Talks to the C++ matching engine over its line protocol (see matching-engine/include/me/protocol.hpp).
 * One shared connection, calls are serialized with synchronized.
 *
 * Reconnect policy: read-only commands (BOOK, PING) are retried once on a broken connection.
 * NEW and CANCEL are NOT retried, because if the engine already processed the command before
 * the connection dropped, sending it again could trade twice. Instead the caller gets an
 * ExchangeException and the next call opens a fresh connection.
 */
public final class MatchingEngineClient implements ExchangeGateway, AutoCloseable {

    private final String host;
    private final int port;
    private final int timeoutMs;

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    public MatchingEngineClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public MatchingEngineClient(String host, int port) {
        this(host, port, 3000);
    }

    // ------------------------------------------------------------------ wire

    private void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII));
    }

    private void dropConnection() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
        socket = null;
        in = null;
        out = null;
    }

    private List<String> roundTrip(String cmd) throws IOException {
        if (socket == null) connect();
        out.write(cmd);
        out.write('\n');
        out.flush();
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = in.readLine();
            if (line == null) throw new IOException("engine closed the connection");
            if (line.equals("OK")) return lines;
            lines.add(line);
        }
    }

    private synchronized List<String> send(String cmd, boolean safeToRetry) {
        try {
            return roundTrip(cmd);
        } catch (IOException first) {
            dropConnection();
            if (!safeToRetry) {
                throw new ExchangeException("lost connection to matching engine, order state unknown: " + first.getMessage(), first);
            }
            try {
                return roundTrip(cmd);
            } catch (IOException second) {
                dropConnection();
                throw new ExchangeException("matching engine unreachable at " + host + ":" + port, second);
            }
        }
    }

    public boolean ping() {
        try {
            return send("PING", true).contains("PONG");
        } catch (ExchangeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------- api

    @Override
    public ExecutionReport submit(long orderId, String symbol, Side side, OrderType type, long qty, double price) {
        String cmd = String.format(Locale.ROOT, "NEW %d %s %s %s %d %.2f", orderId, symbol, side, type, qty,
                type == OrderType.MARKET ? 0.0 : price);
        List<String> lines = send(cmd, false);

        List<Fill> fills = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(" ");
            switch (p[0]) {
                case "TRADE" -> {
                    if (p.length != 6) throw new ExchangeException("bad TRADE line: " + line);
                    fills.add(new Fill(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], Double.parseDouble(p[4]),
                            Long.parseLong(p[5])));
                }
                case "ACK" -> {
                    if (p.length != 4) throw new ExchangeException("bad ACK line: " + line);
                    return new ExecutionReport(orderId, true, "", fills, Long.parseLong(p[3]), p[2].equals("RESTING"));
                }
                case "REJECT" -> {
                    String why = p.length > 2 ? p[2].replace('_', ' ') : "rejected";
                    return ExecutionReport.rejected(orderId, why);
                }
                case "ERR" -> {
                    return ExecutionReport.rejected(orderId, p.length > 1 ? p[1].replace('_', ' ') : "error");
                }
                default -> throw new ExchangeException("unexpected reply: " + line);
            }
        }
        throw new ExchangeException("engine reply had no ACK or REJECT");
    }

    @Override
    public long cancel(long orderId, String symbol) {
        List<String> lines = send("CANCEL " + orderId + " " + symbol, false);
        if (lines.isEmpty()) throw new ExchangeException("empty reply to CANCEL");
        String[] p = lines.get(0).split(" ");
        if (p[0].equals("CANCELED") && p.length == 3) return Long.parseLong(p[2]);
        if (p[0].equals("NOTFOUND")) return -1;
        throw new ExchangeException("unexpected reply to CANCEL: " + lines.get(0));
    }

    @Override
    public BookSnapshot book(String symbol, int depth) {
        List<String> lines = send("BOOK " + symbol + " " + depth, true);
        List<BookSnapshot.Level> bids = new ArrayList<>();
        List<BookSnapshot.Level> asks = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(" ");
            if (p[0].equals("LEVEL") && p.length == 5) {
                var level = new BookSnapshot.Level(Double.parseDouble(p[2]), Long.parseLong(p[3]), Integer.parseInt(p[4]));
                (p[1].equals("BID") ? bids : asks).add(level);
            } else if (p[0].equals("ERR")) {
                throw new ExchangeException("BOOK failed: " + line);
            }
        }
        return new BookSnapshot(bids, asks);
    }

    @Override
    public synchronized void close() {
        dropConnection();
    }
}
