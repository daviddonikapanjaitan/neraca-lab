package com.neracalab.backend.ownership;

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

import com.neracalab.backend.ownership.OwnershipDigest.Digest;
import com.neracalab.backend.ownership.OwnershipParser.Account;
import com.neracalab.backend.ownership.OwnershipParser.Holder;
import com.neracalab.backend.ownership.OwnershipParser.Report;
import com.neracalab.backend.ownership.OwnershipRepository.Entity;
import com.neracalab.backend.ownership.OwnershipRepository.Filter;
import com.neracalab.backend.ownership.OwnershipRepository.HolderDay;
import com.neracalab.backend.ownership.OwnershipRepository.MovementRow;
import com.neracalab.backend.ownership.OwnershipRepository.Outcome;
import com.neracalab.backend.ownership.OwnershipRepository.ReportRow;
import com.neracalab.backend.ownership.OwnershipRepository.Sort;
import com.neracalab.backend.ownership.OwnershipRepository.Transaction;
import com.neracalab.backend.rag.ExcelDocumentType;

/**
 * Two daily lists of a company of its own (ZZOW, listed shares 1,000) in January 2001, in the real database, rolled
 * back: buys, sells, an alias, a move between accounts, an investor crossing 5% and one falling below it.
 */
@SpringBootTest
@Transactional
class OwnershipRepositoryTest {

    private static final ExcelDocumentType TYPE = ExcelDocumentType.OWNERSHIP_5_PERCENT;
    private static final LocalDate JAN2 = LocalDate.of(2001, 1, 2);
    private static final LocalDate JAN3 = LocalDate.of(2001, 1, 3);
    private static final LocalDate JAN4 = LocalDate.of(2001, 1, 4);

    @Autowired
    private OwnershipRepository repository;

    private static Holder holder(int row, String name, String status, Long previous, Long current, Integer aliasOf,
                                 List<Account> accounts) {
        return new Holder(row, "ZZOW", "PT UJI KEPEMILIKAN Tbk", name, OwnershipParser.holderKey(name), null,
                "A".equals(status) ? "SINGAPORE" : "INDONESIA", status, previous,
                previous == null ? null : BigDecimal.valueOf(previous).movePointLeft(1), current,
                current == null ? null : BigDecimal.valueOf(current).movePointLeft(1), aliasOf, accounts);
    }

    private static Account account(int index, String participant, Long previous, Long current) {
        return new Account(index, participant, "ACCOUNT " + index, null, "INDONESIA", "L", previous, current);
    }

    /** Jan 3: ALFA unchanged (200 shares moved between its accounts), BETAZZ bought, GAMMA sold, ALFA listed twice. */
    private static Report january3() {
        List<Account> alfa = List.of(account(1, "BROKER A", 300L, 200L), account(2, "BROKER B", 300L, 400L));
        return new Report(JAN3, JAN2, "KEPEMILIKAN EFEK DIATAS 5% (test)", List.of(
                holder(1, "PT ALFA", "L", 600L, 600L, null, alfa),
                holder(2, "ALFA, PT", "L", 600L, 600L, 1, alfa),
                holder(3, "BETAZZ", "A", 100L, 120L, null, List.of(account(1, "CUSTODIAN", 100L, 120L))),
                holder(4, "GAMMA", "L", 60L, 50L, null, List.of(account(1, "BROKER C", 60L, 50L)))),
                List.of(), List.of());
    }

    /** Jan 4: BETAZZ bought again, DELTAZZ crossed 5%, GAMMA is no longer listed (fell below 5%). */
    private static Report january4() {
        return new Report(JAN4, JAN3, "KEPEMILIKAN EFEK DIATAS 5% (test)", List.of(
                holder(1, "PT ALFA", "L", 600L, 600L, null, List.of(account(1, "BROKER A", 200L, 200L),
                        account(2, "BROKER B", 400L, 400L))),
                holder(2, "BETAZZ", "A", 120L, 150L, null, List.of(account(1, "CUSTODIAN", 120L, 150L))),
                holder(3, "DELTAZZ", "L", null, 70L, null, List.of(account(1, "BROKER D", null, 70L)))),
                List.of(), List.of("a note"));
    }

    private List<MovementRow> rows(Transaction transaction, boolean aliases) {
        return repository.movements(new Filter("IDX", TYPE, List.of("ZZOW"), null, transaction, null, null, null,
                aliases), 500, 0).rows();
    }

    @Test
    void storesTheDaysAndFindsWhoLeftTheList() {
        var first = repository.store("IDX", TYPE, january3(), null, "peng-2001-01-03-00001-lima-persen.xlsx", null);
        assertThat(first.outcome()).isEqualTo(Outcome.STORED);
        assertThat(first.exited()).isNull();      // the list of Jan 2 is not stored
        var second = repository.store("IDX", TYPE, january4(), null, "peng-2001-01-04-00002-lima-persen.xlsx", null);
        assertThat(second.exited()).isEqualTo(1);

        List<MovementRow> all = rows(Transaction.ALL, false);
        assertThat(all).extracting(MovementRow::reportDate, MovementRow::holderName, MovementRow::movement)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JAN4, "PT ALFA", "UNCHANGED"),
                        org.assertj.core.groups.Tuple.tuple(JAN4, "BETAZZ", "BUY"),
                        org.assertj.core.groups.Tuple.tuple(JAN4, "DELTAZZ", "ENTERED"),
                        org.assertj.core.groups.Tuple.tuple(JAN4, "GAMMA", "EXITED"),
                        org.assertj.core.groups.Tuple.tuple(JAN3, "PT ALFA", "UNCHANGED"),
                        org.assertj.core.groups.Tuple.tuple(JAN3, "BETAZZ", "BUY"),
                        org.assertj.core.groups.Tuple.tuple(JAN3, "GAMMA", "SELL"));
        MovementRow alfa = all.get(4);
        assertThat(alfa.aliases()).containsExactly("ALFA, PT");
        assertThat(alfa.accountMoves()).isEqualTo(2);
        assertThat(rows(Transaction.ALL, true)).hasSize(8);

        MovementRow gamma = rows(Transaction.EXITED, false).getFirst();
        assertThat(gamma.leftList()).isTrue();
        assertThat(gamma.sharesPrevious()).isEqualTo(50L);
        assertThat(gamma.sharesCurrent()).isNull();
        assertThat(gamma.percentPrevious()).isEqualByComparingTo("5.0");
        assertThat(gamma.previousDate()).isEqualTo(JAN3);

        MovementRow beta = rows(Transaction.BUY, false).getFirst();
        assertThat(beta.change()).isEqualTo(30L);
        assertThat(beta.percentChange()).isEqualByComparingTo("3.0");
        assertThat(rows(Transaction.CROSSED, false)).extracting(MovementRow::holderName).containsExactly("DELTAZZ", "GAMMA");
        assertThat(rows(Transaction.ACTIVITY, false)).hasSize(5);
        assertThat(repository.movements(new Filter("IDX", TYPE, List.of("ZZOW"), "beta", Transaction.ALL, "A", JAN4,
                JAN4, false), 500, 0).rows()).extracting(MovementRow::holderName).containsExactly("BETAZZ");

        // the same day again replaces it
        assertThat(repository.store("IDX", TYPE, january4(), null, "again.xlsx", null).outcome())
                .isEqualTo(Outcome.REPLACED);
        assertThat(rows(Transaction.ALL, false)).hasSize(7);
    }

    private List<String> sorted(Sort sort) {
        return repository.movements(new Filter("IDX", TYPE, List.of("ZZOW"), null, Transaction.ALL, null, null, null,
                false), sort, 500, 0).rows().stream()
                .map(r -> r.reportDate().getDayOfMonth() + " " + r.holderName() + " " + r.change()).toList();
    }

    @Test
    void sortsByTheChangeEitherWayWithoutAChangeLast() {
        repository.store("IDX", TYPE, january3(), null, null, null);
        repository.store("IDX", TYPE, january4(), null, null, null);
        assertThat(sorted(Sort.CHANGE_DESC)).containsExactly("4 BETAZZ 30", "3 BETAZZ 20", "4 PT ALFA 0",
                "3 PT ALFA 0", "3 GAMMA -10", "4 DELTAZZ null", "4 GAMMA null");
        assertThat(sorted(Sort.CHANGE_ASC)).containsExactly("3 GAMMA -10", "4 PT ALFA 0", "3 PT ALFA 0",
                "3 BETAZZ 20", "4 BETAZZ 30", "4 DELTAZZ null", "4 GAMMA null");
        // percentage points: BETAZZ +3.0 (12 -> 15), +2.0 (10 -> 12), ALFA 0, GAMMA -1.0 (6 -> 5)
        assertThat(sorted(Sort.POINTS_DESC)).containsExactly("4 BETAZZ 30", "3 BETAZZ 20", "4 PT ALFA 0",
                "3 PT ALFA 0", "3 GAMMA -10", "4 DELTAZZ null", "4 GAMMA null");
        assertThat(sorted(Sort.POINTS_ASC).getFirst()).isEqualTo("3 GAMMA -10");
        assertThat(sorted(Sort.DATE)).containsExactly("4 PT ALFA 0", "4 BETAZZ 30", "4 DELTAZZ null",
                "4 GAMMA null", "3 PT ALFA 0", "3 BETAZZ 20", "3 GAMMA -10");
        assertThat(Sort.of("change-desc")).contains(Sort.CHANGE_DESC);
        assertThat(Sort.of(null)).contains(Sort.DATE);
        assertThat(Sort.of("sideways")).isEmpty();

        // the list of one day
        assertThat(repository.report("IDX", TYPE, JAN3)).hasValueSatisfying(r -> {
            assertThat(r.reportDate()).isEqualTo(JAN3);
            assertThat(r.sells()).isEqualTo(1);
        });
        assertThat(repository.report("IDX", TYPE, JAN2)).isEmpty();
    }

    @Test
    void readsOneCompanyItsDigestAndTheStoredLists() {
        repository.store("IDX", TYPE, january3(), null, "peng-2001-01-03-00001-lima-persen.xlsx", null);
        repository.store("IDX", TYPE, january4(), null, "peng-2001-01-04-00002-lima-persen.xlsx", null);

        List<HolderDay> days = repository.holders("IDX", TYPE, "ZZOW", null, null);
        assertThat(days).hasSize(8);
        HolderDay alfa = days.stream().filter(d -> d.holder().reportDate().equals(JAN3)
                && d.holder().holderName().equals("PT ALFA")).findFirst().orElseThrow();
        assertThat(alfa.accounts()).extracting(OwnershipRepository.AccountRow::change).containsExactly(-100L, 100L);
        assertThat(days.stream().filter(d -> d.holder().leftList())).singleElement()
                .satisfies(d -> assertThat(d.accounts()).isEmpty());
        assertThat(repository.holders("IDX", TYPE, "ZZOW", JAN4, JAN4).size()).isEqualTo(4);

        Digest digest = OwnershipDigest.of("ZZOW", days);
        assertThat(digest.latestDate()).isEqualTo(JAN4);
        assertThat(digest.days()).isEqualTo(2);
        assertThat(digest.holders()).extracting(OwnershipDigest.HolderLine::name)
                .containsExactly("PT ALFA", "BETAZZ", "DELTAZZ");
        OwnershipDigest.HolderLine beta = digest.holders().get(1);
        assertThat(beta.status()).isEqualTo("FOREIGN");
        assertThat(beta.since()).isEqualTo(JAN2);
        assertThat(beta.netChange()).isEqualTo(50L);
        assertThat(digest.totals().holdersOwnership()).isEqualByComparingTo("0.82");
        assertThat(digest.totals().foreignOwnership()).isEqualByComparingTo("0.15");
        assertThat(digest.totals().buys()).isEqualTo(2);
        assertThat(digest.totals().sells()).isEqualTo(1);
        assertThat(digest.totals().entered()).isEqualTo(1);
        assertThat(digest.totals().exited()).isEqualTo(1);
        assertThat(digest.totals().accountMoveDays()).isEqualTo(1);
        assertThat(digest.formerHolders()).extracting(OwnershipDigest.FormerHolder::name).containsExactly("GAMMA");
        assertThat(digest.transactions()).hasSize(5);
        assertThat(digest.reading()).contains("3 investors held more than 5% of ZZOW", "82.00%", "foreign 15.00%",
                "2 buys, 1 sell, 1 crossed 5%, 1 fell below 5%");
        String text = OwnershipDigest.text(digest);
        assertThat(text).contains("BETAZZ, foreign, domicile SINGAPORE",
                "bought 50 shares since 2001-01-02", "2001-01-04: GAMMA fell below 5% (held 50 shares, 5.00%",
                "2001-01-04: DELTAZZ crossed 5% with 70 shares (7.00%)", "No longer listed (below 5%): GAMMA");

        List<ReportRow> reports = repository.reports("IDX", TYPE, 500, 0).reports().stream()
                .filter(r -> r.reportDate().getYear() == 2001).toList();
        assertThat(reports).extracting(ReportRow::reportDate).containsExactly(JAN4, JAN3);
        ReportRow jan4 = reports.getFirst();
        assertThat(jan4.announcementNumber()).isEqualTo("00002");
        assertThat(jan4.buys()).isEqualTo(1);
        assertThat(jan4.entered()).isEqualTo(1);
        assertThat(jan4.exited()).isEqualTo(1);
        assertThat(jan4.notes()).containsExactly("a note");
        assertThat(reports.get(1).exited()).isNull();
        assertThat(reports.get(1).aliases()).isEqualTo(1);
        assertThat(reports.get(1).sells()).isEqualTo(1);

        assertThat(repository.tickers("IDX", TYPE)).filteredOn(t -> t.ticker().equals("ZZOW")).singleElement()
                .satisfies(t -> {
                    assertThat(t.holders()).isEqualTo(3);
                    assertThat(t.days()).isEqualTo(2);
                    assertThat(t.firstDate()).isEqualTo(JAN3);
                });
        assertThat(repository.largest("IDX", TYPE, JAN4, "BUY", 5)).extracting(MovementRow::ticker).contains("ZZOW");
    }

    @Test
    void findsAnInvestorAcrossItsCompanies() {
        repository.store("IDX", TYPE, january3(), null, null, null);
        repository.store("IDX", TYPE, january4(), null, null, null);
        List<Entity> found = repository.entities("IDX", TYPE, "pt. betazz", 10);
        Entity beta = found.stream().filter(e -> e.holderKey().equals("BETAZZ")).findFirst().orElseThrow();
        assertThat(beta.status()).isEqualTo("A");
        assertThat(beta.holdings()).filteredOn(h -> h.ticker().equals("ZZOW")).singleElement().satisfies(h -> {
            assertThat(h.shares()).isEqualTo(150L);
            assertThat(h.lastDate()).isEqualTo(JAN4);
            assertThat(h.firstDate()).isEqualTo(JAN2);
            assertThat(h.firstShares()).isEqualTo(100L);
            assertThat(h.change()).isEqualTo(50L);
        });
        Entity delta = repository.entities("IDX", TYPE, "DELTAZZ", 10).stream()
                .filter(e -> e.holderKey().equals("DELTAZZ")).findFirst().orElseThrow();
        assertThat(delta.holdings().getFirst().firstDate()).isEqualTo(JAN4);
        assertThat(delta.holdings().getFirst().change()).isZero();
    }

    /**
     * Every list of data/IDX_OWNERSHIP_FIVE_PERCENTAGE (at least 19 - 27 October 2026; September's too when present),
     * stored and rolled back.
     */
    @Test
    void storesTheListsOfOctober2026() throws IOException {
        assumeTrue(Files.isDirectory(OwnershipParserTest.DIR), "ownership source data not available");
        List<Path> files;
        try (var list = Files.list(OwnershipParserTest.DIR)) {
            files = list.filter(f -> f.toString().endsWith(".xlsx")).sorted().toList();
        }
        for (String day : List.of("19", "20", "21", "22", "23", "26", "27")) {
            assumeTrue(files.stream().anyMatch(f -> f.getFileName().toString().startsWith("peng-2026-10-" + day)),
                    "the list of " + day + " October 2026 is not there");
        }
        for (Path file : files) {
            repository.store("IDX", TYPE, OwnershipParser.parse(Files.readAllBytes(file)), null,
                    file.getFileName().toString(), null);
        }
        LocalDate oct19 = LocalDate.of(2026, 10, 19);
        LocalDate oct23 = LocalDate.of(2026, 10, 23);
        LocalDate oct27 = LocalDate.of(2026, 10, 27);
        Filter exits = new Filter("IDX", TYPE, List.of(), null, Transaction.EXITED, null, oct19, oct27, false);
        List<MovementRow> exited = repository.movements(exits, 500, 0).rows();
        assertThat(exited).anyMatch(r -> r.reportDate().equals(oct27) && r.ticker().equals("TRUK")
                && r.holderName().equals("SUWITO") && r.sharesPrevious() != null);
        assertThat(exited).anyMatch(r -> r.reportDate().equals(oct23) && r.ticker().equals("BAJA")
                && r.holderName().equals("IBNU SUSANTO"));
        List<MovementRow> truk = repository.movements(new Filter("IDX", TYPE, List.of("TRUK"), null,
                Transaction.CROSSED, null, oct27, oct27, false), 500, 0).rows();
        assertThat(truk).extracting(MovementRow::movement).containsExactlyInAnyOrder("ENTERED", "EXITED");

        // the lists of the files (the folder may hold September's too), latest first
        java.util.Set<LocalDate> fileDates = new java.util.HashSet<>();
        files.forEach(f -> fileDates.add(LocalDate.parse(f.getFileName().toString().substring(5, 15))));
        List<ReportRow> reports = repository.reports("IDX", TYPE, 500, 0).reports().stream()
                .filter(r -> fileDates.contains(r.reportDate())).toList();
        assertThat(reports).hasSize(files.size());
        // the day before the first list is not stored: who fell below 5% that day is unknown, not "nobody"
        assertThat(reports.getLast().exited()).isNull();
        assertThat(exited).noneMatch(r -> r.reportDate().equals(reports.getLast().reportDate()));
        assertThat(reports.getFirst().exited()).isEqualTo(3);
        assertThat(reports.getFirst().entered()).isEqualTo(3);
        assertThat(reports.getFirst().announcementNumber()).isEqualTo("00104");

        // Lo Kheng Hong's companies
        Entity lo = repository.entities("IDX", TYPE, "lo kheng hong", 5).stream()
                .filter(e -> e.holderKey().equals("DRS LO KHENG HONG")).findFirst().orElseThrow();
        assertThat(lo.holdings()).extracting(OwnershipRepository.EntityHolding::ticker).contains("ABMM");

        Digest adro = OwnershipDigest.of("ADRO", repository.holders("IDX", TYPE, "ADRO", null, null));
        assertThat(adro.days()).isEqualTo(files.size());
        assertThat(adro.holders().getFirst().name()).isEqualTo("ADARO STRATEGIC INVESTMENTS");
        assertThat(adro.totals().accountMoveDays()).isPositive();
    }
}
