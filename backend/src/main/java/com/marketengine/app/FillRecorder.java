package com.marketengine.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.marketengine.trading.TradingDesk;
import com.marketengine.trading.TradingDesk.UserFill;

/** Every fill that hits the user's portfolio gets saved to Postgres and pushed to the browser. */
@Component
public class FillRecorder {
    private static final Logger log = LoggerFactory.getLogger(FillRecorder.class);

    private final JdbcTemplate jdbc;
    private final WsHub hub;

    public FillRecorder(TradingDesk desk, JdbcTemplate jdbc, WsHub hub) {
        this.jdbc = jdbc;
        this.hub = hub;
        desk.setFillListener(this::onFill);
    }

    private void onFill(UserFill f) {
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO fills (ts, order_id, symbol, side, price, qty) VALUES (?, ?, ?, ?, ?, ?)",
                    now, f.orderId(), f.symbol(), f.side().name(), f.price(), f.qty());
        } catch (RuntimeException e) {
            log.warn("couldn't save fill for order {}: {}", f.orderId(), e.getMessage());
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "fill");
        msg.put("ts", now);
        msg.put("orderId", f.orderId());
        msg.put("symbol", f.symbol());
        msg.put("side", f.side().name());
        msg.put("price", f.price());
        msg.put("qty", f.qty());
        hub.broadcast(msg);
    }
}
