package com.neracalab.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

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
        assertThat(hrta.firstPeriodEnd()).isEqualTo(LocalDate.of(2024, 3, 31));
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

        assertThat(detail.coverage().firstPriceDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(detail.coverage().priceDays()).isGreaterThanOrEqualTo(649);
        assertThat(detail.latestPrice().tradingDate()).isEqualTo(detail.coverage().latestPriceDate());
        assertThat(detail.latestMarketSnapshot().marketCap()).isPositive();
        assertThat(detail.coverage().incomeStatements()).isEqualTo(
                detail.periods().stream().filter(p -> p.incomeStatement() != null).count());
        assertThat(detail.coverage().balanceSheets()).isEqualTo(
                detail.periods().stream().filter(p -> p.balanceSheet() != null).count());
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
