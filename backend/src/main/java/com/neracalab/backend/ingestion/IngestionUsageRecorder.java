package com.neracalab.backend.ingestion;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.ingestion.agent.ModelCallListener;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.UsageMeter;

/**
 * Records the model calls of an upload job in {@code llm_usage}, as the screening does for a run: one row per
 * attempt with its stage, model, tokens and cost. The cost is the one OpenRouter reports ({@code usage.cost});
 * a call without one is priced from {@code neracalab.screening.llm.prices} and flagged as estimated. A failed
 * attempt is a row with its error and no tokens - what the provider billed for it, if anything, is not known.
 * <p>
 * The rows are written while the job runs (each call on its own), so the cost of a job that is stopped at its
 * time limit or fails is kept. Recording never fails the ingestion: a row that cannot be written is logged.
 */
@Component
public class IngestionUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(IngestionUsageRecorder.class);

    private final IngestionJobRepository jobs;
    private final ScreeningProperties.Llm prices;

    public IngestionUsageRecorder(IngestionJobRepository jobs, ScreeningProperties properties) {
        this.jobs = jobs;
        this.prices = properties.llm();
    }

    /**
     * The listener of one upload job.
     *
     * @param ticker the filing's company ({@code null} when the workbook names none)
     */
    public ModelCallListener forJob(UUID jobId, String ticker) {
        return (stage, model, response, durationMs, error) -> {
            try {
                jobs.recordModelCall(jobId, modelCall(stage, ticker, model, response, durationMs, error));
            } catch (RuntimeException e) {
                log.warn("upload job {}: model call ({} {}) not recorded: {}", jobId, stage, model, e.getMessage());
            }
        };
    }

    private IngestionJob.ModelCall modelCall(String stage, String ticker, String model,
                                             org.springframework.ai.chat.model.ChatResponse response, long durationMs,
                                             String error) {
        if (response == null) {
            return new IngestionJob.ModelCall(stage, ticker, model, 0, 0, 0, 0, 0, false, durationMs,
                    error == null ? "no response" : error);
        }
        UsageMeter.Usage u = LlmGateway.usageOf(new LlmGateway.Purpose(stage, null, ticker), model, response, durationMs,
                prices::price);
        return new IngestionJob.ModelCall(stage, ticker, model, u.promptTokens(), u.completionTokens(),
                u.reasoningTokens(), u.cachedTokens(), u.costUsd(), u.costEstimated(), durationMs, null);
    }
}
