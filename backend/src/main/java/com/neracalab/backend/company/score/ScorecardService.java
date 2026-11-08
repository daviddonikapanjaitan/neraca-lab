package com.neracalab.backend.company.score;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.Assessment;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.quant.CombinedProfile;
import com.neracalab.backend.screening.quant.CombinedProfile.MetricSource;
import com.neracalab.backend.screening.quant.QuantScorer;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;
import com.neracalab.backend.screening.quant.QuantScorer.CriterionInfo;
import com.neracalab.backend.screening.quant.QuantScorer.Part;
import com.neracalab.backend.screening.quant.StockProfile;

/**
 * The Quant Score tab of a company: the six fundamental agents' scorecards ({@link QuantScorer}: Buffett, Munger,
 * Lynch, Fisher, Keith Gill, Risk) on the metrics of {@link CombinedProfile} (Neraca Lab's filings, valuation and
 * prices, with Yahoo Finance's screening snapshot where those lack a metric). Plain rules: no model call, nothing
 * stored, computed on every request.
 * <p>
 * Overall: {@link QuantScorer#overall} of the agents with at least {@code min-fundamentals-coverage} of their
 * scorecard known (as Screening > Analysis takes a quantitative score), the Risk agent weighted {@code risk-weight}.
 */
@Service
public class ScorecardService {

    private final ScorecardRepository repository;
    private final FundamentalRepository fundamentals;
    private final ScreeningProperties screening;

    public ScorecardService(ScorecardRepository repository, FundamentalRepository fundamentals,
                            ScreeningProperties screening) {
        this.repository = repository;
        this.fundamentals = fundamentals;
        this.screening = screening;
    }

    /**
     * @param financial    scored as a bank / insurer / financial company (capital and ROA criteria)
     * @param overall      null when no agent has enough of its scorecard known
     * @param verdict      band of the overall score (STRONG_FIT >= 80, FIT >= 65, NEUTRAL >= 45, WEAK >= 30, REJECT)
     * @param minCoverage  scorecards with less of their weight known are shown but not in the overall
     * @param sources      every metric known, with its value and source
     */
    public record Scorecards(String exchange, String ticker, String companyName, boolean financial, Double overall,
                             String verdict, double riskWeight, double minCoverage, LocalDate screeningDate,
                             LocalDate valuationDate, String fiscalYear, LocalDate pricesDate, List<AgentCard> agents,
                             List<MetricView> metrics, List<String> notes) {
    }

    /**
     * @param score       0 - 100 (Risk: higher = safer); null when no metric of the scorecard is known
     * @param coverage    share of the applicable criteria weight with a known metric
     * @param inOverall   whether the score counts in the overall
     */
    public record AgentCard(String agent, String label, String focus, Double score, double coverage, String verdict,
                            boolean inOverall, List<CriterionView> criteria) {
    }

    /**
     * One criterion: the metric against its bounds (poor = 0 points, good = full points; poor above good when lower
     * is better).
     *
     * @param unit    percent (fraction shown as %), points (a change in percentage points), x (a multiple), log10
     *                (of IDR traded per day) or number
     * @param applies false: not applicable to this company (an industrial ratio of a bank, a bank criterion of an
     *                industrial company), left out of the score
     * @param points  0 - 1 of the weight earned; null when unknown or not applicable
     * @param source  FILINGS, VALUATION, PRICES, DERIVED or YAHOO; null when unknown
     */
    public record CriterionView(String key, String label, String unit, Double value, double poor, double good,
                                double weight, boolean applies, Double points, String source, String basis) {
    }

    public record MetricView(String key, Double value, String unit, String source, String basis) {
    }

    /** Share of a scorecard's weight that must be known for it to count in the overall. */
    public double minCoverage() {
        return screening.minFundamentalsCoverage();
    }

    /** Weight of the Risk agent in the overall. */
    public double riskWeight() {
        return screening.riskWeight();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Scorecards scorecards(String exchangeCode, String tickerCode) {
        Exchange exchange = Exchange.of(exchangeCode);
        String ticker = Tickers.normalize(tickerCode);
        ScorecardRepository.CompanyRow company = repository.company(exchange.code(), ticker)
                .orElseThrow(() -> new CompanyNotFoundException(exchange.code(), ticker));
        StockSnapshot snapshot = fundamentals.latestSnapshots(exchange, List.of(ticker)).stream().findFirst()
                .orElse(null);
        return compute(exchange, company, snapshot);
    }

    /**
     * The scorecards of a company with its latest screening snapshot ({@code null}: not in the screening data). The
     * Quant Score tab and the overall column of the company list ({@link QuantScoreBoard}) both come from here.
     */
    Scorecards compute(Exchange exchange, ScorecardRepository.CompanyRow company, StockSnapshot snapshot) {
        String ticker = company.ticker();
        String currency = company.currency() == null ? exchange.currency()
                : company.currency().toUpperCase(Locale.ROOT);
        CombinedProfile.Own own = repository.own(company.companyId(), currency, exchange.currency());
        CombinedProfile.Result combined = CombinedProfile.of(snapshot, own, company.sector(), company.industry(),
                company.idxSector());
        StockProfile profile = combined.profile();
        Map<String, MetricSource> sources = combined.sources();

        List<AgentCard> cards = new ArrayList<>();
        Map<InvestorAgent, Double> counted = new EnumMap<>(InvestorAgent.class);
        for (InvestorAgent agent : InvestorAgent.values()) {
            AgentScore score = QuantScorer.score(agent, profile);
            boolean known = score.coverage() > 0;
            boolean inOverall = known && score.coverage() >= screening.minFundamentalsCoverage();
            if (inOverall) {
                counted.put(agent, score.score());
            }
            cards.add(new AgentCard(agent.name(), agent.label(), agent.focus(), known ? score.score() : null,
                    score.coverage(), known ? Assessment.band(score.score()) : null, inOverall,
                    criteria(agent, profile.financial(), score, sources)));
        }
        Double overall = counted.isEmpty() ? null : QuantScorer.overall(counted, screening.riskWeight());

        List<String> notes = new ArrayList<>();
        if (snapshot == null) {
            notes.add(ticker + " is not in the screening data (Ingestion > Screening Data IDX): beta, insider ownership "
                    + "and Yahoo's metrics are unknown; the scorecards use the filings, the valuation and the prices.");
        } else if (!snapshot.hasFundamentals()) {
            notes.add("Yahoo Finance has no fundamentals for " + ticker + " in the screening data: only its market data "
                    + "is used beside the filings.");
        }
        if (own.fiscalYear() == null) {
            notes.add("No fiscal year (FY) income statement of " + ticker + " is stored: the statement metrics come from "
                    + "Yahoo Finance where it has them.");
        }
        if (overall == null) {
            notes.add("No scorecard has " + Math.round(screening.minFundamentalsCoverage() * 100)
                    + "% of its metrics known: no overall score.");
        }
        List<MetricView> metrics = new ArrayList<>();
        Map<String, Object> values = profile.asMap();
        for (Map.Entry<String, MetricSource> e : sources.entrySet()) {
            Object v = values.get(e.getKey());
            if (v instanceof Number n) {
                metrics.add(new MetricView(e.getKey(), n.doubleValue(), unit(e.getKey()), e.getValue().source().name(),
                        e.getValue().basis()));
            }
        }
        return new Scorecards(exchange.code(), ticker, company.companyName(), profile.financial(), overall,
                overall == null ? null : Assessment.band(overall), screening.riskWeight(),
                screening.minFundamentalsCoverage(), snapshot == null ? null : snapshot.snapshotDate(),
                own.valuationDate(), own.fiscalYear(), own.pricesDate(), cards, metrics, notes);
    }

    private static List<CriterionView> criteria(InvestorAgent agent, boolean financial, AgentScore score,
                                                Map<String, MetricSource> sources) {
        List<CriterionView> out = new ArrayList<>();
        for (CriterionInfo c : QuantScorer.card(agent)) {
            boolean applies = c.appliesTo(financial);
            Part part = applies ? score.parts().stream().filter(p -> p.key().equals(c.key())).findFirst().orElse(null)
                    : null;
            Double value = part == null ? null : part.value();
            // Lynch's earnings CAGR is the pulled-back one; the liquidity is log10 of the traded value
            String metric = c.key().equals("liquidity") ? "avgDailyValue" : c.key();
            if (agent == InvestorAgent.LYNCH && metric.equals("netIncomeCagr") && !sources.containsKey(metric)) {
                metric = "earningsGrowth"; // QuantScorer.lynchGrowth falls back to the latest earnings growth
            }
            MetricSource source = value == null ? null : sources.get(metric);
            out.add(new CriterionView(c.key(), c.label(), unit(c.key()), value, c.poor(), c.good(), c.weight(),
                    applies, part == null ? null : part.points(), source == null ? null : source.source().name(),
                    source == null ? null : source.basis()));
        }
        return out;
    }

    /** How a metric is shown. */
    static String unit(String key) {
        return switch (key) {
            case "roe", "roa", "roeAverage", "positiveYears", "profitMargin", "grossMargin", "operatingMargin",
                 "fcfYield", "revenueCagr", "netIncomeCagr", "revenueGrowth", "earningsGrowth", "equityToAssets",
                 "capexIntensity", "drawdown", "insiderOwnership", "range52w", "dividendYield" -> "percent";
            case "opMarginTrend" -> "points";
            case "pe", "pb", "peg", "evToEbitda", "debtToEquity", "currentRatio", "interestCoverage", "fcfConversion" -> "x";
            case "liquidity" -> "log10";
            default -> "number";
        };
    }
}
