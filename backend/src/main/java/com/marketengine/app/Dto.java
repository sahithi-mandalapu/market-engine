package com.marketengine.app;

import java.util.LinkedHashMap;
import java.util.Map;

import com.marketengine.core.Bar;
import com.marketengine.core.MarketPipeline.BarEvent;

/** JSON shapes for the REST API and the WebSocket. Maps instead of records so NaN becomes null. */
final class Dto {
    private Dto() {}

    static Map<String, Object> bar(Bar b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", b.symbol());
        m.put("start", b.start());
        m.put("open", b.open());
        m.put("high", b.high());
        m.put("low", b.low());
        m.put("close", b.close());
        m.put("volume", b.volume());
        m.put("ticks", b.ticks());
        return m;
    }

    static Map<String, Object> barEvent(BarEvent ev) {
        Map<String, Object> m = bar(ev.bar());
        m.put("indicators", ev.indicators().toJson());
        m.put("anomalies", ev.anomalies());
        return m;
    }

    static Map<String, Object> barMessage(BarEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "bar");
        m.putAll(barEvent(ev));
        return m;
    }
}
