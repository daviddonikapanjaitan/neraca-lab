package com.neracalab.backend.portfolio.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Portfolio &gt; Analysis (instruction/INSTRUCTION.md): AI recommendations to sell, decrease, hold, increase or buy and
 * the cash to keep. Amounts in IDR, weights as fractions of the capital (0.33 = 33%).
 */
public final class PortfolioAnalysisViews {

    private PortfolioAnalysisViews() {
    }

    /**
     * POST /api/v1/portfolio/analyses
     *
     * @param mode         BULK (every company with a Fundamental (Score)) or SELECTED ({@code tickers})
     * @param steps        FUNDAMENTAL_TECHNICAL (default: the fundamental step then the technical step) or
     *                     FUNDAMENTAL (the fundamental step and agent only)
     * @param maxStocks    the most stocks the portfolio may hold after the recommendations
     * @param addedCapital money the user plans to add (IDR, optional)
     */
    public record StartRequest(
            @Size(max = 16) String exchange,
            @NotBlank String mode,
            @Size(max = 30) String steps,
            @NotNull Integer maxStocks,
            List<String> tickers,
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal addedCapital) {
    }

    /** A run in the list. */
    public record RunSummary(String id, String status, String stage, String message, String exchange, String mode,
                             String steps, List<String> tickers, int maxStocks, double addedCapital, double budgetUsd, double costUsd,
                             int modelCalls, long promptTokens, long completionTokens, Instant requestedAt,
                             Instant startedAt, Instant finishedAt, IngestionJob.CreatedBy createdBy,
                             Integer recommended, Double capital) {
    }

    /**
     * A stock of the run.
     *
     * @param stage FUNDAMENTAL_OUT, TECHNICAL_OUT (left out by a rules step), FUNDAMENTAL_AI_OUT, TECHNICAL_AI_OUT
     *              (rejected by an AI agent), RECOMMENDED, NOT_CHOSEN, HELD
     */
    public record Candidate(long id, String ticker, String companyName, String sector, boolean held,
                            Map<String, Object> position, Double close, String priceDate, Double fundamentalScore,
                            String fundamentalVerdict, Integer fundamentalRank, Double technicalScore,
                            String technicalStance, Integer technicalRank, String stage, Map<String, Object> fundamentalAi,
                            Map<String, Object> technicalAi, Map<String, Object> kelly, String action,
                            Map<String, Object> recommendation, String conviction, String thesis, List<String> redFlags,
                            Integer finalRank) {
    }

    /** GET /api/v1/portfolio/analyses/{id} */
    public record Report(RunSummary run, Map<String, Object> capital, Map<String, Object> funnel,
                         Map<String, Object> synthesis, Map<String, Object> allocation,
                         List<Map<String, Object>> checks, Map<String, Object> notes, List<Candidate> candidates,
                         List<UsageRow> usage, String disclaimer) {
    }

    /** A company the form can select, with its two scores. */
    public record SelectableCompany(String ticker, String companyName, String sector, Double fundamentalScore,
                                    String fundamentalVerdict, Double technicalScore, String technicalStance,
                                    boolean held) {
    }

    /** GET /api/v1/portfolio/analyses/options */
    public record Options(String exchange, int defaultMaxStocks, int maxStocksLimit, int maxSelected,
                          double minFundamentalScore, int fundamentalMultiplier, int maxFundamentalShortlist,
                          int technicalMultiplier, int maxTechnicalShortlist, double maxPositionWeight,
                          double maxSectorWeight, double kellyFraction, int lotSize, double budgetUsd,
                          String agentModel, String synthesisModel, Map<String, Object> portfolio,
                          List<SelectableCompany> companies, Map<String, Object> scores) {
    }
}
