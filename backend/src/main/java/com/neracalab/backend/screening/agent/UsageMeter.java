package com.neracalab.backend.screening.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tokens and cost of one screening run, shared by all model calls of the run (thread-safe). The
 * budget is a hard cap: callers ask {@link #allows} before an optional call and degrade instead of
 * exceeding it.
 */
public final class UsageMeter {

    /** One model call. */
    public record Usage(String stage, String agent, String ticker, String model, int promptTokens,
                        int completionTokens, int reasoningTokens, int cachedTokens, double costUsd,
                        boolean costEstimated, long durationMs, String error) {
    }

    /** Totals of a stage and model (report). */
    public record Totals(int calls, long promptTokens, long completionTokens, long reasoningTokens, long cachedTokens,
                         double costUsd) {

        Totals plus(Usage u) {
            return new Totals(calls + 1, promptTokens + u.promptTokens(), completionTokens + u.completionTokens(),
                    reasoningTokens + u.reasoningTokens(), cachedTokens + u.cachedTokens(), costUsd + u.costUsd());
        }

        static final Totals ZERO = new Totals(0, 0, 0, 0, 0, 0);
    }

    /** Receives every recorded call (persisted to {@code llm_usage}). */
    @FunctionalInterface
    public interface Sink {
        void record(UUID runId, Usage usage);
    }

    private final UUID runId;
    private final double budgetUsd;
    private final Sink sink;
    private Totals total = Totals.ZERO;
    private final Map<String, Totals> byStage = new LinkedHashMap<>();

    public UsageMeter(UUID runId, double budgetUsd, Sink sink) {
        this.runId = runId;
        this.budgetUsd = budgetUsd;
        this.sink = sink;
    }

    public void record(Usage usage) {
        synchronized (this) {
            total = total.plus(usage);
            byStage.merge(usage.stage() + "|" + usage.model(), Totals.ZERO.plus(usage), (a, b) -> new Totals(
                    a.calls() + b.calls(), a.promptTokens() + b.promptTokens(), a.completionTokens() + b.completionTokens(),
                    a.reasoningTokens() + b.reasoningTokens(), a.cachedTokens() + b.cachedTokens(),
                    a.costUsd() + b.costUsd()));
        }
        sink.record(runId, usage);
    }

    public synchronized Totals total() {
        return total;
    }

    /** Totals per "stage|model". */
    public synchronized Map<String, Totals> byStage() {
        return Map.copyOf(byStage);
    }

    public synchronized double spentUsd() {
        return total.costUsd();
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    /**
     * Whether a call of about {@code estimateUsd} still fits while keeping {@code reserveUsd} free
     * (for the synthesis).
     */
    public synchronized boolean allows(double estimateUsd, double reserveUsd) {
        return total.costUsd() + estimateUsd + reserveUsd <= budgetUsd;
    }

    public UUID runId() {
        return runId;
    }
}
