package com.neracalab.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The worker threads of a job queue: as many jobs at the same time as there are workers, never more. */
class JobWorkerPoolTest {

    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private JobWorkerPool pool;

    @AfterEach
    void stopWorkers() {
        if (pool != null) {
            pool.stop();
        }
    }

    @Test
    void fiveWorkersProcessFiveJobsAtTheSameTime() throws Exception {
        CyclicBarrier allRunning = new CyclicBarrier(5);
        CountDownLatch done = new CountDownLatch(5);
        pool = new JobWorkerPool("test", 5, jobs, id -> {
            await(allRunning);      // passes only when five jobs are being processed together
            done.countDown();
        });
        for (int i = 0; i < 5; i++) {
            pool.enqueue(UUID.randomUUID());
        }
        assertThat(pool.pendingCount()).isEqualTo(5);

        pool.start();

        assertThat(done.await(10, TimeUnit.SECONDS)).as("five jobs ran at the same time").isTrue();
        assertThat(pool.pendingCount()).isZero();
    }

    @Test
    void neverMoreJobsAtTheSameTimeThanWorkers() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(12);
        pool = new JobWorkerPool("test", 3, jobs, id -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(30);
            running.decrementAndGet();
            done.countDown();
        });
        for (int i = 0; i < 12; i++) {
            pool.enqueue(UUID.randomUUID());
        }

        pool.start();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(most.get()).isEqualTo(3);
    }

    @Test
    void oneWorkerProcessesTheJobsOneAfterTheOtherInOrder() throws Exception {
        List<UUID> submitted = new ArrayList<>();
        List<UUID> processed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        pool = new JobWorkerPool("test", 1, jobs, id -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            processed.add(id);
            sleep(10);
            running.decrementAndGet();
            done.countDown();
        });
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            submitted.add(id);
            pool.enqueue(id);
        }

        pool.start();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(most.get()).isEqualTo(1);
        assertThat(processed).containsExactlyElementsOf(submitted);
    }

    @Test
    void aJobThatThrowsIsFailedAndTheWorkerGoesOn() throws Exception {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        pool = new JobWorkerPool("test", 1, jobs, id -> {
            if (id.equals(failing)) {
                throw new IllegalStateException("no data");
            }
            done.countDown();
        });
        pool.enqueue(failing);
        pool.enqueue(next);

        pool.start();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        verify(jobs, timeout(5000)).finish(eq(failing), eq(IngestionJobStatus.FAILED), eq("Failed"), eq("no data"), any());
    }

    @Test
    void stopEndsEveryWorker() {
        pool = new JobWorkerPool("test", 4, jobs, id -> { });
        pool.start();
        assertThat(pool.isRunning()).isTrue();

        pool.stop();

        assertThat(pool.isRunning()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("test-") && t.isAlive());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("the jobs did not run at the same time", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
