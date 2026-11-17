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
 * @param cpcBaseUrl   NOAA Climate Prediction Center: the ENSO index files ({@code {base}/data/indices/RONI.ascii.txt})
 *                     and the ENSO probabilities ({@code {base}/products/analysis_monitoring/enso/roni/probabilities/})
 * @param jamstecBaseUrl JAMSTEC SINTEX-F climate index files ({@code {base}/SINTEX_DMI.csv}: the Indian Ocean Dipole)
 * @param longPaddockBaseUrl Queensland Government (Long Paddock) SOI files ({@code {base}/MonthlySOI1887-1989Base.txt})
 * @param chirpsBaseUrl CHIRPS v3.0 monthly global cloud-optimized GeoTIFFs ({@code {base}/chirps-v3.0.2026.10.cog})
 * @param overlapDays  an incremental fetch starts this many days before the latest stored observation, so that recent
 *                     revisions are picked up
 * @param jobHistory   finished jobs kept in memory
 * @param outlookCache the Macro page's outlook kept in Redis
 */
@ConfigurationProperties("neracalab.macro")
public record MacroProperties(
        @DefaultValue("https://fred.stlouisfed.org") String fredBaseUrl,
        @DefaultValue("https://ts-api.cnbc.com/harmony/app") String cnbcBaseUrl,
        @DefaultValue("https://publicreporting.cftc.gov/resource/6dca-aqww.json") String cftcBaseUrl,
        @DefaultValue("https://www.bi.go.id/SEKI/tabel") String biSekiBaseUrl,
        @DefaultValue("https://www.cpc.ncep.noaa.gov") String cpcBaseUrl,
        @DefaultValue("https://www.jamstec.go.jp/virtualearth/data/SINTEX") String jamstecBaseUrl,
        @DefaultValue("https://data.longpaddock.qld.gov.au/SeasonalClimateOutlook/SouthernOscillationIndex/SOIDataFiles")
        String longPaddockBaseUrl,
        @DefaultValue("https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs") String chirpsBaseUrl,
        @DefaultValue("45") int overlapDays,
        @DefaultValue("300") int jobHistory,
        @DefaultValue Schedule schedule,
        @DefaultValue OutlookCache outlookCache) {

    public MacroProperties {
        if (overlapDays < 0) {
            throw new IllegalArgumentException("neracalab.macro.overlap-days must not be negative");
        }
        if (outlookCache.ttl().isNegative() || outlookCache.ttl().isZero()) {
            throw new IllegalArgumentException("neracalab.macro.outlook-cache.ttl must be positive");
        }
    }

    /**
     * The Macro page's outlook ({@code GET /api/v1/macro/outlook}) kept in Redis, by country, index, range and day. A
     * write of the data it reads (index, macro series, commodities, currency pairs) and every start clear it; the ttl
     * bounds a change made outside the application (e.g. SQL by hand).
     *
     * @param enabled false: every request computes the outlook
     */
    public record OutlookCache(@DefaultValue("true") boolean enabled,
                               @DefaultValue("1h") java.time.Duration ttl) {
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
