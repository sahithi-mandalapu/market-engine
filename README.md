# Market Engine

A real-time market data + paper trading project I built to learn how the pieces of a trading
system fit together: a C++ order matching engine, a Java/Spring Boot backend that turns a
stream of ticks into bars, indicators and anomaly alerts, and a React dashboard that updates
live over WebSockets.

It uses **replayed or synthetic data only**. There is no live market feed and no real money.

```
 ticks (CSV replay / synthetic generator)
        |
        v
  Kafka topic "ticks"  (keyed by symbol -> 1 symbol = 1 partition)
        |
        v
  Spring Boot consumers (4 threads)
   - dedupe + out-of-order handling, 1 min bars
   - SMA / EMA / RSI / MACD / Bollinger
   - anomaly detection (z-scores)
        |-----> Postgres (bars, fills)      Redis (latest quotes)
        |
        +-----> WebSocket -----> React dashboard
        ^
        |  TCP, line based protocol
  C++ matching engine  <---- orders from the dashboard / liquidity bot
```

## What's in here

| Folder | What |
|---|---|
| `matching-engine/` | C++17 order book (price-time priority), limit + market orders, partial fills, cancels, TCP server, benchmark, GoogleTest tests |
| `backend/` | Java 21 + Spring Boot. Plain-Java core (`core/`, `exchange/`, `trading/`, `ingest/`) and a thin Spring layer (`app/`) |
| `frontend/` | React + TypeScript + Vite dashboard: candlesticks, SMA/Bollinger, RSI, order book, order ticket, portfolio, anomalies, price/volume alerts |
| `analytics/` | Python: IsolationForest compared against my rolling z-score detector (offline) |
| `infra/` | Prometheus + Grafana config |

## Running it

Everything with Docker:

```bash
docker compose up --build
```

- Dashboard: http://localhost:3000 (hit **Start replay**)
- Grafana: http://localhost:3001, Prometheus: http://localhost:9090

Or piece by piece:

```bash
# matching engine
cmake -S matching-engine -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
./build/me_server 9099

# backend (needs kafka, redis, postgres, see docker-compose.yml)
cd backend && mvn spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

## Tests

```bash
# C++ (order book, protocol, engine, concurrency)
cmake -S matching-engine -B build && cmake --build build -j && ctest --test-dir build

# Java. The integration test talks to the real C++ engine if you start one first
./build/me_server 9099 &
cd backend && ME_PORT=9099 mvn test

# frontend
cd frontend && npm test

# python
pip install -r analytics/requirements.txt && pytest analytics
```

## Design notes

- **Order book:** `std::map` of price levels (integer cents, so no float comparisons) each holding a
  `std::list` of orders. A hash map from order id to list iterator makes cancel O(1) after lookup.
  Matching is price-time priority. Market orders are IOC, so leftovers are dropped.
- **Concurrency:** one mutex per symbol, so different symbols don't block each other. I ran the
  concurrency test under ThreadSanitizer.
- **Protocol:** simple text lines over TCP. NEW and CANCEL are never retried after a dropped
  connection, because I can't tell whether the engine already processed them (at-most-once).
- **Bars:** ticks are deduplicated by sequence number, bars stay open for a lateness window
  (15s default) and ticks that arrive after that are dropped and counted.
- **Delivery:** Kafka is at-least-once, so the Postgres writes are idempotent upserts.
- **Anomalies:** z-scores computed against history *before* the new value is added, so a spike
  can't hide itself by inflating its own mean.
- **WebSocket:** slow clients are wrapped in `ConcurrentWebSocketSessionDecorator` so one stuck
  browser can't stall everyone else.

## Numbers I measured

On a small 2-core sandbox, so treat these as rough:

- Order book alone (in-process, no network): about 3.2 to 4.0M operations/s, p99 under 1 microsecond.
- Java analytics pipeline (ticks to bars + indicators + anomaly checks, single thread, no Kafka): about 2.7M ticks/s.

These are **not** end-to-end numbers. The backend records a `market.tick.e2e.latency` timer
(Kafka consume to WebSocket publish) but I have not measured it yet, so I'm not quoting any
latency or throughput for the full system. Run it yourself before putting a number anywhere.

## What I actually verified

- C++ engine: unit tests, ThreadSanitizer run, socket test, benchmark.
- Java core: unit tests plus an integration test against the real C++ engine.
- Frontend: TypeScript build and vitest.
- Python: pytest.

## What I did NOT verify

- The Spring Boot wiring (Kafka consumers, Redis, Postgres, WebSocket config). It was only
  compile-checked, not started. Run `mvn test` and `docker compose up` and expect to fix a few things.
- `docker-compose.yml` and the Dockerfiles have never been run.
- The GitHub Actions workflow has not run yet.

## Limitations / not done

- No Kubernetes manifests (compose only).
- Not using Kafka Streams, plain consumers.
- No live data feed (Alpaca etc.), only replay and synthetic.
- State is in memory: restarting the backend loses the portfolio and open orders.
- If Postgres is down, bars being written at that moment are lost.
- Long-only portfolio, no shorting or margin.
- The IsolationForest comparison is offline only and not wired into the backend.
