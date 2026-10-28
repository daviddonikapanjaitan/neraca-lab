package com.neracalab.backend.ownership.onepercent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ownership.onepercent.OnePercentDigest.Digest;
import com.neracalab.backend.ownership.onepercent.OnePercentDigest.HolderLine;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Holder;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Report;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Entity;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Filter;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.MovementRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Outcome;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.ReportRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Sort;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Stored;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.TopInvestor;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Transaction;

/**
 * Three monthly lists of companies of their own (ZZOP and ZZOQ, 1,000 shares listed each) in 2001, in the real
 * database, rolled back: stored out of order (February first), a buy, a sell, an investor no longer above 1%, one new
 * above it, a block of shares listed under another name (moved) and a name spelled otherwise (renamed).
 */
@SpringBootTest
@Transactional
class OnePercentRepositoryTest {

    private static final String IDX = "IDX";
    private static final LocalDate JAN = LocalDate.of(2001, 1, 31);
    private static final LocalDate FEB = LocalDate.of(2001, 2, 28);
    private static final LocalDate MAR = LocalDate.of(2001, 3, 30);

    @Autowired
    private OnePercentRepository repository;

    private static int row;

    private static Holder holder(String ticker, String name, String type, String group, String status, long shares) {
        return new Holder(++row, ticker, "PT UJI " + ticker + " Tbk", name, OnePercentParser.holderKey(name), List.of(),
                1, type, group, status, null, "A".equals(status) ? "SINGAPORE" : "INDONESIA", shares, 0, shares,
                BigDecimal.valueOf(shares).movePointLeft(1));
    }

    /** January: ALFA 600, BETA 100, GAMMA 50, DELTA BANK 40; ALFA holds 100 of ZZOQ too. */
    private static Report january() {
        row = 0;
        return new Report(JAN, List.of(holder("ZZOP", "PT ALFAQZ", "Corporate", "CORPORATE", "L", 600),
                holder("ZZOP", "BETA", "Individual", "INDIVIDUAL", "L", 100),
                holder("ZZOP", "GAMMA FUND", "Mutual Funds", "FUND", "A", 50),
                holder("ZZOP", "PT DELTA BANK", "Bank", "FINANCIAL", "L", 40),
                holder("ZZOQ", "ALFAQZ, PT", "Corporate", "CORPORATE", "L", 100)), 5, List.of(), List.of());
    }

    /** February: ALFA bought 50, BETA sold 20, GAMMA no longer listed, DELTA BANK's 40 shares as PANIN DELTA, EPSILON new. */
    private static Report february() {
        row = 0;
        return new Report(FEB, List.of(holder("ZZOP", "PT ALFAQZ", "Corporate", "CORPORATE", "L", 650),
                holder("ZZOP", "BETA", "Individual", "INDIVIDUAL", "L", 80),
                holder("ZZOP", "PANIN DELTA", "Bank", "FINANCIAL", "L", 40),
                holder("ZZOP", "EPSILON CAPITAL", "Private Equity", "FUND", "A", 30),
                holder("ZZOQ", "PT ALFAQZ", "Corporate", "CORPORATE", "L", 100)), 5, List.of(), List.of());
    }

    /** March: EPSILON CAPITAL spelled EPSILLON CAPITAL, and it bought 5. */
    private static Report march() {
        row = 0;
        return new Report(MAR, List.of(holder("ZZOP", "PT ALFAQZ", "Corporate", "CORPORATE", "L", 650),
                holder("ZZOP", "BETA", "Individual", "INDIVIDUAL", "L", 80),
                holder("ZZOP", "PANIN DELTA", "Bank", "FINANCIAL", "L", 40),
                holder("ZZOP", "EPSILLON CAPITAL", "Private Equity", "FUND", "A", 35),
                holder("ZZOQ", "PT ALFAQZ", "Corporate", "CORPORATE", "L", 120)), 5, List.of(), List.of());
    }

    private List<MovementRow> rows(LocalDate date, String ticker) {
        return repository.movements(new Filter(IDX, List.of(ticker), null, Transaction.ALL, null, null, date, date),
                Sort.DATE, 100, 0).rows();
    }

    private static MovementRow named(List<MovementRow> rows, String name) {
        return rows.stream().filter(r -> r.holderName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not listed"));
    }

    @Test
    void storesComparesAndReadsTheMonths() {
        // February alone: nothing to compare with
        Stored feb = repository.store(IDX, february(), null, "peng-2001-02-00002-satu-persen.xlsx", null);
        assertThat(feb.outcome()).isEqualTo(Outcome.STORED);
        assertThat(feb.previousDate()).isNull();
        assertThat(feb.exited()).isNull();
        assertThat(rows(FEB, "ZZOP")).allMatch(r -> r.movement() == null && r.previousDate() == null);

        // January stored later: February is compared with it again
        Stored jan = repository.store(IDX, january(), null, "peng-01-00001-satu-persen.xlsx", null);
        assertThat(jan.previousDate()).isNull();
        assertThat(jan.nextDate()).isEqualTo(FEB);
        List<MovementRow> february = rows(FEB, "ZZOP");
        assertThat(named(february, "PT ALFAQZ").movement()).isEqualTo("BUY");
        assertThat(named(february, "PT ALFAQZ").change()).isEqualTo(50);
        assertThat(named(february, "PT ALFAQZ").percentChange()).isEqualByComparingTo("5.00");
        assertThat(named(february, "BETA").movement()).isEqualTo("SELL");
        assertThat(named(february, "BETA").change()).isEqualTo(-20);
        MovementRow moved = named(february, "PANIN DELTA");
        assertThat(moved.matchKind()).isEqualTo("MOVED");
        assertThat(moved.previousName()).isEqualTo("PT DELTA BANK");
        assertThat(moved.movement()).isEqualTo("UNCHANGED");
        assertThat(named(february, "EPSILON CAPITAL").movement()).isEqualTo("ENTERED");
        MovementRow gamma = named(february, "GAMMA FUND");
        assertThat(gamma.leftList()).isTrue();
        assertThat(gamma.movement()).isEqualTo("EXITED");
        assertThat(gamma.sharesPrevious()).isEqualTo(50);
        assertThat(gamma.previousDate()).isEqualTo(JAN);
        // ZZOQ's "ALFAQZ, PT" is "PT ALFAQZ": the same investor
        assertThat(named(rows(FEB, "ZZOQ"), "PT ALFAQZ").matchKind()).isEqualTo("SAME");
        assertThat(repository.exited(feb.reportId())).isEqualTo(1);

        Stored mar = repository.store(IDX, march(), null, "peng-2001-03-00003-satu-persen.xlsx", null);
        assertThat(mar.previousDate()).isEqualTo(FEB);
        assertThat(mar.exited()).isZero();
        MovementRow renamed = named(rows(MAR, "ZZOP"), "EPSILLON CAPITAL");
        assertThat(renamed.matchKind()).isEqualTo("RENAMED");
        assertThat(renamed.previousName()).isEqualTo("EPSILON CAPITAL");
        assertThat(renamed.movement()).isEqualTo("BUY");
        assertThat(renamed.change()).isEqualTo(5);

        // the lists, latest first, with their movements
        List<ReportRow> reports = repository.reports(IDX, 100, 0).reports().stream()
                .filter(r -> r.reportDate().getYear() == 2001).toList();
        assertThat(reports).extracting(ReportRow::reportDate).containsExactly(MAR, FEB, JAN);
        ReportRow febRow = reports.get(1);
        assertThat(febRow.previousDate()).isEqualTo(JAN);
        assertThat(febRow.announcementNumber()).isEqualTo("00002");
        assertThat(febRow.buys()).isEqualTo(1);
        assertThat(febRow.sells()).isEqualTo(1);
        assertThat(febRow.entered()).isEqualTo(1);
        assertThat(febRow.exited()).isEqualTo(1);
        assertThat(febRow.moved()).isEqualTo(1);
        assertThat(febRow.unchanged()).isEqualTo(2);
        assertThat(reports.get(0).renamed()).isEqualTo(1);
        assertThat(reports.get(2).exited()).isNull();
        assertThat(reports.get(2).announcementNumber()).isEqualTo("00001");

        // filters and order, to March 2001 (a stored list of a later year is compared with March 2001 now: ZZOP's
        // investors are no longer above 1% there)
        assertThat(repository.movements(new Filter(IDX, List.of("ZZOP"), null, Transaction.RENAMED, null, null, null,
                MAR), Sort.DATE, 10, 0).rows()).extracting(MovementRow::holderName)
                .containsExactly("EPSILLON CAPITAL", "PANIN DELTA");
        assertThat(repository.movements(new Filter(IDX, List.of("ZZOP", "ZZOQ"), null, Transaction.CHANGED, null, null,
                null, MAR), Sort.CHANGE_DESC, 10, 0).rows()).extracting(MovementRow::change)
                .containsExactly(50L, 20L, 5L, -20L);
        assertThat(repository.movements(new Filter(IDX, List.of("ZZOP"), null, Transaction.ALL, "A", "FUND", null,
                MAR), Sort.DATE, 10, 0).rows()).extracting(MovementRow::holderName)
                .containsExactly("EPSILLON CAPITAL", "EPSILON CAPITAL", "GAMMA FUND", "GAMMA FUND");
        assertThat(repository.largest(IDX, FEB, Transaction.BUY, 5)).extracting(MovementRow::holderName)
                .contains("PT ALFAQZ");

        // one company: the digest follows EPSILLON back to EPSILON and PANIN DELTA back to DELTA BANK
        Digest digest = OnePercentDigest.of("ZZOP", repository.holders(IDX, "ZZOP", null, MAR));
        assertThat(digest.months()).isEqualTo(3);
        assertThat(digest.firstDate()).isEqualTo(JAN);
        assertThat(digest.latestDate()).isEqualTo(MAR);
        assertThat(digest.holders()).extracting(HolderLine::name)
                .containsExactly("PT ALFAQZ", "BETA", "PANIN DELTA", "EPSILLON CAPITAL");
        HolderLine epsilon = digest.holders().get(3);
        assertThat(epsilon.since()).isEqualTo(FEB);
        assertThat(epsilon.sharesSince()).isEqualTo(30);
        assertThat(epsilon.netChange()).isEqualTo(5);
        assertThat(epsilon.previousName()).isEqualTo("EPSILON CAPITAL");
        assertThat(epsilon.status()).isEqualTo("FOREIGN");
        HolderLine panin = digest.holders().get(2);
        assertThat(panin.since()).isEqualTo(JAN);
        assertThat(panin.netChange()).isZero();
        assertThat(digest.formerHolders()).extracting(OnePercentDigest.FormerHolder::name).containsExactly("GAMMA FUND");
        assertThat(digest.totals().holdersOwnership()).isEqualByComparingTo("0.805");
        assertThat(digest.totals().foreignOwnership()).isEqualByComparingTo("0.035");
        assertThat(digest.totals().top3Ownership()).isEqualByComparingTo("0.770");
        assertThat(digest.totals().buys()).isEqualTo(2);
        assertThat(digest.totals().moved()).isEqualTo(1);
        assertThat(digest.totals().renamed()).isEqualTo(1);
        assertThat(digest.byType().getFirst().group()).isEqualTo("CORPORATE");
        String text = OnePercentDigest.text(digest);
        assertThat(text).contains("ZZOP - PT UJI ZZOP Tbk: shareholders of more than 1%",
                "PANIN DELTA holds the same 40 shares listed under PT DELTA BANK the month before",
                "GAMMA FUND no longer above 1%", "EPSILON CAPITAL new above 1% with 30 shares",
                "listed as EPSILON CAPITAL the month before");
        assertThat(digest.reading()).startsWith("On 2001-03-30, 4 investors held more than 1% of ZZOP");

        // an investor across its companies, and the investors in the most companies
        Entity alfa = repository.entities(IDX, "alfaqz", 5).stream().filter(e -> e.holderKey().equals("ALFAQZ"))
                .findFirst().orElseThrow();
        assertThat(alfa.holdings()).extracting(OnePercentRepository.EntityHolding::ticker).containsExactly("ZZOP", "ZZOQ");
        assertThat(alfa.holdings().getFirst().change()).isEqualTo(50);
        assertThat(alfa.holdings().getFirst().firstDate()).isEqualTo(JAN);
        assertThat(alfa.holdings().get(1).change()).isEqualTo(20);
        List<TopInvestor> top = repository.topInvestors(IDX, MAR, 10);
        assertThat(top).anyMatch(t -> t.holderKey().equals("ALFAQZ") && t.companies() == 2 && t.largestTicker().equals("ZZOP"));

        // February stored again: March is compared with the new February
        Stored again = repository.store(IDX, february(), null, "peng-2001-02-00002-satu-persen.xlsx", null);
        assertThat(again.outcome()).isEqualTo(Outcome.REPLACED);
        assertThat(again.nextDate()).isEqualTo(MAR);
        assertThat(named(rows(MAR, "ZZOP"), "EPSILLON CAPITAL").matchKind()).isEqualTo("RENAMED");

        // February removed: March is compared with January
        assertThat(repository.delete(IDX, FEB)).isTrue();
        List<MovementRow> march = rows(MAR, "ZZOP");
        assertThat(named(march, "PT ALFAQZ").previousDate()).isEqualTo(JAN);
        assertThat(named(march, "PT ALFAQZ").change()).isEqualTo(50);
        assertThat(named(march, "EPSILLON CAPITAL").movement()).isEqualTo("ENTERED");
        assertThat(repository.delete(IDX, FEB)).isFalse();
    }

    /**
     * Every list of data/IDX_OWNERSHIP_ONE_PERCENTAGE (February - September 2026), stored and rolled back: each month
     * is compared with the month before; the renamings seen in the lists are matched.
     */
    @Test
    void storesThePublishedLists() throws IOException {
        assumeTrue(Files.isDirectory(OnePercentParserTest.DIR), "1% ownership source data not available");
        List<Path> files;
        try (var list = Files.list(OnePercentParserTest.DIR)) {
            files = list.filter(f -> f.getFileName().toString().endsWith("-satu-persen.xlsx")).sorted().toList();
        }
        assumeTrue(files.size() >= 2, "fewer than two lists");
        for (Path f : files) {
            Report r = OnePercentParser.parse(Files.readAllBytes(f));
            repository.store(IDX, r, null, f.getFileName().toString(), null);
        }
        List<ReportRow> reports = repository.reports(IDX, 50, 0).reports();
        for (ReportRow r : reports) {
            if (r.previousDate() == null) {
                continue;
            }
            // most investors are the same month to month
            assertThat(r.unchanged() + r.buys() + r.sells()).as(r.reportDate().toString())
                    .isGreaterThan(r.holders() * 9 / 10);
            assertThat(r.exited()).isLessThan(r.holders() / 10);
        }
        LocalDate aug = LocalDate.of(2026, 8, 31);
        LocalDate sep = LocalDate.of(2026, 9, 30);
        if (reports.stream().anyMatch(r -> r.reportDate().equals(aug))
                && reports.stream().anyMatch(r -> r.reportDate().equals(sep))) {
            // "IR. T. PERMADI RACHMAT" in August is "T PERMADI RACHMAT" in September
            MovementRow permadi = named(rows(sep, "AADI"), "T PERMADI RACHMAT");
            assertThat(permadi.matchKind()).isEqualTo("SAME");
            assertThat(permadi.previousName()).isEqualTo("IR. T. PERMADI RACHMAT");
            assertThat(permadi.movement()).isEqualTo("UNCHANGED");
        }
        LocalDate mar = LocalDate.of(2026, 3, 31);
        LocalDate apr = LocalDate.of(2026, 4, 30);
        if (reports.stream().anyMatch(r -> r.reportDate().equals(mar))
                && reports.stream().anyMatch(r -> r.reportDate().equals(apr))) {
            // "BANK PAN INDONESIA TBK, PT" in March, the same 388,000,000 shares as "Panin Bank Tbk, PT" in April
            MovementRow panin = named(rows(apr, "AMAG"), "Panin Bank Tbk, PT");
            assertThat(panin.matchKind()).isEqualTo("MOVED");
            assertThat(panin.previousName()).isEqualTo("BANK PAN INDONESIA TBK, PT");
            assertThat(panin.change()).isZero();
        }
    }
}
