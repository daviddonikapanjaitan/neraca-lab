package com.neracalab.backend.macro;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Macro data ingestion ({@code neracalab.macro.*}): the sources of {@code macro_series_def} and the optional evening run.
 *
 * @param fredBaseUrl  FRED graph CSV download (no API key): {@code {base}/graph/fredgraph.csv?id=DGS10&cosd=2026-01-01}
 * @param cnbcBaseUrl  CNBC chart bars (the SBN 10Y yield)
 * @param cftcBaseUrl  CFTC Public Reporting (Socrata) dataset of the legacy futures-only Commitments of Traders
 * @param biSekiBaseUrl Bank Indonesia SEKI tables ({@code {base}/TABEL5_9.xls})
 * @param overlapDays  an incremental fetch starts this many days before the latest stored observation, so that recent
 *                     revisions are picked up
 * @param jobHistory   finished jobs kept in memory
 */
@ConfigurationProperties("neracalab.macro")
public record MacroProperties(
        @DefaultValue("https://fred.stlouisfed.org") String fredBaseUrl,
        @DefaultValue("https://ts-api.cnbc.com/harmony/app") String cnbcBaseUrl,
        @DefaultValue("https://publicreporting.cftc.gov/resource/6dca-aqww.json") String cftcBaseUrl,
        @DefaultValue("https://www.bi.go.id/SEKI/tabel") String biSekiBaseUrl,
        @DefaultValue("45") int overlapDays,
        @DefaultValue("300") int jobHistory,
        @DefaultValue Schedule schedule) {

    public MacroProperties {
        if (overlapDays < 0) {
            throw new IllegalArgumentException("neracalab.macro.overlap-days must not be negative");
        }
    }

    /**
     * Optional evening run: every series that is behind is queued ("Update all macro data").
     *
     * @param cron Spring cron in {@code zone}; default 20:00 every day
     */
    public record Schedule(@DefaultValue("false") boolean enabled,
                           @DefaultValue("0 0 20 * * *") String cron,
                           @DefaultValue("Asia/Jakarta") String zone) {
    }
}
