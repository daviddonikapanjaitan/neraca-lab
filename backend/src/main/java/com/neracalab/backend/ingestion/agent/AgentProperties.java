package com.neracalab.backend.ingestion.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of the ingestion agent.
 *
 * @param maxIterations    model turns per execution round (Tool Calling Loop guard)
 * @param reflectionRounds extra execution rounds the reviewer may request (Reflection Pattern)
 * @param temperature      sampling temperature; 0 for repeatable tool use
 */
@ConfigurationProperties("neracalab.ingestion")
public record AgentProperties(
        @DefaultValue("30") int maxIterations,
        @DefaultValue("2") int reflectionRounds,
        @DefaultValue("0.0") double temperature) {
}
