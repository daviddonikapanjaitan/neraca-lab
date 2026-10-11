package com.neracalab.backend.ingestion.agent;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * Receives every model call of an ingestion (each attempt, also a failed one), to record its tokens and cost
 * ({@code llm_usage}). An implementation never throws: recording must not fail the ingestion.
 */
@FunctionalInterface
public interface ModelCallListener {

    /** Stages of the standard extraction and of the full-text extraction (column {@code llm_usage.stage}). */
    String PLAN = "PLAN";
    String EXECUTE = "EXECUTE";
    String REVIEW = "REVIEW";
    String FULL_TEXT = "FULL_TEXT";

    /** No recording (an ingestion outside an upload job, tests). */
    ModelCallListener NONE = (stage, model, response, durationMs, error) -> { };

    /**
     * @param stage      what the call was for ({@link #PLAN}, {@link #EXECUTE}, {@link #REVIEW}, {@link #FULL_TEXT})
     * @param model      the model asked (the id of the request, e.g. deepseek/deepseek-v4-flash-0731)
     * @param response   the answer; {@code null} when the attempt failed
     * @param durationMs how long the attempt took
     * @param error      why the attempt failed; {@code null} when it answered
     */
    void called(String stage, String model, ChatResponse response, long durationMs, String error);
}
