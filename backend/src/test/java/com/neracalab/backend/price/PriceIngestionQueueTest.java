package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionJob.Status;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;

/** Worker behaviour with a scripted service: back-off on HTTP 429, run stop, one active job per company. */
class PriceIngestionQueueTest {

    private static final CompanyRef HRTA = new CompanyRef(1, Exchange.IDX, "HRTA", "IDR");
    private static final CompanyRef INDF = new CompanyRef(7, Exchange.IDX, "INDF", "IDR");
    private static final CompanyRef BMRI = new CompanyRef(9, Exchange.IDX, "BMRI", "IDR");
    private static final CompanyRef CEKA = new CompanyRef(11, Exchange.IDX, "CEKA", "IDR");
    private static final CompanyRef ASGR = new CompanyRef(13, Exchange.IDX, "ASGR", "IDR");
    private static final Result OK = new Result("fake", 1, null, null, false, false, 0, 0, 0, 0, 0, null, null, null);

    private PriceIngestionQueue queue;

    @AfterEach
    void stopWorker() {
        if (queue != null) {
            queue.stop();
        }
    }

    @Test
    void waitsAfterHttp429AndRetriesTheSameJob() {
        ScriptedService service = new ScriptedService();
        service.script(HRTA, rateLimited(), rateLimited(), () -> OK);
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(10), Duration.ofMillis(20)));

        PriceIngestionJob job = queue.submit(HRTA, false).job();
        queue.start();

        PriceIngestionJob.View view = awaitFinished(job);
        assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(view.attempts()).isEqualTo(3);
        assertThat(view.result()).isEqualTo(OK);
        assertThat(view.message()).isNull();
    }

    @Test
    void http429AfterTheLastWaitStopsTheRunAndFailsQueuedJobs() {
        ScriptedService service = new ScriptedService();
        service.script(HRTA, rateLimited(), rateLimited());
        service.script(INDF, () -> OK);
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(10)));

        PriceIngestionJob first = queue.submit(HRTA, false).job();
        PriceIngestionJob queued = queue.submit(INDF, false).job();
        queue.start();

        PriceIngestionJob.View firstView = awaitFinished(first);
        PriceIngestionJob.View queuedView = awaitFinished(queued);
        assertThat(firstView.status()).isEqualTo(Status.FAILED);
        assertThat(firstView.attempts()).isEqualTo(2);
        assertThat(firstView.message()).contains("HTTP 429").contains("run stopped");
        assertThat(queuedView.status()).isEqualTo(Status.FAILED);
        assertThat(queuedView.message()).startsWith("Run stopped");
        assertThat(service.calls(INDF)).isZero();
    }

    @Test
    void aSuccessResetsTheBackoffSequence() {
        ScriptedService service = new ScriptedService();
        service.script(HRTA, rateLimited(), () -> OK);
        service.script(INDF, rateLimited(), () -> OK);
        // a single wait: without the reset, INDF's 429 would stop the run
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(10)));

        PriceIngestionJob first = queue.submit(HRTA, false).job();
        PriceIngestionJob second = queue.submit(INDF, false).job();
        queue.start();

        assertThat(awaitFinished(first).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(awaitFinished(second).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void otherFailuresFailOnlyTheirJob() {
        ScriptedService service = new ScriptedService();
        service.script(HRTA, () -> {
            throw new SymbolNotFoundException("Yahoo Finance has no data for HRTA.JK");
        });
        service.script(INDF, () -> OK);
        queue = new PriceIngestionQueue(service, TestPriceProperties.defaults());

        PriceIngestionJob failing = queue.submit(HRTA, false).job();
        PriceIngestionJob next = queue.submit(INDF, false).job();
        queue.start();

        PriceIngestionJob.View failed = awaitFinished(failing);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.message()).isEqualTo("Yahoo Finance has no data for HRTA.JK");
        assertThat(awaitFinished(next).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void oneActiveJobPerCompany() {
        queue = new PriceIngestionQueue(new ScriptedService(), TestPriceProperties.defaults());   // worker not started

        Submission first = queue.submit(HRTA, false);
        Submission again = queue.submit(HRTA, true);
        Submission other = queue.submit(INDF, false);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.job()).isSameAs(first.job());
        assertThat(other.created()).isTrue();
        assertThat(queue.pendingCount()).isEqualTo(2);
        assertThat(queue.jobs()).extracting(PriceIngestionJob::id).containsExactly(other.job().id(), first.job().id());
        assertThat(queue.job(first.job().id())).containsSame(first.job());
        assertThat(first.job().view().status()).isEqualTo(Status.QUEUED);
    }

    @Test
    void severalWorkersIngestCompaniesAtTheSameTime() {
        List<CompanyRef> companies = List.of(HRTA, INDF, BMRI, CEKA, ASGR);
        CyclicBarrier allRunning = new CyclicBarrier(5);
        ScriptedService service = new ScriptedService();
        // passes only when five ingestions run together
        companies.forEach(c -> service.script(c, () -> {
            await(allRunning);
            return OK;
        }));
        queue = new PriceIngestionQueue(service, TestPriceProperties.defaults(), job -> { }, 5);

        List<PriceIngestionJob> jobs = companies.stream().map(c -> queue.submit(c, false).job()).toList();
        queue.start();

        assertThat(jobs).allSatisfy(job -> assertThat(awaitFinished(job).status()).isEqualTo(Status.SUCCEEDED));
    }

    @Test
    void http429OfJobsRunningTogetherCountsAsOnePause() {
        List<CompanyRef> companies = List.of(HRTA, INDF, BMRI);
        CyclicBarrier allRunning = new CyclicBarrier(3);
        ScriptedService service = new ScriptedService();
        // all three are being fetched when the provider starts answering 429
        companies.forEach(c -> service.script(c, () -> {
            await(allRunning);
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests", null);
        }, () -> OK));
        // a single wait: were each 429 counted, the second one would stop the run
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(50)), job -> { }, 3);

        List<PriceIngestionJob> jobs = companies.stream().map(c -> queue.submit(c, false).job()).toList();
        queue.start();

        assertThat(jobs).allSatisfy(job -> {
            PriceIngestionJob.View view = awaitFinished(job);
            assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(view.attempts()).isEqualTo(2);
        });
    }

    @Test
    void theRunStopFailsEveryJobAnswered429AndEveryQueuedJob() {
        CyclicBarrier firstAttempts = new CyclicBarrier(2);
        CyclicBarrier secondAttempts = new CyclicBarrier(2);
        ScriptedService service = new ScriptedService();
        for (CompanyRef c : List.of(HRTA, INDF)) {
            service.script(c, () -> {
                await(firstAttempts);
                throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests", null);
            }, () -> {
                await(secondAttempts);
                throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests", null);
            });
        }
        service.script(BMRI, () -> OK);
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(50)), job -> { }, 2);

        PriceIngestionJob first = queue.submit(HRTA, false).job();
        PriceIngestionJob second = queue.submit(INDF, false).job();
        PriceIngestionJob queued = queue.submit(BMRI, false).job();
        queue.start();

        List<PriceIngestionJob.View> stopped = List.of(awaitFinished(first), awaitFinished(second));
        assertThat(stopped).allSatisfy(view -> {
            assertThat(view.status()).isEqualTo(Status.FAILED);
            assertThat(view.attempts()).isEqualTo(2);
        });
        // one of them stops the run, the other one was answered 429 beside it
        assertThat(stopped).extracting(PriceIngestionJob.View::message)
                .anySatisfy(m -> assertThat(m).contains("HTTP 429").contains("run stopped"))
                .anySatisfy(m -> assertThat(m).startsWith("Run stopped"));
        PriceIngestionJob.View queuedView = awaitFinished(queued);
        assertThat(queuedView.status()).isEqualTo(Status.FAILED);
        assertThat(queuedView.message()).startsWith("Run stopped");
        assertThat(service.calls(BMRI)).isZero();
    }

    @Test
    void aQueuedJobStaysQueuedWhileTheQueueIsPaused() {
        ScriptedService service = new ScriptedService();
        service.script(HRTA, rateLimited(), () -> OK);
        service.script(INDF, () -> OK);
        List<Status> statusesOfQueued = java.util.Collections.synchronizedList(new ArrayList<>());
        queue = new PriceIngestionQueue(service, TestPriceProperties.withBackoff(Duration.ofMillis(100)),
                job -> {
                    if (job.ticker().equals("INDF")) {
                        statusesOfQueued.add(job.status());
                    }
                }, 1);

        PriceIngestionJob limited = queue.submit(HRTA, false).job();
        PriceIngestionJob queued = queue.submit(INDF, false).job();
        queue.start();

        assertThat(awaitFinished(limited).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(awaitFinished(queued).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(statusesOfQueued).containsExactly(Status.QUEUED, Status.RUNNING, Status.SUCCEEDED);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("the ingestions did not run at the same time", e);
        }
    }

    private static Supplier<Result> rateLimited() {
        return () -> {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests for HRTA.JK", null);
        };
    }

    private static PriceIngestionJob.View awaitFinished(PriceIngestionJob job) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            PriceIngestionJob.View view = job.view();
            if (view.status() == Status.SUCCEEDED || view.status() == Status.FAILED) {
                return view;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("job did not finish: " + job.view());
    }

    /** Answers each company's calls from its script, one step per call. */
    private static final class ScriptedService extends PriceIngestionService {

        private final Map<Long, Deque<Supplier<Result>>> scripts = new HashMap<>();
        private final Map<Long, Integer> calls = new HashMap<>();

        ScriptedService() {
            super(null, null, null, null, null, TestPriceProperties.defaults());
        }

        @SafeVarargs
        final void script(CompanyRef company, Supplier<Result>... steps) {
            scripts.put(company.companyId(), new ArrayDeque<>(List.of(steps)));
        }

        synchronized int calls(CompanyRef company) {
            return calls.getOrDefault(company.companyId(), 0);
        }

        @Override
        public Result ingest(CompanyRef company, boolean full) {
            synchronized (this) {
                calls.merge(company.companyId(), 1, Integer::sum);
            }
            Deque<Supplier<Result>> steps = scripts.getOrDefault(company.companyId(), new ArrayDeque<>(new ArrayList<>()));
            Supplier<Result> step = steps.poll();
            if (step == null) {
                throw new IllegalStateException("no scripted step left for " + company.ticker());
            }
            return step.get();
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
