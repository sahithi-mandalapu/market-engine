package com.marketengine.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.marketengine.core.Bar;

@Repository
public class BarRepository {

    // upsert: writing the same bar twice (Kafka redelivery after a crash) just overwrites it, so the table never gets duplicates
    private static final String UPSERT = """
            INSERT INTO bars (symbol, start_ms, open_px, high_px, low_px, close_px, volume, ticks)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, start_ms) DO UPDATE SET
              open_px = EXCLUDED.open_px, high_px = EXCLUDED.high_px, low_px = EXCLUDED.low_px,
              close_px = EXCLUDED.close_px, volume = EXCLUDED.volume, ticks = EXCLUDED.ticks
            """;

    private final JdbcTemplate jdbc;

    public BarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(Bar b) {
        jdbc.update(UPSERT, b.symbol(), b.start(), b.open(), b.high(), b.low(), b.close(), b.volume(), b.ticks());
    }

    /** newest `limit` bars, returned oldest first */
    public List<Bar> recent(String symbol, int limit) {
        List<Bar> rows = new ArrayList<>(jdbc.query(
                "SELECT symbol, start_ms, open_px, high_px, low_px, close_px, volume, ticks FROM bars "
                        + "WHERE symbol = ? ORDER BY start_ms DESC LIMIT ?",
                (rs, i) -> new Bar(rs.getString("symbol"), rs.getLong("start_ms"), rs.getDouble("open_px"),
                        rs.getDouble("high_px"), rs.getDouble("low_px"), rs.getDouble("close_px"),
                        rs.getLong("volume"), rs.getInt("ticks")),
                symbol, limit));
        Collections.reverse(rows);
        return rows;
    }
}
