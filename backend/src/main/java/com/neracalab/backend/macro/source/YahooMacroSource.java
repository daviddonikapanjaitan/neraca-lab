package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Component;

import com.neracalab.backend.macro.MacroSeries;
import com.neracalab.backend.technical.YahooChartClient;

/**
 * Yahoo Finance daily closes (the US Dollar Index {@code DX-Y.NYB}), through the chart client the price ingestion
 * uses, so the requests are paced with it. The bar of the current New York day is unfinished and left out.
 */
@Component
public class YahooMacroSource implements MacroSource {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final YahooChartClient yahoo;

    public YahooMacroSource(YahooChartClient yahoo) {
        this.yahoo = yahoo;
    }

    @Override
    public String name() {
        return "yahoo";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        LocalDate newYorkToday = LocalDate.now(NEW_YORK);
        return yahoo.dailyBars(series.sourceRef(), NEW_YORK, from, today).stream()
                .filter(c -> c.date().isBefore(newYorkToday))
                .map(c -> new Observation(c.date(), BigDecimal.valueOf(c.close())))
                .toList();
    }
}
