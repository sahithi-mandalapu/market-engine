package com.marketengine.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketengine.core.Anomaly;
import com.marketengine.core.MarketPipeline;
import com.marketengine.core.MarketPipeline.BarEvent;
import com.marketengine.core.Tick;
import com.marketengine.exchange.ExchangeException;
import com.marketengine.trading.LiquidityBot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The Kafka consumer. For every tick: run the analytics pipeline, then fan the results out to
 * the websocket, Redis and Postgres.
 *
 * The topic is keyed by symbol, so a given symbol is always handled by one consumer thread. That is what
 * makes it OK for MarketPipeline to keep per-symbol state in memory.
 */
@Service
public class PipelineService {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final long BOT_BACKOFF_MS = 5_000;
    private static final int MEMORY_ANOMALIES = 200;

    private final MarketPipeline pipeline;
    private final BarRepository bars;
    private final QuoteCache cache;
    private final WsHub hub;
    private final LiquidityBot bot;
    private final ObjectMapper mapper;
    private final long botRefreshMs;

    private final Counter ticksIn;
    private final Counter badTicks;
    private final Counter barsOut;
    private final Counter anomaliesOut;
    private final Counter dbErrors;
    private final Timer latency;

    private final Map<String, Long> lastBotRefresh = new ConcurrentHashMap<>();
    private volatile long botDisabledUntil = 0;
    private final ArrayDeque<String> recentAnomalies = new ArrayDeque<>();

    public PipelineService(MarketPipeline pipeline, BarRepository bars, QuoteCache cache, WsHub hub,
                           LiquidityBot bot, ObjectMapper mapper, MeterRegistry registry,
                           @Value("${market.bot-refresh-ms}") long botRefreshMs) {
        this.pipeline = pipeline;
        this.bars = bars;
        this.cache = cache;
        this.hub = hub;
        this.bot = bot;
        this.mapper = mapper;
        this.botRefreshMs = botRefreshMs;

        this.ticksIn = registry.counter("market.ticks.processed");
        this.badTicks = registry.counter("market.ticks.invalid");
        this.barsOut = registry.counter("market.bars.closed");
        this.anomaliesOut = registry.counter("market.anomalies.detected");
        this.dbErrors = registry.counter("market.db.errors");
        // time from "published to Kafka" to "processed here", in ms. percentiles are readable from /api/stats
        this.latency = Timer.builder("market.tick.e2e.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    @KafkaListener(topics = "${market.topic}", groupId = "${market.consumer-group}",
            concurrency = "${market.consumer-concurrency}")
    public void onMessage(String payload) {
        Tick tick;
        try {
            JsonNode n = mapper.readTree(payload);
            tick = new Tick(n.get("symbol").asText(), n.get("ts").asLong(), n.get("price").asDouble(),
                    n.get("size").asLong(), n.get("seq").asLong(), n.path("ingestMs").asLong(0));
        } catch (Exception e) {
            // a malformed message must not be retried forever, count it and move on
            badTicks.increment();
            log.warn("dropping invalid tick payload: {}", e.getMessage());
            return;
        }
        handle(tick);
    }

    void handle(Tick t) {
        ticksIn.increment();
        if (t.ingestMs() > 0) {
            latency.record(Math.max(0, System.currentTimeMillis() - t.ingestMs()), TimeUnit.MILLISECONDS);
        }

        List<BarEvent> events = pipeline.onTick(t);

        Map<String, Object> tickMsg = new LinkedHashMap<>();
        tickMsg.put("type", "tick");
        tickMsg.put("symbol", t.symbol());
        tickMsg.put("price", t.price());
        tickMsg.put("size", t.size());
        tickMsg.put("ts", t.ts());
        hub.broadcast(tickMsg);

        cache.putQuote(t);
        refreshBot(t);

        for (BarEvent ev : events) {
            barsOut.increment();
            try {
                bars.upsert(ev.bar());
            } catch (RuntimeException e) {
                // postgres being down shouldn't stop the live analytics. the bar is lost from the table though
                dbErrors.increment();
                log.warn("couldn't save bar {} {}: {}", ev.bar().symbol(), ev.bar().start(), e.getMessage());
            }
            hub.broadcast(Dto.barMessage(ev));
            for (Anomaly a : ev.anomalies()) publishAnomaly(a);
        }
    }

    private void publishAnomaly(Anomaly a) {
        anomaliesOut.increment();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "anomaly");
        msg.put("anomalyType", a.type().name());
        msg.put("symbol", a.symbol());
        msg.put("barStart", a.barStart());
        msg.put("score", a.score());
        msg.put("message", a.message());
        hub.broadcast(msg);
        try {
            String json = mapper.writeValueAsString(msg);
            cache.pushAnomaly(json);
            synchronized (recentAnomalies) {
                recentAnomalies.addFirst(json);
                if (recentAnomalies.size() > MEMORY_ANOMALIES) recentAnomalies.removeLast();
            }
        } catch (Exception e) {
            log.warn("couldn't serialize anomaly: {}", e.getMessage());
        }
    }

    private void refreshBot(Tick t) {
        long now = System.currentTimeMillis();
        if (now < botDisabledUntil) return;
        Long last = lastBotRefresh.get(t.symbol());
        if (last != null && now - last < botRefreshMs) return;
        lastBotRefresh.put(t.symbol(), now);
        try {
            bot.refresh(t.symbol(), t.price());
        } catch (ExchangeException e) {
            // engine is down: stop trying for a few seconds so we don't stall the consumer on connect timeouts
            botDisabledUntil = now + BOT_BACKOFF_MS;
            log.warn("matching engine unreachable, pausing liquidity bot for {} s: {}", BOT_BACKOFF_MS / 1000, e.getMessage());
        }
    }

    /** newest first. reads Redis (shared) and falls back to this instance's memory if Redis is down */
    public List<JsonNode> recentAnomalies(int limit) {
        List<String> raw = cache.anomalies(limit).orElseGet(() -> {
            synchronized (recentAnomalies) {
                return new ArrayList<>(recentAnomalies).subList(0, Math.min(limit, recentAnomalies.size()));
            }
        });
        List<JsonNode> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(mapper.readTree(s));
            } catch (Exception ignored) {
                // skip anything unreadable
            }
        }
        return out;
    }

    public void reset() {
        pipeline.reset();
        lastBotRefresh.clear();
        synchronized (recentAnomalies) {
            recentAnomalies.clear();
        }
    }

    /** latency percentiles in ms, empty map until some ticks were measured */
    public Map<String, Object> latencySummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", latency.count());
        if (latency.count() > 0) {
            m.put("meanMs", latency.mean(TimeUnit.MILLISECONDS));
            m.put("maxMs", latency.max(TimeUnit.MILLISECONDS));
            for (var p : latency.takeSnapshot().percentileValues()) {
                m.put("p" + (int) Math.round(p.percentile() * 100) + "Ms", p.value(TimeUnit.MILLISECONDS));
            }
        }
        return m;
    }
}
