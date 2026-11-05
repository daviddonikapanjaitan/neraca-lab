package com.neracalab.backend.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background jobs of the ingestion APIs ({@code neracalab.jobs.*}).
 *
 * @param workers       worker threads of each ingestion queue (RAG, screening data, and the others without their own
 *                      number): that many jobs of a queue run at the same time, the others wait
 * @param uploadWorkers worker threads of the financial statement upload queue ({@code neracalab.jobs.upload-workers},
 *                      env {@code INGESTION_UPLOAD_WORKERS}, 50 in application.yaml); 0: as many as {@code workers}.
 *                      Its own number: an upload waits on model calls and short database writes
 * @param priceWorkers  worker threads of the price queue ({@code neracalab.jobs.price-workers}, env
 *                      {@code INGESTION_PRICE_WORKERS}, 50 in application.yaml since 2026-11-05); 0: as many as
 *                      {@code workers}. A price job waits on its provider request (paced by {@code PacedHttpClient}:
 *                      at most {@code neracalab.prices.throughput.concurrent-requests} in flight) and then writes one
 *                      company's prices and valuation in a short transaction
 */
@ConfigurationProperties("neracalab.jobs")
public record JobProperties(int workers, int uploadWorkers, int priceWorkers) {

    @ConstructorBinding
    public JobProperties(@DefaultValue("5") int workers, @DefaultValue("0") int uploadWorkers,
                         @DefaultValue("0") int priceWorkers) {
        if (workers < 1) {
            throw new IllegalArgumentException("neracalab.jobs.workers must be at least 1");
        }
        if (uploadWorkers < 0) {
            throw new IllegalArgumentException("neracalab.jobs.upload-workers must be 0 (as workers) or more");
        }
        if (priceWorkers < 0) {
            throw new IllegalArgumentException("neracalab.jobs.price-workers must be 0 (as workers) or more");
        }
        this.workers = workers;
        this.uploadWorkers = uploadWorkers;
        this.priceWorkers = priceWorkers;
    }

    /** The upload and price queues' workers as many as the other queues'. */
    public JobProperties(int workers) {
        this(workers, 0, 0);
    }

    /** The price queue's workers as many as the other queues'. */
    public JobProperties(int workers, int uploadWorkers) {
        this(workers, uploadWorkers, 0);
    }

    /** Worker threads of the financial statement upload queue. */
    public int uploads() {
        return uploadWorkers > 0 ? uploadWorkers : workers;
    }

    /** Worker threads of the price queue. */
    public int prices() {
        return priceWorkers > 0 ? priceWorkers : workers;
    }
}
