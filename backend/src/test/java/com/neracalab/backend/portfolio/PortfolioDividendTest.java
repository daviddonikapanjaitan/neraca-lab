package com.neracalab.backend.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.profile.CompanyProfileViews.DividendRow;
import com.neracalab.backend.portfolio.PortfolioService.Dividend;
import com.neracalab.backend.portfolio.PortfolioViews.Position;
import com.neracalab.backend.portfolio.PortfolioViews.Slice;

/** The dividend column (this year, else last year) and the sector allocation of Portfolio &gt; Position. No database. */
class PortfolioDividendTest {

    private static DividendRow row(String ex, String perShare, String currency, String yahoo, String split) {
        BigDecimal amount = new BigDecimal(perShare);
        return new DividendRow(LocalDate.parse(ex), null, null, null, null, null, yahoo == null ? "IDX" : "IDX_YAHOO",
                amount, currency, yahoo == null ? null : new BigDecimal(yahoo), new BigDecimal(split), null, null,
                amount, null, null, null);
    }

    @Test
    void thisYearsDividendsAreSummed() {
        Dividend d = PortfolioService.dividend(List.of(row("2026-06-03", "9.5", "IDR", null, "1"),
                row("2026-08-10", "2.5", "IDR", null, "1"), row("2025-07-09", "9", "IDR", null, "1")), 2026);
        assertThat(d.perShare()).isEqualTo(12);
        assertThat(d.year()).isEqualTo(2026);
        assertThat(d.notes()).isEmpty();
    }

    @Test
    void elseLastYears() {
        Dividend d = PortfolioService.dividend(List.of(row("2025-06-23", "21", "IDR", null, "1"),
                row("2024-05-06", "15", "IDR", null, "1")), 2026);
        assertThat(d.perShare()).isEqualTo(21);
        assertThat(d.year()).isEqualTo(2025);
        assertThat(PortfolioService.dividend(List.of(row("2024-05-06", "15", "IDR", null, "1")), 2026)).isNull();
    }

    @Test
    void onTodaysShareBasisAfterASplit() {
        // Rp 100 before a 1:5 split is Rp 20 a share today
        Dividend d = PortfolioService.dividend(List.of(row("2026-03-02", "100", "IDR", null, "5")), 2026);
        assertThat(d.perShare()).isEqualTo(20);
    }

    @Test
    void anIdxAmountFarOffYahoosTakesYahoos() {
        // INDY 2026-06-03: IDX's "USD 10" (Rp 10) converted to Rp 178,350; Yahoo Rp 10.2488
        DividendRow indy = new DividendRow(LocalDate.parse("2026-06-03"), LocalDate.parse("2026-06-02"), null, null,
                2025, "dt", "IDX_YAHOO", new BigDecimal("10"), "USD", new BigDecimal("10.2488"), BigDecimal.ONE, null,
                null, new BigDecimal("178350"), null, null, null);
        Dividend d = PortfolioService.dividend(List.of(indy), 2026);
        assertThat(d.perShare()).isEqualTo(10.2488);
        assertThat(d.notes()).singleElement().asString().contains("Yahoo's amount is used");
        // close amounts keep IDX's
        Dividend close = PortfolioService.dividend(List.of(row("2026-07-02", "800", "IDR", "780", "1")), 2026);
        assertThat(close.perShare()).isEqualTo(800);
        assertThat(close.notes()).isEmpty();
    }

    @Test
    void theAllocationAddsUpToTheWholeWithTheCashLast() {
        List<Slice> slices = PortfolioService.allocation(List.of(position("HRTA", "Consumer Cyclicals", 120_094_000.0),
                position("LSIP", "Consumer Non-Cyclicals", 10_032_000.0),
                position("CEKA", "Consumer Non-Cyclicals", 5_720_000.0), position("XXXX", null, null)), 4_852_232);
        assertThat(slices).extracting(Slice::name)
                .containsExactly("Consumer Cyclicals", "Consumer Non-Cyclicals", "Cash");
        assertThat(slices.get(1).value()).isEqualTo(15_752_000.0);
        assertThat(slices.get(1).tickers()).containsExactly("LSIP", "CEKA");
        assertThat(slices.stream().mapToDouble(Slice::share).sum()).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(PortfolioService.allocation(List.of(), 0)).isEmpty();
    }

    private static Position position(String ticker, String sector, Double marketValue) {
        return new Position(1, "IDX", ticker, ticker, sector, null, null, null, null, null, null, null, null, 1, 1, 1,
                marketValue, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, List.of());
    }
}
