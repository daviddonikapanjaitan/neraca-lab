package com.neracalab.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Comparator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.CompanyDetailResponse.Period;
import com.neracalab.backend.company.CompanyListResponse.CompanySummary;

import tools.jackson.databind.json.JsonMapper;

/**
 * Company APIs against the Docker Postgres with the HRTA seed data (V1.0.4 / V1.0.5 / V1.0.6),
 * which is loaded on every start.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyControllerTest {

    @Autowired
    private WebApplicationContext context;

    /** Sends the bearer token of a root session with every request. */
    private MockMvc mvc;
    private String token;

    @BeforeEach
    void loginAsRoot() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    // ------------------------------------------------------------------ exchanges

    @Test
    void listsSupportedExchanges() throws Exception {
        mvc.perform(get("/api/v1/exchanges"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(Exchange.values().length))
                .andExpect(jsonPath("$[0].code").value("IDX"))
                .andExpect(jsonPath("$[0].name").value("Indonesia Stock Exchange"))
                .andExpect(jsonPath("$[0].country").value("Indonesia"));
    }

    // ------------------------------------------------------------------ list

    @Test
    void listsCompaniesOfAnExchangeOrderedByTicker() throws Exception {
        CompanyListResponse list = getJson("/api/v1/companies?exchange=IDX", CompanyListResponse.class);

        assertThat(list.exchange()).isEqualTo("IDX");
        assertThat(list.exchangeName()).isEqualTo("Indonesia Stock Exchange");
        assertThat(list.count()).isEqualTo(list.companies().size());
        assertThat(list.companies()).extracting(CompanySummary::exchange).containsOnly("IDX");
        assertThat(list.companies()).extracting(CompanySummary::ticker)
                .doesNotHaveDuplicates()
                .isSortedAccordingTo(Comparator.naturalOrder());

        CompanySummary hrta = list.companies().stream().filter(c -> c.ticker().equals("HRTA")).findFirst().orElseThrow();
        assertThat(hrta.companyName()).isEqualTo("Hartadinata Abadi");
        assertThat(hrta.legalName()).isEqualTo("PT Hartadinata Abadi Tbk");
        assertThat(hrta.currency()).isEqualTo("IDR");
        assertThat(hrta.periodCount()).isGreaterThanOrEqualTo(10);
        // the seed starts at 2024-03-31; ingested annual filings may add earlier years
        assertThat(hrta.firstPeriodEnd()).isBeforeOrEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(hrta.latestPeriodEnd()).isAfterOrEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(hrta.latestPeriod()).isNotBlank();
        assertThat(hrta.latestPriceDate()).isAfterOrEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void exchangeDefaultsToIdxAndIsCaseInsensitive() throws Exception {
        CompanyListResponse byDefault = getJson("/api/v1/companies", CompanyListResponse.class);
        CompanyListResponse lowerCase = getJson("/api/v1/companies?exchange=idx", CompanyListResponse.class);

        assertThat(byDefault.exchange()).isEqualTo("IDX");
        assertThat(lowerCase.exchange()).isEqualTo("IDX");
        assertThat(lowerCase.companies()).extracting(CompanySummary::ticker)
                .isEqualTo(byDefault.companies().stream().map(CompanySummary::ticker).toList());
    }

    @Test
    void unsupportedExchangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/companies").param("exchange", "NYSE"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unsupported exchange"))
                .andExpect(jsonPath("$.supportedExchanges[0]").value("IDX"));
    }

    // ------------------------------------------------------------------ detail

    @Test
    void returnsEverythingStoredForACompany() throws Exception {
        CompanyDetailResponse detail = getJson("/api/v1/companies/IDX/HRTA", CompanyDetailResponse.class);

        assertThat(detail.company().ticker()).isEqualTo("HRTA");
        assertThat(detail.company().exchange()).isEqualTo("IDX");
        assertThat(detail.company().exchangeName()).isEqualTo("Indonesia Stock Exchange");
        assertThat(detail.company().companyName()).isEqualTo("Hartadinata Abadi");

        // periods: most recent first, the longest first on equal end dates
        assertThat(detail.periods()).hasSize((int) detail.coverage().periods());
        assertThat(detail.periods()).extracting(Period::periodEnd).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(detail.periods()).extracting(Period::period)
                .contains("2026 H1", "2026 Q1", "2025 FY", "2025 9M", "2025 H1", "2025 Q1",
                        "2024 FY", "2024 9M", "2024 H1", "2024 Q1")
                .doesNotHaveDuplicates();

        Period h1 = period(detail, "2026 H1");
        assertThat(h1.periodType()).isEqualTo("H1");
        assertThat(h1.fiscalQuarter()).isEqualTo(2);
        assertThat(h1.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(h1.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(h1.audited()).isFalse();
        assertThat(h1.incomeStatement().revenue()).isEqualByComparingTo("33806129682661");
        assertThat(h1.balanceSheet().totalAssets()).isEqualByComparingTo("11350876052356");
        assertThat(h1.balanceSheet().totalAssets())
                .isEqualByComparingTo(h1.balanceSheet().totalLiabilities().add(h1.balanceSheet().totalEquity()));
        assertThat(h1.cashFlowStatement().endingCash()).isEqualByComparingTo("983698277704");
        assertThat(h1.segments()).hasSize(5);
        assertThat(h1.segments().stream().map(CompanyDetailResponse.SegmentFigures::revenue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(h1.incomeStatement().revenue());
        assertThat(h1.metrics()).containsKeys("gross_margin", "current_ratio")
                .doesNotContainKeys("pe_ratio", "ev_op");
        assertThat(h1.metrics().get("gross_margin").category()).isEqualTo("PROFITABILITY");
        assertThat(h1.metrics().get("gross_margin").unit()).isEqualTo("ratio");

        Period fy2025 = period(detail, "2025 FY");
        assertThat(fy2025.audited()).isTrue();
        assertThat(fy2025.fiscalQuarter()).isNull();
        assertThat(fy2025.incomeStatement().revenue()).isEqualByComparingTo("44548424151152");

        // interim comparative of 2024: no balance sheet stored, statement is null (not an empty object)
        Period nineMonths2024 = period(detail, "2024 9M");
        assertThat(nineMonths2024.balanceSheet()).isNull();
        assertThat(nineMonths2024.incomeStatement().revenue()).isEqualByComparingTo("13290380945734");
        assertThat(nineMonths2024.cashFlowStatement().endingCash()).isEqualByComparingTo("203510814822");

        assertThat(detail.segments()).hasSize((int) detail.coverage().segments()).isNotEmpty();
        assertThat(detail.shareSnapshots()).hasSize((int) detail.coverage().shareSnapshots()).isNotEmpty();
        assertThat(detail.valuations()).hasSize((int) detail.coverage().valuationSnapshots()).isNotEmpty();
        assertThat(detail.valuations()).allSatisfy(v -> assertThat(v.period()).isNotBlank());
        assertThat(detail.corporateActions()).hasSize((int) detail.coverage().corporateActions());

        // 2024-01-02 or earlier: a full-history price ingestion reaches back further (2017-06-22)
        assertThat(detail.coverage().firstPriceDate()).isBeforeOrEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(detail.coverage().priceDays()).isGreaterThanOrEqualTo(649);
        assertThat(detail.latestPrice().tradingDate()).isEqualTo(detail.coverage().latestPriceDate());
        assertThat(detail.latestMarketSnapshot().marketCap()).isPositive();
        assertThat(detail.coverage().incomeStatements()).isEqualTo(
                detail.periods().stream().filter(p -> p.incomeStatement() != null).count());
        assertThat(detail.coverage().balanceSheets()).isEqualTo(
                detail.periods().stream().filter(p -> p.balanceSheet() != null).count());
    }

    /**
     * A company reporting in USD whose listing trades in IDR (CUAN, DSSA, INDY) has its prices stored converted; the
     * price as quoted on IDX comes with them: stored price x the rate of the last FX day before the day, to 2
     * decimals (CUAN 2026-10-16: USD 0.03009362 x 17,944 = Rp 539.9999 -> 540.00). Rolled back.
     */
    @Test
    @Transactional
    void pricesOfAUsdReporterComeWithTheirIdrQuote() throws Exception {
        long id = jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, currency, active)
                        VALUES ('ZZUS', 'IDX', 'USD Reporter Test', 'USD', true) RETURNING company_id""")
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO fx_rate_daily (base_currency, quote_currency, rate_date, rate, source)
                        VALUES ('USD', 'IDR', DATE '2098-01-02', 17944, 'test'), ('USD', 'IDR', DATE '2098-01-05', 18000, 'test')""")
                .update();
        jdbc.sql("""
                        INSERT INTO price_daily (company_id, trading_date, open_price, high_price, low_price, close_price,
                                                 adjusted_close, volume)
                        VALUES (:id, DATE '2098-01-05', 0.03065195, 0.03093061, 0.02980597, 0.03009362, 0.03009362, 1000)""")
                .param("id", id).update();
        jdbc.sql("""
                        INSERT INTO market_snapshot (company_id, snapshot_date, share_price, shares_outstanding, market_cap)
                        VALUES (:id, DATE '2098-01-05', 0.03009362, 112399199400, 3382498795.05)""")
                .param("id", id).update();

        CompanyDetailResponse detail = getJson("/api/v1/companies/IDX/ZZUS", CompanyDetailResponse.class);

        // the rate of 2098-01-02 (the last FX day before the trading day), not the one of the day itself
        assertThat(detail.latestPrice().closePrice()).isEqualByComparingTo("0.03009362");
        assertThat(detail.latestPrice().quote()).satisfies(q -> {
            assertThat(q.currency()).isEqualTo("IDR");
            assertThat(q.closePrice()).isEqualByComparingTo("540.00");
            assertThat(q.openPrice()).isEqualByComparingTo("550.02");
            assertThat(q.fxRate()).isEqualByComparingTo("17944");
            assertThat(q.fxDate()).isEqualTo(LocalDate.parse("2098-01-02"));
        });
        assertThat(detail.latestMarketSnapshot().quoteCurrency()).isEqualTo("IDR");
        assertThat(detail.latestMarketSnapshot().quotePrice()).isEqualByComparingTo("540.00");

        // a company reporting in the listing currency has no separate quote
        CompanyDetailResponse hrta = getJson("/api/v1/companies/IDX/HRTA", CompanyDetailResponse.class);
        assertThat(hrta.latestPrice().quote()).isNull();
        assertThat(hrta.latestMarketSnapshot().quotePrice()).isNull();
        assertThat(hrta.valuations()).allSatisfy(v -> assertThat(v.quotePrice()).isNull());
    }

    @Test
    void exchangeAndTickerAreCaseInsensitive() throws Exception {
        CompanyDetailResponse detail = getJson("/api/v1/companies/idx/hrta", CompanyDetailResponse.class);
        assertThat(detail.company().ticker()).isEqualTo("HRTA");
        assertThat(detail.company().exchange()).isEqualTo("IDX");
    }

    @Test
    void unknownCompanyIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/companies/IDX/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Company not found"))
                .andExpect(jsonPath("$.detail").value("No company ZZZZ on exchange IDX"));
    }

    @Test
    void invalidTickerAndExchangeAreRejected() throws Exception {
        mvc.perform(get("/api/v1/companies/IDX/{ticker}", "HR TA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid ticker"));
        mvc.perform(get("/api/v1/companies/NASDAQ/AAPL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
    }

    private <T> T getJson(String url, Class<T> type) throws Exception {
        String body = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, type);
    }

    private static Period period(CompanyDetailResponse detail, String key) {
        return detail.periods().stream().filter(p -> p.period().equals(key)).findFirst().orElseThrow();
    }
}
