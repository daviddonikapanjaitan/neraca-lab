package com.neracalab.backend.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.company.CompanyDetailResponse;
import com.neracalab.backend.company.CompanyDetailResponse.Company;
import com.neracalab.backend.company.CompanyDetailResponse.IncomeStatement;
import com.neracalab.backend.company.CompanyDetailResponse.Metric;
import com.neracalab.backend.company.CompanyDetailResponse.Period;

/** Small company data for the analysis tests: one fiscal year 2025 with revenue 1,234.567 billion and EPS 85.5. */
final class TestFacts {

    private TestFacts() {
    }

    static CompanyDetailResponse detail() {
        return detail("IDR");
    }

    static CompanyDetailResponse detail(String currency) {
        Company company = new Company(1, "TEST", "IDX", "Indonesia Stock Exchange", null, "PT Test Tbk", null,
                "Industrials", null, "Indonesia", currency, null, null, true, null, null);
        IncomeStatement income = new IncomeStatement(new BigDecimal("1234567000000"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, new BigDecimal("85.5"), null, null, null);
        Period fy = new Period(1, "2025 FY", 2025, null, "FY", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null,
                null, true, income, null, null, List.of(),
                Map.of("roe_annualized", new Metric("PROFITABILITY", new BigDecimal("0.1834"), "ratio")));
        return new CompanyDetailResponse(company, null, List.of(fy), List.of(), List.of(), null, null, List.of(), List.of());
    }

    static Period period(long id, String name, int year, String type, LocalDate end) {
        return new Period(id, name, year, null, type, null, end, null, null, null, null, null, null, List.of(), Map.of());
    }
}
