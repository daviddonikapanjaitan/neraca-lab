package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.IngestionResponse.Status;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.JobProperties;

/**
 * The upload workers, with a scripted agent and no database: filings are stored at the same time, also several
 * of one company; with {@code same-company-in-order} filings of one company one after the other in upload order.
 * A file is named TICKER-n.xlsx here.
 */
class FinancialStatementQueueWorkersTest {

    private final IngestionService service = mock(IngestionService.class);
    private final IngestionFileRepository files = mock(IngestionFileRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    /** The file of each prepared session. */
    private final Map<IngestionSession, String> sessions = Collections.synchronizedMap(new IdentityHashMap<>());
    /** What the agent does with a file (its name); set by the test. */
    private volatile Consumer<String> agent = file -> { };
    private FinancialStatementQueue queue;

    @BeforeEach
    void scriptCollaborators() {
        AtomicLong fileIds = new AtomicLong();
        Map<Long, byte[]> stored = new ConcurrentHashMap<>();
        when(files.store(any(), anyString(), any())).thenAnswer(call -> {
            long id = fileIds.incrementAndGet();
            stored.put(id, call.getArgument(0));
            return new StoredFile(id, call.getArgument(1), 1, "checksum-" + id, false);
        });
        when(files.content(anyLong())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<Long>getArgument(0))));
        when(jobs.find(any())).thenAnswer(call -> Optional.of(mock(IngestionJob.class)));
        when(service.prepare(any(), anyString())).thenAnswer(call -> {
            String file = call.getArgument(1);
            IngestionSession session = mock(IngestionSession.class, RETURNS_DEEP_STUBS);
            when(session.info().ticker()).thenReturn(ticker(file));
            sessions.put(session, file);
            return session;
        });
        when(service.run(any())).thenAnswer(call -> {
            agent.accept(sessions.get(call.<IngestionSession>getArgument(0)));
            return new IngestionResponse("test", Status.COMPLETED, null, null, null, null, false, List.of(), List.of(),
                    List.of(), Map.of(), Map.of(), null, List.of(), null, null);
        });
    }

    @AfterEach
    void stopWorkers() {
        if (queue != null) {
            queue.stop();
        }
    }

    @Test
    void filingsOfDifferentCompaniesAreStoredAtTheSameTime() {
        CyclicBarrier allRunning = new CyclicBarrier(5);
        agent = file -> await(allRunning);      // passes only when five agents work together
        queue = queue(5);
        for (String ticker : List.of("ASGR", "BMRI", "CEKA", "HRTA", "INDF")) {
            submit(ticker + "-1.xlsx");
        }

        queue.start();

        verify(jobs, timeout(10_000).times(5)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    void fiveFilingsOfOneCompanyAreStoredAtTheSameTime() {
        CyclicBarrier allRunning = new CyclicBarrier(5);
        agent = file -> await(allRunning);      // passes only when the five MAPA agents work together
        queue = queue(5);
        for (int year = 2022; year <= 2026; year++) {
            submit("MAPA-" + year + ".xlsx");
        }
        assertThat(queue.pendingCount()).isEqualTo(5);

        queue.start();

        verify(jobs, timeout(10_000).times(5)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        verify(jobs, timeout(10_000).times(5)).running(any(), any());
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    void aSixthFilingWaitsForAFreeWorker() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        agent = file -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(60);
            running.decrementAndGet();
        };
        queue = queue(5);
        for (int n = 1; n <= 8; n++) {
            submit("MAPA-" + n + ".xlsx");
        }

        queue.start();

        verify(jobs, timeout(10_000).times(8)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        assertThat(most.get()).isEqualTo(5);
    }

    @Test
    void inOrderFilingsOfOneCompanyAreStoredOneAfterTheOtherInUploadOrder() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> running = new ConcurrentHashMap<>();
        AtomicInteger mostOfOneCompany = new AtomicInteger();
        agent = file -> {
            AtomicInteger ofCompany = running.computeIfAbsent(ticker(file), t -> new AtomicInteger());
            mostOfOneCompany.accumulateAndGet(ofCompany.incrementAndGet(), Math::max);
            order.add(file);
            sleep(40);
            ofCompany.decrementAndGet();
        };
        queue = queue(5, true);
        List<String> uploads = List.of("HRTA-1.xlsx", "HRTA-2.xlsx", "BMRI-1.xlsx", "HRTA-3.xlsx", "BMRI-2.xlsx",
                "HRTA-4.xlsx", "HRTA-5.xlsx");
        uploads.forEach(this::submit);
        assertThat(queue.pendingCount()).isEqualTo(7);

        queue.start();

        verify(jobs, timeout(10_000).times(7)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        assertThat(mostOfOneCompany.get()).as("filings of one company stored at the same time").isEqualTo(1);
        assertThat(order.stream().filter(f -> f.startsWith("HRTA"))).containsExactly("HRTA-1.xlsx", "HRTA-2.xlsx",
                "HRTA-3.xlsx", "HRTA-4.xlsx", "HRTA-5.xlsx");
        assertThat(order.stream().filter(f -> f.startsWith("BMRI"))).containsExactly("BMRI-1.xlsx", "BMRI-2.xlsx");
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    void inOrderASecondCompanyDoesNotWaitForTheFilingsOfTheFirst() {
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        agent = file -> {
            if (file.equals("HRTA-1.xlsx") || file.equals("BMRI-1.xlsx")) {
                await(bothRunning);     // BMRI starts although three HRTA filings were uploaded before it
            }
        };
        queue = queue(2, true);
        List.of("HRTA-1.xlsx", "HRTA-2.xlsx", "HRTA-3.xlsx", "BMRI-1.xlsx").forEach(this::submit);

        queue.start();

        verify(jobs, timeout(10_000).times(4)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
    }

    @Test
    void inOrderAFailedFilingLetsTheNextOneOfItsCompanyRun() {
        agent = file -> {
            if (file.equals("HRTA-1.xlsx")) {
                throw new IllegalStateException("model unavailable");
            }
        };
        queue = queue(3, true);
        submit("HRTA-1.xlsx");
        submit("HRTA-2.xlsx");

        queue.start();

        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.FAILED), eq("Failed"), eq("model unavailable"), any());
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        assertThat(queue.pendingCount()).isZero();
    }

    private FinancialStatementQueue queue(int workers) {
        return queue(workers, false);
    }

    private FinancialStatementQueue queue(int workers, boolean sameCompanyInOrder) {
        return new FinancialStatementQueue(service, files, jobs, Duration.ofMinutes(5), sameCompanyInOrder,
                new JobProperties(workers));
    }

    private void submit(String file) {
        assertThat(queue.submit(file.getBytes(StandardCharsets.UTF_8), file, "application/test", ticker(file), null).created())
                .isTrue();
    }

    private static String ticker(String file) {
        return file.substring(0, file.indexOf('-'));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("the filings were not stored at the same time", e);
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
