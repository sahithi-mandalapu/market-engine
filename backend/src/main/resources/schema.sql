CREATE TABLE IF NOT EXISTS bars (
    symbol    VARCHAR(16)      NOT NULL,
    start_ms  BIGINT           NOT NULL,
    open_px   DOUBLE PRECISION NOT NULL,
    high_px   DOUBLE PRECISION NOT NULL,
    low_px    DOUBLE PRECISION NOT NULL,
    close_px  DOUBLE PRECISION NOT NULL,
    volume    BIGINT           NOT NULL,
    ticks     INTEGER          NOT NULL,
    PRIMARY KEY (symbol, start_ms)
);

CREATE TABLE IF NOT EXISTS fills (
    id        BIGSERIAL        PRIMARY KEY,
    ts        BIGINT           NOT NULL,
    order_id  BIGINT           NOT NULL,
    symbol    VARCHAR(16)      NOT NULL,
    side      VARCHAR(4)       NOT NULL,
    price     DOUBLE PRECISION NOT NULL,
    qty       BIGINT           NOT NULL
);

CREATE INDEX IF NOT EXISTS fills_symbol_ts ON fills (symbol, ts);
