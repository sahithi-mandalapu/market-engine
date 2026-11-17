package com.marketengine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** All the indicators for one symbol, fed one close at a time. */
public final class IndicatorSet {

    public record Snapshot(double sma20, double sma50, double ema12, double ema26, double rsi14,
                           MacdValue macd, Bands bollinger) {

        /** NaN isn't valid JSON so warm-up values become null here */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sma20", nn(sma20));
            m.put("sma50", nn(sma50));
            m.put("ema12", nn(ema12));
            m.put("ema26", nn(ema26));
            m.put("rsi14", nn(rsi14));
            m.put("macd", nn(macd.macd()));
            m.put("macdSignal", nn(macd.signal()));
            m.put("macdHist", nn(macd.histogram()));
            m.put("bbMid", nn(bollinger.mid()));
            m.put("bbUpper", nn(bollinger.upper()));
            m.put("bbLower", nn(bollinger.lower()));
            return m;
        }

        private static Double nn(double d) {
            return Double.isNaN(d) ? null : d;
        }
    }

    private final Sma sma20 = new Sma(20);
    private final Sma sma50 = new Sma(50);
    private final Ema ema12 = new Ema(12);
    private final Ema ema26 = new Ema(26);
    private final Rsi rsi14 = new Rsi(14);
    private final Macd macd = new Macd();
    private final Bollinger bb = new Bollinger(20, 2.0);

    public Snapshot update(double close) {
        return new Snapshot(sma20.update(close), sma50.update(close), ema12.update(close), ema26.update(close),
                rsi14.update(close), macd.update(close), bb.update(close));
    }
}
