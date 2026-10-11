package com.neracalab.backend.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background jobs of the ingestion APIs ({@code neracalab.jobs.*}).
 *
 * @param workers worker threads of each ingestion queue (financial statement uploads, prices, RAG, screening
 *                data): that many jobs of a queue run at the same time, the others wait
 */
@ConfigurationProperties("neracalab.jobs")
public record JobProperties(@DefaultValue("5") int workers) {

    public JobProperties {
        if (workers < 1) {
            throw new IllegalArgumentException("neracalab.jobs.workers must be at least 1");
        }
    }
}
