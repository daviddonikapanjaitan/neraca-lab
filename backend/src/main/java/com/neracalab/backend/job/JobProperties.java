package com.neracalab.backend.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background jobs of the ingestion APIs ({@code neracalab.jobs.*}).
 *
 * @param workers       worker threads of each ingestion queue (prices, RAG, screening data): that many jobs of a queue
 *                      run at the same time, the others wait
 * @param uploadWorkers worker threads of the financial statement upload queue ({@code neracalab.jobs.upload-workers},
 *                      env {@code INGESTION_UPLOAD_WORKERS}, 50 in application.yaml); 0: as many as {@code workers}.
 *                      Its own number: an upload waits on model calls and short database writes, while the price
 *                      requests to Yahoo Finance and to each news site stay paced one at a time however many workers
 *                      the other queues have
 */
@ConfigurationProperties("neracalab.jobs")
public record JobProperties(int workers, int uploadWorkers) {

    @ConstructorBinding
    public JobProperties(@DefaultValue("5") int workers, @DefaultValue("0") int uploadWorkers) {
        if (workers < 1) {
            throw new IllegalArgumentException("neracalab.jobs.workers must be at least 1");
        }
        if (uploadWorkers < 0) {
            throw new IllegalArgumentException("neracalab.jobs.upload-workers must be 0 (as workers) or more");
        }
        this.workers = workers;
        this.uploadWorkers = uploadWorkers;
    }

    /** The upload queue's workers as many as the other queues'. */
    public JobProperties(int workers) {
        this(workers, 0);
    }

    /** Worker threads of the financial statement upload queue. */
    public int uploads() {
        return uploadWorkers > 0 ? uploadWorkers : workers;
    }
}
