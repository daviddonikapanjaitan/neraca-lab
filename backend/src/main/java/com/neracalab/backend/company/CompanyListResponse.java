package com.neracalab.backend.company;

import java.time.LocalDate;
import java.util.List;

/**
 * Companies of one exchange ({@code GET /api/v1/companies?exchange=IDX}), ordered by ticker.
 *
 * @param exchange     exchange code, e.g. IDX
 * @param exchangeName e.g. Indonesia Stock Exchange
 * @param count        number of companies
 */
public record CompanyListResponse(String exchange, String exchangeName, int count, List<CompanySummary> companies) {

    /**
     * One company with a short overview of the data stored for it.
     *
     * @param periodCount     number of reporting periods stored
     * @param firstPeriodEnd  end date of the oldest reporting period
     * @param latestPeriod    most recent reporting period, e.g. "2026 H1" (longest period when several end on the same day)
     * @param latestPeriodEnd end date of {@code latestPeriod}
     * @param latestPriceDate most recent trading day in price_daily
     */
    public record CompanySummary(long companyId, String ticker, String exchange, String companyName,
                                 String legalName, String sector, String industry, String country,
                                 String currency, LocalDate fiscalYearEnd, boolean active,
                                 long periodCount, LocalDate firstPeriodEnd, String latestPeriod,
                                 LocalDate latestPeriodEnd, LocalDate latestPriceDate) {
    }
}
