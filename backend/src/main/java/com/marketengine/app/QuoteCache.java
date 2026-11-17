package com.marketengine.app;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.marketengine.core.Tick;

/**
 * Redis holds the "hot" state other services (or a second API instance) could read:
 *   quote:<SYMBOL>   hash with the latest price / size / timestamp
 *   anomalies        capped list (newest first) of anomaly events as JSON
 * Every call swallows Redis errors, the analytics must not stop because the cache is down.
 */
@Component
public class QuoteCache {
    private static final Logger log = LoggerFactory.getLogger(QuoteCache.class);
    private static final long MIN_WRITE_GAP_MS = 200;   // don't hammer redis once per tick at high replay speeds
    private static final int MAX_ANOMALIES = 200;

    private final StringRedisTemplate redis;
    private final Map<String, Long> lastWrite = new ConcurrentHashMap<>();
    private volatile long lastWarn = 0;

    public QuoteCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void putQuote(Tick t) {
        long now = System.currentTimeMillis();
        Long prev = lastWrite.get(t.symbol());
        if (prev != null && now - prev < MIN_WRITE_GAP_MS) return;
        lastWrite.put(t.symbol(), now);
        try {
            redis.opsForHash().putAll("quote:" + t.symbol(), Map.of(
                    "price", String.valueOf(t.price()),
                    "size", String.valueOf(t.size()),
                    "ts", String.valueOf(t.ts())));
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    public void pushAnomaly(String json) {
        try {
            redis.opsForList().leftPush("anomalies", json);
            redis.opsForList().trim("anomalies", 0, MAX_ANOMALIES - 1);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /** empty if redis is unavailable, so the caller can fall back */
    public Optional<List<String>> anomalies(int limit) {
        try {
            List<String> raw = redis.opsForList().range("anomalies", 0, Math.max(0, limit - 1));
            return Optional.ofNullable(raw);
        } catch (RuntimeException e) {
            warn(e);
            return Optional.empty();
        }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 10_000) {   // at most one log line per 10 s, otherwise an outage floods the logs
            lastWarn = now;
            log.warn("redis unavailable, continuing without cache: {}", e.getMessage());
        }
    }
}
