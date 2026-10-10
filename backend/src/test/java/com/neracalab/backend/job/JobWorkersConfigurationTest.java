package com.neracalab.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.SmartLifecycle;

import com.neracalab.backend.ingestion.FinancialStatementQueue;
import com.neracalab.backend.price.PriceIngestionQueue;
import com.neracalab.backend.price.TestPriceProperties;
import com.neracalab.backend.rag.RagQueue;
import com.neracalab.backend.screening.data.FundamentalsQueue;

/**
 * {@code neracalab.jobs.workers} sets the worker threads of every ingestion queue: 5 by default, here 6. No
 * database: the queues are started without their collaborators and no job is submitted.
 */
class JobWorkersConfigurationTest {

    private final List<SmartLifecycle> started = new ArrayList<>();

    @AfterEach
    void stopQueues() {
        started.forEach(SmartLifecycle::stop);
    }

    @Test
    void theWorkersSettingIsReadFromTheConfiguration() {
        assertThat(bind(Map.of("neracalab.jobs.workers", "6")).workers()).isEqualTo(6);
        assertThat(bind(Map.of()).workers()).as("default").isEqualTo(5);
    }

    @Test
    void everyIngestionQueueRunsTheConfiguredNumberOfWorkers() {
        JobProperties six = bind(Map.of("neracalab.jobs.workers", "6"));
        Set<Thread> before = Thread.getAllStackTraces().keySet();      // other tests' queues may still run
        start(new FinancialStatementQueue(null, null, null, Duration.ofMinutes(5), false, six));
        start(new PriceIngestionQueue(null, TestPriceProperties.defaults(), job -> { }, six));
        start(new RagQueue(null, null, null, six));
        start(new FundamentalsQueue(null, null, six));

        for (String queue : List.of("financial-statement-ingestion", "price-ingestion", "rag-ingestion", "screening-data-etl")) {
            assertThat(workerThreads(queue, before)).as(queue).containsExactlyInAnyOrder(
                    queue + "-1", queue + "-2", queue + "-3", queue + "-4", queue + "-5", queue + "-6");
        }
    }

    @Test
    void atLeastOneWorkerIsNeeded() {
        assertThatThrownBy(() -> new JobProperties(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neracalab.jobs.workers");
    }

    private static JobProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("neracalab.jobs", JobProperties.class);
    }

    private void start(SmartLifecycle queue) {
        queue.start();
        started.add(queue);
    }

    /** The queue's worker threads started since {@code before}. */
    private static List<String> workerThreads(String queue, Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && !before.contains(t))
                .map(Thread::getName).filter(name -> name.matches(queue + "-\\d+")).toList();
    }
}
