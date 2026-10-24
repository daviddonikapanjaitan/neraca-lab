package com.neracalab.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.registration.InsiderRole;
import com.neracalab.backend.registration.RegistrationRepository.InsiderRow;
import com.neracalab.backend.registration.RegistrationRepository.ReportRow;

/** The insider section of the analysis dossier, from the stored monthly registration reports (no model). */
class InsiderOwnershipTest {

    private static final long LISTED = 1_000_000_000L;

    private static ReportRow report(String period) {
        return new ReportRow(1, "IDX", "ZZRG", period, "001/" + period, "Laporan Bulanan Registrasi Pemegang Efek",
                false, null, "PT. Biro Uji", LISTED, LISTED, 2, List.of(), null, null, null);
    }

    private static InsiderRow row(String period, String name, InsiderRole role, Long previous, long current,
                                  boolean affiliate) {
        return new InsiderRow(1, 1, "IDX", "ZZRG", "PT Uji", period, name, role == InsiderRole.DIRECTOR ? "Direksi"
                : "Komisaris", role, previous, null, current, BigDecimal.valueOf(current).movePointLeft(7),
                previous == null ? null : current - previous, false, affiliate, false, null, null, null, null);
    }

    @Test
    void noReportsMeansNoSection() {
        assertThat(InsiderOwnership.of(List.of(), List.of())).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void readsHoldingsBuysSellsAndGaps() {
        // May, June and August stored (July missing); latest first as the repository lists them
        List<ReportRow> reports = List.of(report("2026-08"), report("2026-06"), report("2026-05"));
        List<InsiderRow> rows = List.of(
                row("2026-08", "BUDI", InsiderRole.DIRECTOR, 1_200_000L, 1_500_000, false),
                row("2026-08", "SITI", InsiderRole.COMMISSIONER, 500_000L, 400_000, true),
                row("2026-06", "BUDI", InsiderRole.DIRECTOR, 1_000_000L, 1_200_000, false),
                row("2026-06", "SITI", InsiderRole.COMMISSIONER, 500_000L, 500_000, true),
                row("2026-05", "BUDI", InsiderRole.DIRECTOR, 1_000_000L, 1_000_000, false),
                row("2026-05", "ANTON", InsiderRole.DIRECTOR, 300_000L, 300_000, false));

        Map<String, Object> s = InsiderOwnership.of(reports, rows);

        assertThat(s.get("months")).isEqualTo(List.of("2026-05", "2026-06", "2026-08"));
        assertThat(s.get("latestMonth")).isEqualTo("2026-08");
        assertThat(s.get("missingMonths")).isEqualTo(List.of("2026-07"));
        assertThat(s.get("notInLatestReport")).isEqualTo(List.of("ANTON"));
        List<Map<String, Object>> insiders = (List<Map<String, Object>>) s.get("insiders");
        assertThat(insiders).extracting(i -> i.get("name")).containsExactly("BUDI", "SITI");
        assertThat(insiders.getFirst()).containsEntry("shares", 1_500_000L).containsEntry("ownership", 0.0015)
                .containsEntry("changeLatestMonth", 300_000L).containsEntry("netChange", 500_000L);
        assertThat(insiders.get(1)).containsEntry("controllerAffiliate", true).containsEntry("netChange", -100_000L);
        Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(totals).containsEntry("insiders", 2).containsEntry("shares", 1_900_000L)
                .containsEntry("ownership", 0.0019).containsEntry("sharesBought", 500_000L)
                .containsEntry("sharesSold", 100_000L).containsEntry("netChange", 400_000L)
                .containsEntry("buys", 2).containsEntry("sells", 1);
        // latest first, as the rows came
        assertThat((List<Map<String, Object>>) s.get("transactions")).extracting(t -> t.get("month") + " " + t.get("name")
                + " " + t.get("change")).containsExactly("2026-08 BUDI 300000", "2026-08 SITI -100000", "2026-06 BUDI 200000");
        assertThat(s.get("reading")).isEqualTo("Directors and commissioners bought 500,000 shares (2 purchases) and sold "
                + "100,000 shares (1 sale) in May 2026 - Aug 2026, net +400,000; 2 hold 1,900,000 shares (0.19% of the "
                + "listed shares).");
    }

    @Test
    void noDealingsReadsAsNoChange() {
        // HRTA's four insiders, May - August 2026: every change 0
        List<ReportRow> reports = new ArrayList<>();
        List<InsiderRow> rows = new ArrayList<>();
        for (String m : List.of("2026-08", "2026-07", "2026-06", "2026-05")) {
            reports.add(report(m));
            rows.add(row(m, "CUNCUN MULIAWAN", InsiderRole.DIRECTOR, 2_157_500L, 2_157_500, false));
            rows.add(row(m, "FENDY WIJAYA", InsiderRole.COMMISSIONER, 17_567_800L, 17_567_800, true));
        }
        Map<String, Object> s = InsiderOwnership.of(reports, rows);
        assertThat(s).doesNotContainKey("missingMonths").doesNotContainKey("notInLatestReport");
        assertThat((List<?>) s.get("transactions")).isEmpty();
        assertThat(s.get("reading")).isEqualTo("No director or commissioner bought or sold shares in May 2026 - Aug 2026; "
                + "2 hold 19,725,300 shares (1.97% of the listed shares).");
    }
}
