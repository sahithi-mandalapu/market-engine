package com.marketengine.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketengine.core.Bar;
import com.marketengine.core.MarketPipeline;
import com.marketengine.core.MarketPipeline.BarEvent;
import com.marketengine.core.OrderType;
import com.marketengine.core.Side;
import com.marketengine.exchange.BookSnapshot;
import com.marketengine.exchange.ExchangeException;
import com.marketengine.exchange.MatchingEngineClient;
import com.marketengine.trading.TradingDesk;

/** All the REST endpoints in one file, they're each just a few lines. */
public final class Controllers {
    private Controllers() {}

    // ------------------------------------------------------------------ market data

    @RestController
    @RequestMapping("/api")
    public static class MarketController {
        private final MarketPipeline pipeline;
        private final PipelineService pipelineService;
        private final BarRepository barRepo;
        private final ReplayService replay;
        private final WsHub hub;

        public MarketController(MarketPipeline pipeline, PipelineService pipelineService, BarRepository barRepo,
                                ReplayService replay, WsHub hub) {
            this.pipeline = pipeline;
            this.pipelineService = pipelineService;
            this.barRepo = barRepo;
            this.replay = replay;
            this.hub = hub;
        }

        @GetMapping("/symbols")
        public List<Map<String, Object>> symbols() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String sym : pipeline.symbols()) {
                var price = pipeline.lastPrice(sym);
                if (price.isEmpty()) continue;
                List<BarEvent> hist = pipeline.recent(sym, 500);
                double ref = hist.isEmpty() ? price.getAsDouble() : hist.get(0).bar().open();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", sym);
                m.put("price", price.getAsDouble());
                m.put("changePct", (price.getAsDouble() / ref - 1.0) * 100.0);
                out.add(m);
            }
            return out;
        }

        @GetMapping("/bars")
        public List<Map<String, Object>> bars(@RequestParam String symbol, @RequestParam(defaultValue = "100") int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BarEvent ev : pipeline.recent(symbol.toUpperCase(Locale.ROOT), Math.min(Math.max(limit, 1), 500))) {
                out.add(Dto.barEvent(ev));
            }
            return out;
        }

        /** bars saved in Postgres, survives restarts (the in-memory /bars doesn't) */
        @GetMapping("/history")
        public List<Map<String, Object>> history(@RequestParam String symbol, @RequestParam(defaultValue = "200") int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Bar b : barRepo.recent(symbol.toUpperCase(Locale.ROOT), Math.min(Math.max(limit, 1), 5000))) {
                out.add(Dto.bar(b));
            }
            return out;
        }

        @GetMapping("/anomalies")
        public List<JsonNode> anomalies(@RequestParam(defaultValue = "50") int limit) {
            return pipelineService.recentAnomalies(Math.min(Math.max(limit, 1), 200));
        }

        @GetMapping("/stats")
        public Map<String, Object> stats() {
            var s = pipeline.stats();
            Map<String, Object> pipe = new LinkedHashMap<>();
            pipe.put("ticksAccepted", s.ticks());
            pipe.put("barsClosed", s.bars());
            pipe.put("duplicatesDropped", s.duplicates());
            pipe.put("lateTicksDropped", s.lateDropped());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pipeline", pipe);
            m.put("latency", pipelineService.latencySummary());
            m.put("websocketClients", hub.sessionCount());
            m.put("replay", replay.status());
            return m;
        }
    }

    // ----------------------------------------------------------------------- replay

    public record StartReplay(String source, Double speed, Integer ticks, Long seed) {
    }

    @RestController
    @RequestMapping("/api/replay")
    public static class ReplayController {
        private final ReplayService replay;

        public ReplayController(ReplayService replay) {
            this.replay = replay;
        }

        @PostMapping("/start")
        public Map<String, Object> start(@RequestBody(required = false) StartReplay req) {
            String source = req == null || req.source() == null ? "sample" : req.source();
            double speed = req == null || req.speed() == null ? 60.0 : req.speed();
            int ticks = req == null || req.ticks() == null ? 20_000 : req.ticks();
            long seed = req == null || req.seed() == null ? 42L : req.seed();
            return replay.start(source, speed, ticks, seed);
        }

        @PostMapping("/stop")
        public Map<String, Object> stop() {
            return replay.stop();
        }

        @GetMapping
        public Map<String, Object> status() {
            return replay.status();
        }
    }

    // ---------------------------------------------------------------------- trading

    public record OrderRequest(String symbol, String side, String type, long qty, double price) {
    }

    @RestController
    @RequestMapping("/api")
    public static class TradingController {
        private final TradingDesk desk;
        private final MarketPipeline pipeline;
        private final MatchingEngineClient engine;
        private final PortfolioService portfolio;

        public TradingController(TradingDesk desk, MarketPipeline pipeline, MatchingEngineClient engine,
                                 PortfolioService portfolio) {
            this.desk = desk;
            this.pipeline = pipeline;
            this.engine = engine;
            this.portfolio = portfolio;
        }

        @PostMapping("/orders")
        public TradingDesk.OrderResponse place(@RequestBody OrderRequest req) {
            if (req.symbol() == null || req.symbol().isBlank()) throw new IllegalArgumentException("symbol is required");
            String symbol = req.symbol().trim().toUpperCase(Locale.ROOT);
            Side side = Side.valueOf(req.side().trim().toUpperCase(Locale.ROOT));
            OrderType type = OrderType.valueOf(req.type().trim().toUpperCase(Locale.ROOT));
            double mark = pipeline.lastPrice(symbol).orElse(0);
            return desk.place(symbol, side, type, req.qty(), req.price(), mark);
        }

        @DeleteMapping("/orders/{id}")
        public ResponseEntity<Map<String, Object>> cancel(@PathVariable long id) {
            long remaining = desk.cancel(id);
            if (remaining < 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no open order with id " + id));
            }
            return ResponseEntity.ok(Map.of("cancelled", true, "remaining", remaining));
        }

        @GetMapping("/orders")
        public List<TradingDesk.OpenOrder> open() {
            return desk.openOrders();
        }

        @GetMapping("/book")
        public BookSnapshot book(@RequestParam String symbol, @RequestParam(defaultValue = "8") int depth) {
            return engine.book(symbol.toUpperCase(Locale.ROOT), Math.min(Math.max(depth, 1), 50));
        }

        @GetMapping("/portfolio")
        public Map<String, Object> portfolio() {
            return portfolio.snapshot();
        }
    }

    // ----------------------------------------------------------------------- errors

    @RestControllerAdvice
    public static class ApiErrors {
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
        }

        @ExceptionHandler(ExchangeException.class)
        public ResponseEntity<Map<String, String>> exchangeDown(ExchangeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }
}
