package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Share counts of the error log of 2026-10-16 (BKSL, CUAN, DSSA, BUKA): a par value only one column's EPS fits, and
 * published counts (Yahoo Finance, October 2026) that fit a filing only on the count of before a later split, or
 * only as the end count of a period with a buyback.
 */
class FilingMapperWebSplitsTest {

    private static final String YAHOO = "Yahoo Finance";

    /** CUAN: split 1:10 on 2025-07-15; split-adjusted counts. */
    private static final List<WebShareCount> CUAN = List.of(
            count("2022-12-31", 112_418_900_000L, 112_418_900_000L, null),
            count("2023-12-31", 112_418_900_000L, 112_418_900_000L, null),
            count("2024-12-31", 112_418_900_000L, 112_418_900_000L, null),
            count("2025-06-30", 112_418_900_000L, 112_418_900_000L, null),
            count("2025-12-31", 112_414_716_000L, 112_418_900_000L, 4_184_000L),
            count("2026-06-30", 112_399_199_400L, 112_399_199_400L, null));
    private static final List<WebSplit> CUAN_SPLITS = List.of(split("2025-07-15", "10"));

    /** DSSA: splits 1:10 on 2024-07-18 and 1:25 on 2026-04-09; a buyback in 2023. */
    private static final List<WebShareCount> DSSA = List.of(
            count("2022-12-31", 192_638_080_000L, 192_638_080_000L, null),
            count("2023-12-31", 154_111_748_250L, 192_638_080_000L, 38_526_331_750L),
            count("2024-12-31", 154_732_123_250L, 192_638_080_000L, 37_905_956_750L),
            count("2025-06-30", 154_732_123_250L, 192_638_080_000L, 37_905_956_750L),
            count("2025-12-31", 154_732_123_250L, 192_638_080_000L, 37_905_956_750L),
            count("2026-03-31", 154_732_123_250L, 192_638_080_000L, 37_905_956_750L));
    private static final List<WebSplit> DSSA_SPLITS = List.of(split("2026-04-09", "25"), split("2024-07-18", "10"));

    /** BUKA: buybacks from 2025. */
    private static final List<WebShareCount> BUKA = List.of(
            count("2023-12-31", 103_099_967_067L, 103_099_967_067L, 0L),
            count("2024-12-31", 103_121_636_167L, 103_121_636_167L, null),
            count("2025-06-30", 103_121_636_167L, 103_121_636_167L, null),
            count("2025-12-31", 92_970_828_660L, 103_148_706_067L, 10_177_877_407L),
            count("2026-06-30", 91_833_653_260L, 103_167_090_767L, 11_333_437_507L));

    /**
     * BKSL FY2025: FY2024's EPS 0.10 (17,355 million profit) fits par 50, FY2025's 4.95 (830,295 million) no
     * standard par value; par 50 gave 251,243,773,287 shares at every date, 50% too many. No par value is used, and
     * Yahoo's 167,708,902,705 fits both EPS.
     */
    @Test
    void parValueOnlyOneColumnsEpsFitsIsNotUsed() throws Exception {
        FilingMapper mapper = mapper("BKSL", "2025-Tahunan");
        ShareCapital filing = mapper.shareCapital();

        assertThat(filing.resolved()).isFalse();
        assertThat(filing.parValue()).isNull();
        assertThat(filing.checks()).anyMatch(c -> c.rule().equals("par_value") && c.message().startsWith(
                "Par value 50 fits only one column's EPS: 2025 FY share capital 12562188664350 / 50 = 251243773287 shares"));

        ShareCapital shares = mapper.withWebShareCounts(filing, List.of(
                count("2023-12-31", 167_708_902_705L, 167_708_902_705L, null),
                count("2024-12-31", 167_708_902_705L, 167_708_902_705L, null),
                count("2025-12-31", 167_708_902_705L, 167_708_902_705L, null)), YAHOO);
        assertThat(outstanding(shares)).hasSize(3).allSatisfy((d, n) -> assertThat(n).isEqualByComparingTo("167708902705"));
    }

    /**
     * CUAN FY2024 (EPS 0.014 = 160,785,000 / 11.5 billion shares) predates the split 1:10 of 2025-07-15: Yahoo's
     * 112,418,900,000 gives 0.00143 and is refused without the split; with it, the count is used as published and
     * the split is returned with the count before it.
     */
    @Test
    void countOfAfterALaterSplitFitsOnTheCountOfBeforeIt() throws Exception {
        FilingMapper mapper = mapper("CUAN", "2024-Tahunan");
        assertThat(mapper.withWebShareCounts(mapper.shareCapital(), CUAN, YAHOO).resolved()).isFalse();

        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), CUAN, CUAN_SPLITS, YAHOO);

        assertThat(shares.resolved()).isTrue();
        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2024-12-31"), new BigDecimal("112418900000"))
                .containsEntry(LocalDate.parse("2023-12-31"), new BigDecimal("112418900000"));
        assertThat(shares.basis()).contains("2024-12-31: 112418900000 (the filing's EPS is on the count of before the split 1:10 of 2025-07-15)");
        assertThat(shares.webSplits()).singleElement().satisfies(s -> {
            assertThat(s.date()).isEqualTo(LocalDate.parse("2025-07-15"));
            assertThat(s.factor()).isEqualByComparingTo("10");
            assertThat(s.sharesBefore()).isEqualByComparingTo("11241890000");
        });
    }

    /** CUAN H1 2026: its own dates fit as published, its prior-year dates on the count of before the split. */
    @Test
    void oneFilingMayHaveDatesOnBothSidesOfASplit() throws Exception {
        FilingMapper mapper = mapper("CUAN", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), CUAN, CUAN_SPLITS, YAHOO);

        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2026-06-30"), new BigDecimal("112399199400"))
                .containsEntry(LocalDate.parse("2025-12-31"), new BigDecimal("112414716000"))
                .containsEntry(LocalDate.parse("2025-06-30"), new BigDecimal("112418900000"));
        assertThat(shares.webSplits()).hasSize(1);
    }

    /**
     * DSSA FY2023 predates both splits (EPS 0.59 on about 722 million weighted shares) and bought back 20% of its
     * shares in 2023: the end count (616 million before the splits) gives 0.69, the start count (771 million) 0.55,
     * and the EPS lies between them. Both splits are returned, each with the count just before it.
     */
    @Test
    void endCountOfAPeriodWithABuybackFitsBetweenTheCounts() throws Exception {
        FilingMapper mapper = mapper("DSSA", "2023-Tahunan");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), DSSA, DSSA_SPLITS, YAHOO);

        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2023-12-31"), new BigDecimal("154111748250"))
                .containsEntry(LocalDate.parse("2022-12-31"), new BigDecimal("192638080000"));
        assertThat(shares.snapshots().stream().filter(s -> s.date().equals(LocalDate.parse("2023-12-31"))).findFirst()
                .orElseThrow().treasuryShares()).isEqualByComparingTo("38526331750");
        assertThat(shares.webSplits()).extracting(WebSplit::date, WebSplit::sharesBefore).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2024-07-18"), new BigDecimal("616446993")),
                org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-04-09"), new BigDecimal("6189284930")));
    }

    /** DSSA FY2024 already reflects the split of 2024: only the one of 2026 is needed. */
    @Test
    void aFilingReflectsTheSplitsBeforeIt() throws Exception {
        FilingMapper mapper = mapper("DSSA", "2024-Tahunan");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), DSSA, DSSA_SPLITS, YAHOO);

        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2024-12-31"), new BigDecimal("154732123250"));
        assertThat(shares.webSplits()).extracting(WebSplit::date).containsExactly(LocalDate.parse("2026-04-09"));
    }

    /**
     * DSSA H1 2026 files an EPS of 0.04 for 174,358,486 profit: 4.4 billion shares, neither the 154.7 billion of after
     * the split nor the 6.2 billion of before it. Its own dates get no count; the prior year's dates do.
     */
    @Test
    void anEpsThatFitsNoCountGivesNoCount() throws Exception {
        FilingMapper mapper = mapper("DSSA", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), DSSA, DSSA_SPLITS, YAHOO);

        assertThat(outstanding(shares)).doesNotContainKey(LocalDate.parse("2025-12-31"))
                .containsEntry(LocalDate.parse("2025-06-30"), new BigDecimal("154732123250"));
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("2025-12-31")
                && c.message().contains("nor on the count of before the published split 1:25 of 2026-04-09"));
    }

    /**
     * BUKA FY2025 bought back 10.2 billion shares: the end count 92,970,828,660 gives 33.80, the EPS 31.91 is per
     * weighted share and lies between that and profit / the start count 103,121,636,167 = 30.47.
     */
    @Test
    void buybackEndCountIsUsedWithItsTreasuryShares() throws Exception {
        FilingMapper mapper = mapper("BUKA", "2025-Tahunan");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), BUKA, YAHOO);

        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2025-12-31"), new BigDecimal("92970828660"));
        assertThat(shares.snapshots().stream().filter(s -> s.date().equals(LocalDate.parse("2025-12-31"))).findFirst()
                .orElseThrow().treasuryShares()).isEqualByComparingTo("10177877407");
        assertThat(shares.webSplits()).isEmpty();
    }

    /**
     * BUKA H1 2026: EPS -8.53 is 96.2 billion weighted shares, more than both the start (92,970,828,660) and the end
     * count (91,833,653,260); it does not lie between them, so neither is used.
     */
    @Test
    void anEpsOutsideTheStartAndEndCountsIsRefused() throws Exception {
        FilingMapper mapper = mapper("BUKA", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), BUKA, YAHOO);

        assertThat(outstanding(shares)).doesNotContainKeys(LocalDate.parse("2026-06-30"), LocalDate.parse("2025-12-31"));
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("2026-06-30")
                && c.message().contains("nor between it and profit / the count at 2025-12-31"));
    }

    /** A rights issue is no buyback: the shares issued changed, so the start count gives no range (BKSL FY2023). */
    @Test
    void sharesIssuedInThePeriodGiveNoRange() throws Exception {
        FilingMapper mapper = mapper("BKSL", "2023-Tahunan");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), List.of(
                count("2022-12-31", 68_594_046_211L, 68_594_046_211L, null),
                count("2023-12-31", 167_708_902_705L, 167_708_902_705L, null)), YAHOO);

        assertThat(outstanding(shares)).containsEntry(LocalDate.parse("2022-12-31"), new BigDecimal("68594046211"))
                .doesNotContainKey(LocalDate.parse("2023-12-31"));
    }

    private static WebShareCount count(String date, long outstanding, Long issued, Long treasury) {
        return new WebShareCount(LocalDate.parse(date), BigDecimal.valueOf(outstanding),
                issued == null ? null : BigDecimal.valueOf(issued), treasury == null ? null : BigDecimal.valueOf(treasury));
    }

    private static WebSplit split(String date, String factor) {
        return new WebSplit(LocalDate.parse(date), new BigDecimal(factor));
    }

    private static Map<LocalDate, BigDecimal> outstanding(ShareCapital shares) {
        Map<LocalDate, BigDecimal> m = new LinkedHashMap<>();
        for (ShareAt s : shares.snapshots()) {
            if (s.sharesOutstanding() != null) {
                m.put(s.date(), s.sharesOutstanding());
            }
        }
        return m;
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
