package com.neracalab.backend.technical;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.neracalab.backend.technical.engine.Candle;

/** Test data of the technical analysis: synthetic bars, the IDX fixtures and the default properties. */
public final class TechnicalTestData {

    private TechnicalTestData() {
    }

    /** The properties with every default of {@link TechnicalProperties} (as bound without configuration). */
    public static TechnicalProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of("neracalab.technical.min-history-days", "250")))
                .bind("neracalab.technical", TechnicalProperties.class).get();
    }

    /**
     * A deterministic series of {@code n} weekday bars starting 2020-01-01: a trend with two sine waves, highs and lows
     * around the close, volume rising on up days.
     */
    public static List<Candle> synthetic(int n) {
        List<Candle> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2020, 1, 1);
        double prev = 1000;
        for (int i = 0; i < n; i++) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.plusDays(1);
            }
            double close = 1000 + i * 1.5 + 80 * Math.sin(i / 9.0) + 35 * Math.sin(i / 2.3);
            double open = prev + (close - prev) * 0.3;
            double high = Math.max(open, close) + 6 + 4 * Math.abs(Math.sin(i * 1.7));
            double low = Math.min(open, close) - 6 - 4 * Math.abs(Math.cos(i * 1.3));
            double volume = 1_000_000 + 400_000 * Math.abs(Math.sin(i / 5.0)) + (close > prev ? 250_000 : 0);
            bars.add(Candle.daily(d, open, high, low, close, close, Math.round(volume)));
            prev = close;
            d = d.plusDays(1);
        }
        return bars;
    }

    /** About 300 trading days of a real IDX listing ({@code src/test/resources/technical/<TICKER>.csv}), oldest first. */
    public static List<Candle> fixture(String name) {
        try (InputStream in = TechnicalTestData.class.getResourceAsStream("/technical/" + name + ".csv")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture technical/" + name + ".csv");
            }
            List<Candle> bars = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("date")) {
                    continue;
                }
                String[] f = line.split(",");
                bars.add(Candle.daily(LocalDate.parse(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6])));
            }
            return bars;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
