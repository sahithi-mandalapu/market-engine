package com.marketengine.exchange;

import com.marketengine.core.OrderType;
import com.marketengine.core.Side;

/** What the trading code needs from an exchange. The real one is the C++ engine over TCP, tests use a fake. */
public interface ExchangeGateway {

    ExecutionReport submit(long orderId, String symbol, Side side, OrderType type, long qty, double price);

    /** @return remaining qty that was cancelled, or -1 if the order wasn't there (already filled or never existed) */
    long cancel(long orderId, String symbol);

    BookSnapshot book(String symbol, int depth);
}
