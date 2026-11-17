package com.marketengine.ingest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.marketengine.core.Tick;

/** CSV format: ts,symbol,price,size,seq   (ts in epoch millis). A header line is optional. */
public final class CsvTicks {
    private CsvTicks() {}

    public static List<Tick> read(Reader reader) throws IOException {
        List<Tick> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (lineNo == 1 && line.toLowerCase(Locale.ROOT).startsWith("ts")) continue;  // header
                String[] p = line.split(",");
                if (p.length != 5) throw new IOException("line " + lineNo + ": expected 5 columns but got " + p.length);
                try {
                    out.add(new Tick(p[1].trim().toUpperCase(Locale.ROOT), Long.parseLong(p[0].trim()),
                            Double.parseDouble(p[2].trim()), Long.parseLong(p[3].trim()), Long.parseLong(p[4].trim())));
                } catch (IllegalArgumentException e) {
                    throw new IOException("line " + lineNo + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    public static void write(List<Tick> ticks, Writer writer) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(writer)) {
            bw.write("ts,symbol,price,size,seq\n");
            for (Tick t : ticks) {
                bw.write(String.format(Locale.ROOT, "%d,%s,%.2f,%d,%d%n", t.ts(), t.symbol(), t.price(), t.size(), t.seq()));
            }
        }
    }
}
