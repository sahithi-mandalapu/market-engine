package com.marketengine.app;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.marketengine.core.Tick;
import com.marketengine.ingest.CsvTicks;
import com.marketengine.ingest.ReplayPlayer;
import com.marketengine.ingest.SyntheticTickGenerator;

/**
 * Plays a market session into Kafka. Two sources:
 *   sample     the bundled CSV (4 symbols, a compressed trading day)
 *   synthetic  freshly generated random walk, with duplicate and out-of-order ticks mixed in
 * speed 1 = real time, 60 = a minute of market time per second, 0 = as fast as possible.
 */
@Service
public class ReplayService {
    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final TickPublisher publisher;
    private final PipelineService pipelineService;

    private ReplayPlayer player;
    private Thread worker;
    private String source = "";
    private double speed;
    private int total;
    private volatile String state = "idle";

    public ReplayService(TickPublisher publisher, PipelineService pipelineService) {
        this.publisher = publisher;
        this.pipelineService = pipelineService;
    }

    public synchronized Map<String, Object> start(String source, double speed, int ticks, long seed) {
        if (worker != null && worker.isAlive()) throw new IllegalStateException("a replay is already running, stop it first");

        List<Tick> data = load(source, ticks, seed);
        pipelineService.reset();   // otherwise the dedupe memory would reject a second run of the same file

        this.source = source;
        this.speed = speed;
        this.total = data.size();
        this.player = new ReplayPlayer();
        ReplayPlayer p = this.player;
        this.state = "running";
        worker = new Thread(() -> {
            try {
                long sent = p.play(data, speed, publisher::publish);
                state = sent < data.size() ? "stopped" : "finished";   // fewer than all = somebody called stop()
            } catch (RuntimeException e) {
                log.error("replay failed: {}", e.getMessage(), e);
                state = "failed";
            }
        }, "replay");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    public synchronized Map<String, Object> stop() {
        if (player != null) player.stop();   // the worker thread sets the final state when it notices
        return status();
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("source", source);
        m.put("speed", speed);
        m.put("total", total);
        m.put("played", player == null ? 0 : player.played());
        return m;
    }

    private List<Tick> load(String source, int ticks, long seed) {
        switch (source) {
            case "sample" -> {
                try (var in = new ClassPathResource("sample_session.csv").getInputStream()) {
                    return CsvTicks.read(new InputStreamReader(in, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IllegalStateException("couldn't read sample_session.csv: " + e.getMessage(), e);
                }
            }
            case "synthetic" -> {
                if (ticks < 1 || ticks > 2_000_000) throw new IllegalArgumentException("ticks must be between 1 and 2,000,000");
                Map<String, Double> start = new LinkedHashMap<>();
                start.put("AAPL", 190.0);
                start.put("MSFT", 410.0);
                start.put("NVDA", 125.0);
                start.put("TSLA", 245.0);
                start.put("AMZN", 185.0);
                start.put("GOOG", 175.0);
                return SyntheticTickGenerator.messy(start, seed, System.currentTimeMillis(), 8_000L).generate(ticks);
            }
            default -> throw new IllegalArgumentException("unknown source '" + source + "' (use sample or synthetic)");
        }
    }
}
