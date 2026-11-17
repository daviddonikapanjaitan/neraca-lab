package com.neracalab.backend.macro.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.crawl.CrawlProperties;
import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;
import com.neracalab.backend.macro.source.MacroSource.Observation;

/**
 * The weather sources of Ingestion › Macro on their real layouts (checked live on 2026-11-16): NOAA CPC's ONI / RONI
 * seasons and weekly Nino 3.4, its ENSO probability table (current and archived), JAMSTEC's monthly IOD, Long
 * Paddock's monthly and 30-day SOI, the CHIRPS cloud-optimized GeoTIFF (LZW tiles read over byte ranges) and the SPI.
 * Pure: no network, no database.
 */
class MacroWeatherSourcesTest {

    // ------------------------------------------------------------------ NOAA CPC

    @Test
    void aSeasonIsDatedOnItsLastMonth() {
        List<Observation> roni = NoaaCpcSource.seasons("""
                SEAS   YR  ANOM
                DJF  1950 -1.19
                NDJ  2024 -0.43
                ASO  2026  1.97
                """, "RONI.ascii.txt");
        // DJF 1950 = Dec 1949 - Feb 1950; NDJ 2024 = Nov 2024 - Jan 2025 (CPC labels it with November's year)
        assertThat(roni).extracting(Observation::date).containsExactly(LocalDate.of(1950, 2, 1),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1));
        assertThat(roni.get(2).value()).isEqualByComparingTo("1.97");
        // the classic ONI: the anomaly is the last column, not the total
        List<Observation> oni = NoaaCpcSource.seasons(" SEAS  YR   TOTAL   ANOM\n  ASO 2026  29.23   2.46\n",
                "oni.ascii.txt");
        assertThat(oni.getFirst().value()).isEqualByComparingTo("2.46");
        assertThatThrownBy(() -> NoaaCpcSource.seasons("YEAR ANOM\n2026 1.0\n", "RONI.ascii.txt"))
                .isInstanceOf(SourceException.class).hasMessageContaining("unexpected header");
        assertThatThrownBy(() -> NoaaCpcSource.seasons("SEAS YR ANOM\nXYZ 2026 1.0\n", "RONI.ascii.txt"))
                .isInstanceOf(SourceException.class).hasMessageContaining("unexpected row");
    }

    @Test
    void theWeeklyNino34IsTheSixthNumberOfGluedColumns() {
        List<Observation> weeks = NoaaCpcSource.weeklyNino34("""
                 Weekly SST data starts week centered on 2Sept1981

                                Nino1+2      Nino3        Nino34        Nino4
                 Week          SST SSTA     SST SSTA     SST SSTA     SST SSTA
                 02SEP1981     20.6-0.1     24.8-0.1     26.5-0.2     28.3-0.3
                 11NOV2026     27.1 5.4     29.4 4.3     29.9 3.2     29.9 1.3
                """);
        assertThat(weeks).extracting(Observation::date).containsExactly(LocalDate.of(1981, 9, 2),
                LocalDate.of(2026, 11, 11));
        assertThat(weeks).extracting(o -> o.value().toPlainString()).containsExactly("-0.2", "3.2");
        assertThatThrownBy(() -> NoaaCpcSource.weeklyNino34("Nino34\n 11NOV2026  27.1 5.4  29.4\n"))
                .isInstanceOf(SourceException.class).hasMessageContaining("expected 8 numbers");
    }

    private static String probabilitiesPage(String month, String... rows) {
        StringBuilder body = new StringBuilder();
        for (String row : rows) {
            String[] c = row.split(" ");
            body.append("<tr><th scope=\"row\"><abbr>").append(c[0]).append(" <span class=\"tooltip\">x</span></abbr></th>")
                    .append("<td>").append(c[1]).append("</td><td>").append(c[2]).append("</td><td>").append(c[3])
                    .append("</td></tr>");
        }
        return "<html><body><h2>Issued " + month + "</h2><table id=\"probabilities-table\"><caption>ENSO Probabilities"
                + "</caption><tbody><tr class=\"cell-headings\"><th scope=\"col\">Season</th><th scope=\"col\">La Ni&ntilde;a"
                + "</th><th scope=\"col\">Neutral</th><th scope=\"col\">El Ni&ntilde;o</th></tr>" + body
                + "</tbody></table></body></html>";
    }

    @Test
    void theProbabilityTableIsReadByItsHeadingsAndChecked() {
        NoaaCpcSource.Issue issue = NoaaCpcSource.parseProbabilities(probabilitiesPage("November 2026",
                "OND 0 0 100", "NDJ 0 0 100", "DJF 0 0 100", "JFM 0 0 100", "FMA 0 2 98", "MAM 0 20 80", "AMJ 1 61 38",
                "MJJ 13 75 12", "JJA 33 61 6"));
        assertThat(issue.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(issue.rows()).hasSize(9);
        assertThat(issue.rows().get(3)).containsExactly(0, 100);   // JFM: La Nina 0, El Nino 100
        assertThat(issue.rows().get(6)).containsExactly(1, 38);    // AMJ
        // CPC publishes on the second Thursday
        assertThat(NoaaCpcSource.issueDate(YearMonth.of(2026, 11))).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(NoaaCpcSource.issueDate(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 4, 9));
        // row 0 must be centred on the issue month
        assertThatThrownBy(() -> NoaaCpcSource.parseProbabilities(probabilitiesPage("November 2026", "NDJ 0 0 100")))
                .isInstanceOf(SourceException.class).hasMessageContaining("expected OND");
        assertThatThrownBy(() -> NoaaCpcSource.parseProbabilities("<html><h2>Issued November 2026</h2></html>"))
                .isInstanceOf(SourceException.class).hasMessageContaining("probabilities-table");
    }

    // ------------------------------------------------------------------ BoM, Long Paddock

    @Test
    void theArchivedTableHasItsOwnId() {
        String archived = probabilitiesPage("September 2026", "ASO 0 0 100", "SON 0 0 100")
                .replace("probabilities-table", "enso-archives-table");
        NoaaCpcSource.Issue issue = NoaaCpcSource.parseProbabilities(archived);
        assertThat(issue.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(issue.rows()).hasSize(2);
    }

    @Test
    void theJamstecIodIsTheObservedColumnOfEachMonth() {
        List<Observation> iod = JamstecSource.parse("""
                time,Obs,Mean,F1-Mean
                1982-01-15,-0.006299999999999528,,
                2026-10-15,0.9392999999999994,,
                2026-12-15,,0.1757,0.2
                """, "SINTEX_DMI.csv", "Obs");
        // the forecast months (no observation) are left out; a month labelled on its 15th is dated on its 1st
        assertThat(iod).extracting(Observation::date).containsExactly(LocalDate.of(1982, 1, 1), LocalDate.of(2026, 10, 1));
        assertThat(iod.get(1).value()).isEqualByComparingTo("0.939");
        assertThatThrownBy(() -> JamstecSource.parse("time,Mean\n2026-10-15,0.1\n", "SINTEX_DMI.csv", "Obs"))
                .isInstanceOf(SourceException.class).hasMessageContaining("no column 'Obs'");
        assertThatThrownBy(() -> JamstecSource.parse("<html>moved</html>\nx", "SINTEX_DMI.csv", "Obs"))
                .isInstanceOf(SourceException.class).hasMessageContaining("unexpected header");
    }

    @Test
    void theThirtyDaySoiIsTheMeanOfThirtyConsecutiveDays() {
        StringBuilder text = new StringBuilder("Year Day  Tahiti  Darwin     SOI\n");
        for (int day = 289; day <= 320; day++) {
            if (day != 300) {   // day 300 is missing: the windows over it are left out
                text.append("2026 ").append(day).append(" 1015.00 1011.00 ").append(day - 300).append(".00\n");
            }
        }
        List<Observation> daily = LongPaddockSource.parseDaily(text.toString(), "DailySOI1887-1989Base.txt");
        assertThat(daily.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 16));   // day 289 of 2026
        assertThat(LongPaddockSource.thirtyDay(daily)).isEmpty();
        // with every day there, day 320 (16 November) averages days 291 .. 320
        StringBuilder full = new StringBuilder("Year Day  Tahiti  Darwin     SOI\n");
        for (int day = 289; day <= 320; day++) {
            full.append("2026 ").append(day).append(" 1015.00 1011.00 ").append(day - 300).append(".00\n");
        }
        List<Observation> windows = LongPaddockSource.thirtyDay(LongPaddockSource.parseDaily(full.toString(), "f"));
        assertThat(windows).extracting(Observation::date).containsExactly(LocalDate.of(2026, 11, 14),
                LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 16));
        assertThat(windows.getLast().value()).isEqualByComparingTo("5.50");   // mean of -9 .. 20
        List<Observation> soi = LongPaddockSource.parseMonthly("Year Month  SOI\n2026  9  -20.61\n2026 10  -14.76\n", "f");
        assertThat(soi).extracting(Observation::date).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
        assertThat(soi.get(1).value()).isEqualByComparingTo("-14.76");
        assertThatThrownBy(() -> LongPaddockSource.parseMonthly("Year Day Tahiti\n", "f"))
                .isInstanceOf(SourceException.class).hasMessageContaining("expected Year Month SOI");
    }

    // ------------------------------------------------------------------ LZW and the GeoTIFF

    /** A TIFF LZW encoder (libtiff's: the width grows when the next free code passes the current maximum). */
    static byte[] lzwEncode(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] buffer = {0, 0};   // bits, count
        java.util.function.BiConsumer<Integer, Integer> write = (code, width) -> {
            buffer[0] = (buffer[0] << width) | code;
            buffer[1] += width;
            while (buffer[1] >= 8) {
                out.write((int) (buffer[0] >>> (buffer[1] - 8)) & 0xFF);
                buffer[1] -= 8;
            }
        };
        Map<Long, Integer> table = new HashMap<>();
        int width = 9;
        int next = 258;
        write.accept(256, width);
        int w = in[0] & 0xFF;
        for (int i = 1; i < in.length; i++) {
            int k = in[i] & 0xFF;
            Integer code = table.get(((long) w << 8) | k);
            if (code != null) {
                w = code;
                continue;
            }
            write.accept(w, width);
            table.put(((long) w << 8) | k, next++);
            if (next == 4094) {
                write.accept(256, width);
                table.clear();
                next = 258;
                width = 9;
            } else if (next == 512 || next == 1024 || next == 2048) {
                width++;
            }
            w = k;
        }
        write.accept(w, width);
        write.accept(257, width);
        if (buffer[1] > 0) {
            out.write((int) (buffer[0] << (8 - buffer[1])) & 0xFF);
        }
        return out.toByteArray();
    }

    @Test
    void lzwRoundTripsThroughAFullTable() {
        byte[] data = new byte[300_000];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 97 < 60 ? i % 11 : random.nextInt(256));   // repeats and noise: several clears
        }
        assertThat(TiffLzw.decode(lzwEncode(data), data.length)).isEqualTo(data);
        assertThatThrownBy(() -> TiffLzw.decode(lzwEncode(data), data.length + 1))
                .isInstanceOf(SourceException.class).hasMessageContaining("decode to");
    }

    private static final float SEA = -9999f;

    /**
     * A CHIRPS-like global GeoTIFF (7200 x 2400, 0.05 degree from 180 W, 60 N; 512-pixel LZW float tiles), every tile
     * the same: {@code land} on even rows, the sea (-9999) on odd rows.
     */
    static byte[] chirpsLike(float land) {
        int w = 7200;
        int h = 2400;
        int t = 512;
        int across = (w + t - 1) / t;
        int down = (h + t - 1) / t;
        int tiles = across * down;
        ByteBuffer tile = ByteBuffer.allocate(t * t * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < t; r++) {
            for (int c = 0; c < t; c++) {
                tile.putFloat(r % 2 == 0 ? land : SEA);
            }
        }
        byte[] encoded = lzwEncode(tile.array());
        int entries = 14;
        int ifd = 8;
        int offsetsAt = ifd + 2 + entries * 12 + 4;
        int countsAt = offsetsAt + tiles * 4;
        int scaleAt = countsAt + tiles * 4;
        int tieAt = scaleAt + 24;
        int dataAt = 4096;
        ByteBuffer b = ByteBuffer.allocate(dataAt + encoded.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        b.position(ifd);
        b.putShort((short) entries);
        entry(b, 256, 4, 1, w);
        entry(b, 257, 4, 1, h);
        entry(b, 258, 3, 1, 32);
        entry(b, 259, 3, 1, 5);
        entry(b, 262, 3, 1, 1);
        entry(b, 277, 3, 1, 1);
        entry(b, 284, 3, 1, 1);
        entry(b, 322, 3, 1, t);
        entry(b, 323, 3, 1, t);
        entry(b, 324, 4, tiles, offsetsAt);
        entry(b, 325, 4, tiles, countsAt);
        entry(b, 339, 3, 1, 3);
        entry(b, 33550, 12, 3, scaleAt);
        entry(b, 33922, 12, 6, tieAt);
        b.putInt(0);
        for (int i = 0; i < tiles; i++) {
            b.putInt(offsetsAt + i * 4, dataAt);              // every tile shares the same data
            b.putInt(countsAt + i * 4, encoded.length);
        }
        b.putDouble(scaleAt, 0.05).putDouble(scaleAt + 8, 0.05).putDouble(scaleAt + 16, 0);
        double[] tie = {0, 0, 0, -180, 60, 0};
        for (int i = 0; i < 6; i++) {
            b.putDouble(tieAt + i * 8, tie[i]);
        }
        b.position(dataAt);
        b.put(encoded);
        return b.array();
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            b.putShort((short) value).putShort((short) 0);
        } else {
            b.putInt(value);
        }
    }

    @Test
    void theRegionsAreTheMeanOfTheirLandPixels() {
        CogReader reader = CogReader.of(chirpsLike(123.5f));
        assertThat(reader.width).isEqualTo(7200);
        assertThat(reader.lon(0)).isCloseTo(-179.975, within(1e-9));
        assertThat(reader.lat(0)).isCloseTo(59.975, within(1e-9));
        Map<String, Double> means = ChirpsSource.regionMeans(reader);
        assertThat(means.keySet()).containsExactlyElementsOf(ChirpsSource.REGIONS.keySet());
        // the sea rows are left out, so every region is the land value
        means.values().forEach(v -> assertThat(v).isCloseTo(123.5, within(1e-6)));
    }

    /** A crawler over the synthetic file; a month listed as missing answers 404, one listed as limited 429. */
    private static final class FakeHttp extends PoliteHttpClient {

        final byte[] file;
        final List<String> missing;
        final List<String> limited;
        final AtomicInteger requests = new AtomicInteger();

        FakeHttp(byte[] file, List<String> missing) {
            this(file, missing, List.of());
        }

        FakeHttp(byte[] file, List<String> missing, List<String> limited) {
            super(new CrawlProperties(java.time.Duration.ZERO, "test", java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(1), 20_000_000, Map.of()));
            this.file = file;
            this.missing = missing;
            this.limited = limited;
        }

        @Override
        public Response getRange(URI uri, long first, long last) {
            requests.incrementAndGet();
            if (missing.stream().anyMatch(m -> uri.toString().contains(m))) {
                return new Response(404, new byte[0], "text/html");
            }
            if (limited.stream().anyMatch(m -> uri.toString().contains(m))) {
                return new Response(429, new byte[0], "text/html");
            }
            long end = Math.min(last, file.length - 1);
            return new Response(206, java.util.Arrays.copyOfRange(file, (int) first, (int) end + 1),
                    "application/octet-stream");
        }
    }

    private static MacroProperties properties() {
        return new MacroProperties("f", "c", "t", "s", "cpc", "jamstec", "lp", "https://chirps.test/cogs", 45, 300,
                new MacroProperties.Schedule(false, "0 0 20 * * *", "Asia/Jakarta"),
                new MacroProperties.OutlookCache(true, java.time.Duration.ofHours(1)));
    }

    private static MacroSeries rain(String code, String region) {
        return new MacroSeries(code, code, "ID", "WEATHER_RAIN", "mm", "MONTHLY", "chirps", region, null, 15, 1,
                "DALIO", BigDecimal.ZERO, new BigDecimal("3000"), null, LocalDate.of(1981, 1, 1), null);
    }

    @Test
    void aMonthNotPublishedYetEndsTheFetchAndOneMonthServesEveryRegion() {
        // October 2026 is posted mid-November: on 2026-11-10 its file is not there yet
        FakeHttp http = new FakeHttp(chirpsLike(80f), List.of("2026.10"));
        ChirpsSource source = new ChirpsSource(http, properties());
        List<Observation> kal = source.fetch(rain("ID_RAIN_KAL_PALM", "ID_KAL_PALM"), LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 10));
        assertThat(kal).extracting(Observation::date).containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        assertThat(kal).extracting(o -> o.value().toPlainString()).containsOnly("80.00");
        int asked = http.requests.get();
        // the other regions of the same months come from the cache: only October is asked again
        List<Observation> java = source.fetch(rain("ID_RAIN_JAVA_RICE", "id_java_rice"), LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 10));
        assertThat(java).hasSize(2);
        assertThat(http.requests.get()).isEqualTo(asked + 1);
        // a month missing further back is an error, not "not yet published"
        ChirpsSource gap = new ChirpsSource(new FakeHttp(chirpsLike(80f), List.of("2020.01")), properties());
        assertThatThrownBy(() -> gap.fetch(rain("ID_RAIN_KAL_PALM", "ID_KAL_PALM"), LocalDate.of(2019, 12, 1),
                LocalDate.of(2026, 11, 10))).isInstanceOf(SourceException.class).hasMessageContaining("no file for 2020-01");
        assertThatThrownBy(() -> source.fetch(rain("X", "MARS"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 10)))
                .isInstanceOf(SourceException.class).hasMessageContaining("unknown region");
    }

    @Test
    void aLongHistoryIsReadInChunksAndARateLimitKeepsWhatWasRead() {
        // 1981-01 .. 1986-12 is 72 months: the first fetch reads 60 and says where to continue
        ChirpsSource source = new ChirpsSource(new FakeHttp(chirpsLike(50f), List.of()), properties());
        MacroSource.PartialFetch chunk = org.junit.jupiter.api.Assertions.assertThrows(MacroSource.PartialFetch.class,
                () -> source.fetch(rain("ID_RAIN_KAL_PALM", "ID_KAL_PALM"), LocalDate.of(1981, 1, 1),
                        LocalDate.of(1987, 1, 20)));
        assertThat(chunk.observations()).hasSize(ChirpsSource.MAX_MONTHS_PER_FETCH);
        assertThat(chunk.observations().getLast().date()).isEqualTo(LocalDate.of(1985, 12, 1));
        assertThat(chunk.resumeFrom()).isEqualTo(LocalDate.of(1986, 1, 1));
        assertThat(chunk.continueAfter()).isZero();
        // another region of the same months: the 60 cached months do not count, the next 12 are read
        List<Observation> other = source.fetch(rain("ID_RAIN_JAVA_RICE", "ID_JAVA_RICE"), LocalDate.of(1981, 1, 1),
                LocalDate.of(1987, 1, 20));
        assertThat(other).hasSize(72);

        // HTTP 429 at March 1981: the two months read before it are kept, asked again later from March
        ChirpsSource limited = new ChirpsSource(new FakeHttp(chirpsLike(50f), List.of(), List.of("1981.03")), properties());
        MacroSource.PartialFetch stopped = org.junit.jupiter.api.Assertions.assertThrows(MacroSource.PartialFetch.class,
                () -> limited.fetch(rain("ID_RAIN_KAL_PALM", "ID_KAL_PALM"), LocalDate.of(1981, 1, 1),
                        LocalDate.of(1987, 1, 20)));
        assertThat(stopped.observations()).extracting(Observation::date).containsExactly(LocalDate.of(1981, 1, 1),
                LocalDate.of(1981, 2, 1));
        assertThat(stopped.resumeFrom()).isEqualTo(LocalDate.of(1981, 3, 1));
        assertThat(stopped.continueAfter()).isEqualTo(ChirpsSource.AFTER_RATE_LIMIT);
        assertThat(stopped.getMessage()).contains("HTTP 429 at 1981-03");
    }

    @Test
    void aSlowHostWaitsItsOwnPause() {
        CrawlProperties p = new CrawlProperties(java.time.Duration.ofSeconds(2), "test", java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1), 1000, Map.of("data.chc.ucsb.edu", java.time.Duration.ofSeconds(8)));
        assertThat(p.delay("data.chc.ucsb.edu")).isEqualTo(java.time.Duration.ofSeconds(8));
        assertThat(p.delay("DATA.CHC.UCSB.EDU")).isEqualTo(java.time.Duration.ofSeconds(8));
        assertThat(p.delay("fred.stlouisfed.org")).isEqualTo(java.time.Duration.ofSeconds(2));
        // a host pause shorter than the minimum does not shorten it
        CrawlProperties shorter = new CrawlProperties(java.time.Duration.ofSeconds(2), "test",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1000,
                Map.of("x.org", java.time.Duration.ofSeconds(1)));
        assertThat(shorter.delay("x.org")).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(new CrawlProperties(java.time.Duration.ofSeconds(2), "test", java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1), 1000, null).hostDelays()).isEmpty();
    }

    // ------------------------------------------------------------------ SPI

    /** The deterministic rainfall the reference values were computed from (scipy, the same Thom fit). */
    private static Map<YearMonth, Double> syntheticRain() {
        Map<YearMonth, Double> rain = new TreeMap<>();
        for (int y = 1985; y <= 2024; y++) {
            for (int m = 1; m <= 12; m++) {
                rain.put(YearMonth.of(y, m), 150 + 100 * Math.sin(2 * Math.PI * m / 12) + ((y * 7 + m * 13) % 40) - 20);
            }
        }
        return rain;
    }

    @Test
    void theSpiMatchesAnIndependentComputation() {
        NavigableMap<YearMonth, Double> spi = StandardizedPrecipitation.compute(syntheticRain(), 3);
        assertThat(spi).hasSize(478);   // every month after the first two
        // scipy.special.gammainc and scipy.stats.norm on the same totals and Thom fits
        assertThat(spi.get(YearMonth.of(1985, 3))).isCloseTo(-1.529659737864793, within(1e-6));
        assertThat(spi.get(YearMonth.of(2000, 6))).isCloseTo(1.4689722251456165, within(1e-6));
        assertThat(spi.get(YearMonth.of(2015, 9))).isCloseTo(0.7946012027460824, within(1e-6));
        assertThat(spi.get(YearMonth.of(2024, 12))).isCloseTo(-0.2973735912882721, within(1e-6));
        assertThat(StandardizedPrecipitation.unfitted(syntheticRain(), 3)).isEmpty();
    }

    @Test
    void theSpiNeedsTheBasePeriodAndCompleteWindows() {
        Map<YearMonth, Double> rain = new TreeMap<>(syntheticRain());
        rain.keySet().removeIf(m -> m.getYear() >= 1991 && m.getYear() <= 2000);   // 20 base years left
        assertThat(StandardizedPrecipitation.unfitted(rain, 3)).hasSize(12);
        assertThat(StandardizedPrecipitation.compute(rain, 3)).isEmpty();
        Map<YearMonth, Double> gap = new TreeMap<>(syntheticRain());
        gap.remove(YearMonth.of(2024, 5));   // breaks the windows ending in May, June and July 2024
        NavigableMap<YearMonth, Double> spi = StandardizedPrecipitation.compute(gap, 3);
        assertThat(spi).doesNotContainKeys(YearMonth.of(2024, 5), YearMonth.of(2024, 6), YearMonth.of(2024, 7));
        assertThat(spi).containsKey(YearMonth.of(2024, 8));
        // a drier total scores lower; the bound holds for no rain at all
        StandardizedPrecipitation.Fit fit = StandardizedPrecipitation.fit(List.of(300.0, 350.0, 400.0, 420.0, 500.0));
        assertThat(StandardizedPrecipitation.spi(250, fit)).isLessThan(StandardizedPrecipitation.spi(450, fit));
        assertThat(StandardizedPrecipitation.spi(0, fit)).isCloseTo(-StandardizedPrecipitation.MAX, within(1e-9));
    }

    @Test
    void theSpiSourceRefusesAnIncompleteRainfallHistory() {
        List<com.neracalab.backend.macro.MacroRepository.Row> rows = new ArrayList<>();
        syntheticRain().forEach((m, v) -> rows.add(new com.neracalab.backend.macro.MacroRepository.Row(m.atDay(1),
                BigDecimal.valueOf(v), m.atEndOfMonth(), m.atEndOfMonth().plusDays(15))));
        com.neracalab.backend.macro.MacroRepository repository = new com.neracalab.backend.macro.MacroRepository(null,
                null, null) {
            @Override
            public List<Row> observations(String code, LocalDate from) {
                return code.equals("ID_RAIN_KAL_PALM") ? rows : List.of();
            }
        };
        SpiSource source = new SpiSource(repository);
        MacroSeries spi = new MacroSeries("ID_SPI3_KAL_PALM", "x", "ID", "WEATHER_DROUGHT", "SPI", "MONTHLY", "spi",
                "ID_RAIN_KAL_PALM", null, 15, 1, "DALIO", new BigDecimal("-5"), new BigDecimal("5"), null,
                LocalDate.of(1981, 3, 1), null);
        List<Observation> out = source.fetch(spi, LocalDate.of(2024, 10, 1), LocalDate.of(2026, 11, 16));
        assertThat(out).extracting(Observation::date).containsExactly(LocalDate.of(2024, 10, 1),
                LocalDate.of(2024, 11, 1), LocalDate.of(2024, 12, 1));
        assertThat(out.get(2).value().doubleValue()).isCloseTo(-0.2973735912882721, within(5e-4));
        MacroSeries orphan = new MacroSeries("ID_SPI3_JAVA_RICE", "x", "ID", "WEATHER_DROUGHT", "SPI", "MONTHLY", "spi",
                "ID_RAIN_JAVA_RICE", null, 15, 1, "DALIO", new BigDecimal("-5"), new BigDecimal("5"), null,
                LocalDate.of(1981, 3, 1), null);
        assertThatThrownBy(() -> source.fetch(orphan, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 16)))
                .isInstanceOf(SourceException.class).hasMessageContaining("fetch ID_RAIN_JAVA_RICE");
    }
}
