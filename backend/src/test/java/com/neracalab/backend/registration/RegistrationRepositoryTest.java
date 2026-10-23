package com.neracalab.backend.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.registration.RegistrationParser.Insider;
import com.neracalab.backend.registration.RegistrationParser.Report;
import com.neracalab.backend.registration.RegistrationRepository.Filter;
import com.neracalab.backend.registration.RegistrationRepository.InsiderRow;
import com.neracalab.backend.registration.RegistrationRepository.Outcome;
import com.neracalab.backend.registration.RegistrationRepository.Stored;
import com.neracalab.backend.registration.RegistrationRepository.Transaction;

/** The registration reports of a company of its own (ZZRG), in the real database, rolled back. */
@SpringBootTest
@Transactional
class RegistrationRepositoryTest {

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private long companyId;

    @BeforeEach
    void company() {
        companyId = jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, currency, active)
                        VALUES ('ZZRG', 'IDX', 'PT Uji Registrasi Tbk', 'IDR', true) RETURNING company_id""")
                .query(Long.class).single();
    }

    private static Insider insider(int row, String name, String position, long previous, long current, boolean affiliate) {
        return new Insider(row, name, position, InsiderRole.ofPosition(position), previous,
                BigDecimal.valueOf(previous).movePointLeft(7), current, BigDecimal.valueOf(current).movePointLeft(7),
                false, affiliate);
    }

    private static Report report(YearMonth period, LocalDateTime filedAt, boolean correction, List<Insider> insiders) {
        return new Report("ZZRG", "PT Uji Registrasi Tbk", "001/ZZRG/" + period, "Laporan Bulanan Registrasi Pemegang Efek",
                correction, period, filedAt, "PT. Biro Uji", 1_000_000_000L, 1_000_000_000L, insiders, List.of());
    }

    private List<InsiderRow> rows(Transaction transaction, InsiderRole role, YearMonth from, YearMonth to) {
        return repository.insiders(new Filter("IDX", List.of("ZZRG"), transaction, role, from, to), 500, 0).rows();
    }

    @Test
    void storesTheMonthsAndFiltersBuysSellsAndTitles() {
        YearMonth may = YearMonth.of(2026, 5);
        YearMonth june = YearMonth.of(2026, 6);
        assertThat(repository.store(companyId, report(may, LocalDateTime.of(2026, 6, 5, 19, 17), false, List.of(
                insider(1, "BUDI", "Direksi", 1_000_000, 1_000_000, false),
                insider(2, "SITI", "Komisaris", 500_000, 500_000, true))), null, null, "mei.pdf", null).outcome())
                .isEqualTo(Outcome.STORED);
        repository.store(companyId, report(june, LocalDateTime.of(2026, 7, 6, 10, 0), false, List.of(
                insider(1, "BUDI", "Direksi", 1_000_000, 1_250_000, false),
                insider(2, "SITI", "Komisaris", 500_000, 400_000, true))), null, null, "juni.pdf", null);

        List<InsiderRow> all = rows(Transaction.ALL, null, null, null);
        // latest month first, then in the order of the report
        assertThat(all).extracting(r -> r.period() + " " + r.holderName() + " " + r.change())
                .containsExactly("2026-06 BUDI 250000", "2026-06 SITI -100000", "2026-05 BUDI 0", "2026-05 SITI 0");
        assertThat(all.getFirst()).satisfies(r -> {
            assertThat(r.ticker()).isEqualTo("ZZRG");
            assertThat(r.companyName()).isEqualTo("PT Uji Registrasi Tbk");
            assertThat(r.role()).isEqualTo(InsiderRole.DIRECTOR);
            assertThat(r.sharesPrevious()).isEqualTo(1_000_000L);
            assertThat(r.sharesCurrent()).isEqualTo(1_250_000L);
            assertThat(r.percentCurrent()).isEqualByComparingTo("0.125");
            assertThat(r.fileName()).isEqualTo("juni.pdf");
            assertThat(r.filedAt()).isEqualTo(Instant.parse("2026-07-06T03:00:00Z"));    // 10:00 Jakarta
        });
        assertThat(all.get(1).controllerAffiliate()).isTrue();

        assertThat(rows(Transaction.BUY, null, null, null)).extracting(InsiderRow::holderName).containsExactly("BUDI");
        assertThat(rows(Transaction.SELL, null, null, null)).extracting(InsiderRow::holderName).containsExactly("SITI");
        assertThat(rows(Transaction.CHANGED, null, null, null)).hasSize(2);
        assertThat(rows(Transaction.ALL, InsiderRole.COMMISSIONER, null, null)).extracting(InsiderRow::holderName)
                .containsOnly("SITI");
        assertThat(rows(Transaction.ALL, null, may, may)).extracting(InsiderRow::period).containsOnly("2026-05");
        assertThat(rows(Transaction.ALL, null, june, null)).extracting(InsiderRow::period).containsOnly("2026-06");
        assertThat(repository.insiders(new Filter("IDX", List.of("ZZRG"), Transaction.ALL, null, null, null), 1, 1))
                .satisfies(p -> {
                    assertThat(p.total()).isEqualTo(4);
                    assertThat(p.rows()).singleElement().satisfies(r -> assertThat(r.holderName()).isEqualTo("SITI"));
                });

        assertThat(repository.companies()).anySatisfy(c -> {
            assertThat(c.ticker()).isEqualTo("ZZRG");
            assertThat(c.reports()).isEqualTo(2);
            assertThat(c.firstPeriod()).isEqualTo("2026-05");
            assertThat(c.lastPeriod()).isEqualTo("2026-06");
        });
        assertThat(repository.reports("IDX", "ZZRG")).extracting(RegistrationRepository.ReportRow::period)
                .containsExactly("2026-06", "2026-05");
    }

    /** A correction filed later replaces the report of the month; the report it corrects, uploaded after it, does not. */
    @Test
    void theLaterFilingOfAMonthStays() {
        YearMonth june = YearMonth.of(2026, 6);
        LocalDateTime original = LocalDateTime.of(2026, 7, 7, 9, 0);
        LocalDateTime corrected = LocalDateTime.of(2026, 8, 10, 15, 14);
        repository.store(companyId, report(june, original, false,
                List.of(insider(1, "BUDI", "Direksi", 1_000_000, 900_000, false))), null, null, "juni.pdf", null);

        Stored correction = repository.store(companyId, report(june, corrected, true,
                List.of(insider(1, "BUDI", "Direksi", 1_000_000, 1_000_000, false))), null, null, "juni-koreksi.pdf", null);
        assertThat(correction.outcome()).isEqualTo(Outcome.REPLACED);

        Stored again = repository.store(companyId, report(june, original, false,
                List.of(insider(1, "BUDI", "Direksi", 1_000_000, 900_000, false))), null, null, "juni.pdf", null);
        assertThat(again.outcome()).isEqualTo(Outcome.KEPT_LATER_FILING);
        assertThat(again.reportId()).isEqualTo(correction.reportId());
        assertThat(again.keptFiledAt()).isEqualTo(Instant.parse("2026-08-10T08:14:00Z"));

        List<InsiderRow> rows = rows(Transaction.ALL, null, null, null);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.correction()).isTrue();
            assertThat(r.change()).isZero();
            assertThat(r.fileName()).isEqualTo("juni-koreksi.pdf");
        });

        // the same file again (the same filing time) replaces it
        assertThat(repository.store(companyId, report(june, corrected, true,
                List.of(insider(1, "BUDI", "Direksi", 1_000_000, 1_000_000, false))), null, null, "juni-koreksi.pdf",
                null).outcome()).isEqualTo(Outcome.REPLACED);
        assertThat(jdbc.sql("SELECT count(*) FROM registration_report WHERE company_id = :c").param("c", companyId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anEmptyCellHasNoChange() {
        Insider newcomer = new Insider(1, "DEWI", "Komisaris Independen", InsiderRole.COMMISSIONER, null, null,
                100_000L, new BigDecimal("0.01"), false, false);
        repository.store(companyId, report(YearMonth.of(2026, 9), null, false, List.of(newcomer)), null, null, null, null);
        assertThat(rows(Transaction.ALL, null, null, null)).singleElement().satisfies(r -> {
            assertThat(r.sharesPrevious()).isNull();
            assertThat(r.change()).isNull();
            assertThat(r.position()).isEqualTo("Komisaris Independen");
        });
        assertThat(rows(Transaction.CHANGED, null, null, null)).isEmpty();
    }
}
