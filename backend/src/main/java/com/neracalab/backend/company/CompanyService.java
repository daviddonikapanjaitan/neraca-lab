package com.neracalab.backend.company;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.CompanyDetailResponse.Company;
import com.neracalab.backend.company.CompanyDetailResponse.Metric;
import com.neracalab.backend.company.CompanyDetailResponse.Period;
import com.neracalab.backend.company.CompanyDetailResponse.SegmentFigures;
import com.neracalab.backend.company.CompanyListResponse.CompanySummary;
import com.neracalab.backend.company.CompanyQueryRepository.PeriodMetric;
import com.neracalab.backend.company.CompanyQueryRepository.PeriodRow;
import com.neracalab.backend.company.CompanyQueryRepository.PeriodSegment;

/**
 * Company list and company detail. Each call runs in one REPEATABLE READ read-only transaction,
 * so all its queries see the same snapshot even while an upload is writing.
 */
@Service
public class CompanyService {

    private final CompanyQueryRepository repository;

    public CompanyService(CompanyQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyListResponse list(String exchangeCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        List<CompanySummary> companies = repository.companies(exchange.code());
        return new CompanyListResponse(exchange.code(), exchange.displayName(), companies.size(), companies);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyDetailResponse detail(String exchangeCode, String tickerCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        String ticker = Tickers.normalize(tickerCode);
        Company company = repository.company(exchange, ticker)
                .orElseThrow(() -> new CompanyNotFoundException(exchange.code(), ticker));
        long companyId = company.companyId();

        Map<Long, List<SegmentFigures>> segmentsByPeriod = new LinkedHashMap<>();
        for (PeriodSegment s : repository.segmentFigures(companyId)) {
            segmentsByPeriod.computeIfAbsent(s.periodId(), id -> new ArrayList<>()).add(s.figures());
        }
        Map<Long, Map<String, Metric>> metricsByPeriod = new LinkedHashMap<>();
        for (PeriodMetric m : repository.periodMetrics(companyId)) {
            metricsByPeriod.computeIfAbsent(m.periodId(), id -> new LinkedHashMap<>()).put(m.name(), m.metric());
        }
        List<Period> periods = repository.periods(companyId).stream()
                .map(p -> period(p, segmentsByPeriod.getOrDefault(p.periodId(), List.of()),
                        metricsByPeriod.getOrDefault(p.periodId(), Map.of())))
                .toList();

        return new CompanyDetailResponse(company, repository.coverage(companyId), periods,
                repository.segments(companyId), repository.shareSnapshots(companyId),
                repository.latestPrice(companyId).orElse(null),
                repository.latestMarketSnapshot(companyId).orElse(null),
                repository.valuations(companyId), repository.corporateActions(companyId));
    }

    private static Period period(PeriodRow p, List<SegmentFigures> segments, Map<String, Metric> metrics) {
        return new Period(p.periodId(), p.period(), p.fiscalYear(), p.fiscalQuarter(), p.periodType(),
                p.periodStart(), p.periodEnd(), p.filingDate(), p.sourceFiling(), p.audited(),
                p.incomeStatement(), p.balanceSheet(), p.cashFlowStatement(), segments, metrics);
    }

    /** No company with this ticker on this exchange. */
    public static class CompanyNotFoundException extends RuntimeException {

        public CompanyNotFoundException(String exchange, String ticker) {
            super("No company " + ticker + " on exchange " + exchange);
        }
    }
}
