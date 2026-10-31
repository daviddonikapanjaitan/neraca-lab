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
import org.mockito.ArgumentCaptor;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.IngestionResponse.Status;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.error.IngestionErrorRepository;
import com.neracalab.backend.ingestion.error.IngestionErrorRepository.NewError;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.JobProperties;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.PricesAfterUpload;
import com.neracalab.backend.rag.XbrlRagService;

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
    /** What the ingestion of every file returns; set by the test. */
    private volatile IngestionResponse outcome = response(Status.COMPLETED, null);
    private final IngestionErrorRepository errors = mock(IngestionErrorRepository.class);
    private final IngestionUsageRecorder usage = mock(IngestionUsageRecorder.class);
    private final PricesAfterUpload prices = mock(PricesAfterUpload.class);
    private final XbrlRagService rag = mock(XbrlRagService.class);
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
        when(service.standardLimit(any())).thenAnswer(call -> call.getArgument(0));
        when(service.open(any(), anyString())).thenAnswer(call -> {
            String file = call.getArgument(1);
            IngestionSession session = mock(IngestionSession.class, RETURNS_DEEP_STUBS);
            when(session.info().ticker()).thenReturn(ticker(file));
            sessions.put(session, file);
            return session;
        });
        when(service.run(any(), any())).thenAnswer(call -> {
            agent.accept(sessions.get(call.<IngestionSession>getArgument(0)));
            return outcome;
        });
    }

    private static IngestionResponse response(Status status, IngestionResponse.Fallback fallback) {
        return new IngestionResponse("test", status, null, null, null, null, false, List.of(), List.of(),
                List.of(), Map.of(), Map.of(), null, List.of(), null, status == Status.FAILED ? "model offline" : null, fallback);
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

    @Test
    void anUploadStoredCleanlyIsNotAnError() {
        queue = queue(1);
        submit("HRTA-1.xlsx");

        queue.start();

        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        verify(errors, org.mockito.Mockito.after(200).never()).record(any());
    }

    @Test
    void anIncompleteUploadIsRecordedInTheErrorLog() {
        outcome = response(Status.INCOMPLETE, null);
        queue = queue(1);
        submit("TINS-2025.xlsx");

        queue.start();

        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.INCOMPLETE), any(), any(), any());
        ArgumentCaptor<NewError> error = ArgumentCaptor.forClass(NewError.class);
        verify(errors, timeout(10_000)).record(error.capture());
        assertThat(error.getValue().type()).isEqualTo(IngestionErrorRepository.Type.INCOMPLETE);
        assertThat(error.getValue().fileName()).isEqualTo("TINS-2025.xlsx");
        assertThat(error.getValue().ticker()).isEqualTo("TINS");
        assertThat(error.getValue().jobId()).isNotNull();
        assertThat(error.getValue().fallbackUsed()).isFalse();
        assertThat(error.getValue().finalStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    void anUploadTheFullTextExtractionRescuedSucceedsAndIsStillRecorded() {
        outcome = response(Status.COMPLETED, new IngestionResponse.Fallback("INCOMPLETE",
                "saveStatements(PRIOR_PERIOD) is blocked: income_statement failed checks", "anthropic/claude-sonnet-5.5",
                List.of("PRIOR_PERIOD"), 1,
                List.of("PRIOR_PERIOD 2024 FY income_statement"), List.of(), List.of("saveStatements(PRIOR_PERIOD) done"), null));
        queue = queue(1);
        submit("TINS-2025.xlsx");

        queue.start();

        ArgumentCaptor<String> stage = ArgumentCaptor.forClass(String.class);
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), stage.capture(), any(), any());
        assertThat(stage.getValue()).startsWith("Stored and verified TINS").endsWith("; full-text extraction of PRIOR_PERIOD");
        // the model calls of the job are recorded for it (tokens and cost)
        verify(usage).forJob(any(java.util.UUID.class), eq("TINS"));
        ArgumentCaptor<NewError> error = ArgumentCaptor.forClass(NewError.class);
        verify(errors, timeout(10_000)).record(error.capture());
        assertThat(error.getValue().type()).isEqualTo(IngestionErrorRepository.Type.INCOMPLETE);
        assertThat(error.getValue().message()).startsWith("saveStatements(PRIOR_PERIOD) is blocked");
        assertThat(error.getValue().fallbackUsed()).isTrue();
        assertThat(error.getValue().fallbackColumns()).isEqualTo("PRIOR_PERIOD");
        assertThat(error.getValue().finalStatus()).isEqualTo("SUCCEEDED");
        // the model that did the full-text extraction is part of the record
        assertThat(((java.util.Map<?, ?>) error.getValue().details()).get("fullTextExtraction"))
                .extracting("model").isEqualTo("anthropic/claude-sonnet-5.5");
    }

    /**
     * The full-text extraction's model (Sonnet) kept failing: the upload fails, and the error log has the model,
     * why the extraction stopped and each failed call with what the provider said.
     */
    @Test
    void aFullTextExtractionWhoseModelFailsIsRecordedWithItsFailedCalls() {
        String sonnet = "anthropic/claude-sonnet-5.5";
        outcome = response(Status.FAILED, new IngestionResponse.Fallback("UNSUPPORTED_WORKBOOK",
                "Unsupported filing: Profit or loss uses the 'by nature' template", sonnet, List.of(), 0, List.of(),
                List.of("Full-text extraction: the model call failed (stream was reset: CANCEL)"), List.of(),
                "IllegalStateException: no usable answer"));
        when(jobs.failedModelCalls(any())).thenReturn(List.of(
                new IngestionJob.FailedCall("FULL_TEXT", sonnet, "no model response within 150 s", 150_000, java.time.Instant.now()),
                new IngestionJob.FailedCall("FULL_TEXT", sonnet, "stream was reset: CANCEL", 61_000, java.time.Instant.now())));
        queue = queue(1);
        submit("NSSS-2025.xlsx");

        queue.start();

        ArgumentCaptor<NewError> error = ArgumentCaptor.forClass(NewError.class);
        verify(errors, timeout(10_000)).record(error.capture());
        assertThat(error.getValue().type()).isEqualTo(IngestionErrorRepository.Type.UNSUPPORTED_WORKBOOK);
        assertThat(error.getValue().fallbackUsed()).isTrue();
        assertThat(error.getValue().finalStatus()).isEqualTo("FAILED");
        Map<?, ?> details = (Map<?, ?>) error.getValue().details();
        assertThat(details.get("fullTextExtraction")).extracting("model").isEqualTo(sonnet);
        assertThat(details.get("fullTextExtraction")).extracting("error").isEqualTo("IllegalStateException: no usable answer");
        assertThat(details.get("failedModelCalls")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2).extracting("error")
                .containsExactly("no model response within 150 s", "stream was reset: CANCEL");
    }

    /** Failed calls of an upload the full-text extraction still rescued are in its row as well. */
    @Test
    void theFailedCallsOfARescuedUploadAreRecordedToo() {
        outcome = response(Status.COMPLETED, new IngestionResponse.Fallback("INCOMPLETE", "open items",
                "anthropic/claude-sonnet-5.5", List.of("PRIOR_PERIOD"), 2, List.of("PRIOR_PERIOD 2024 FY income_statement"),
                List.of(), List.of(), null));
        when(jobs.failedModelCalls(any())).thenReturn(List.of(new IngestionJob.FailedCall("FULL_TEXT",
                "anthropic/claude-sonnet-5.5", "HTTP 529 overloaded", 900, java.time.Instant.now())));
        queue = queue(1);
        submit("TINS-2025.xlsx");

        queue.start();

        ArgumentCaptor<NewError> error = ArgumentCaptor.forClass(NewError.class);
        verify(errors, timeout(10_000)).record(error.capture());
        assertThat(error.getValue().finalStatus()).isEqualTo("SUCCEEDED");
        assertThat(((Map<?, ?>) error.getValue().details()).get("failedModelCalls"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).singleElement()
                .extracting("error").isEqualTo("HTTP 529 overloaded");
    }

    @Test
    void aFailedUploadIsRecordedInTheErrorLog() {
        outcome = response(Status.FAILED, null);
        queue = queue(1);
        submit("HRTA-1.xlsx");

        queue.start();

        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.FAILED), eq("AI agent failed on HRTA null"), eq("model offline"), any());
        ArgumentCaptor<NewError> error = ArgumentCaptor.forClass(NewError.class);
        verify(errors, timeout(10_000)).record(error.capture());
        assertThat(error.getValue().type()).isEqualTo(IngestionErrorRepository.Type.EXTRACTION_FAILED);
        assertThat(error.getValue().message()).isEqualTo("model offline");
        assertThat(error.getValue().finalStatus()).isEqualTo("FAILED");
    }

    /** A filing holds no prices: once it is stored, the company's prices are queued for the user who uploaded it. */
    @Test
    void aStoredFilingQueuesThePricesOfItsCompany() {
        Requester uploader = new Requester(7, "analyst");
        queue = queueWithPrices();
        assertThat(queue.submit("DSNG-2025.xlsx".getBytes(StandardCharsets.UTF_8), "DSNG-2025.xlsx", "application/test",
                "DSNG", uploader).created()).isTrue();

        queue.start();

        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        verify(prices, timeout(10_000)).queue(Exchange.IDX, "DSNG", uploader);
    }

    /** Open items do not stand in the way: what was stored is valued as well. */
    @Test
    void anIncompleteUploadQueuesThePricesToo() {
        outcome = response(Status.INCOMPLETE, null);
        queue = queueWithPrices();
        submit("TINS-2025.xlsx");

        queue.start();

        verify(prices, timeout(10_000)).queue(Exchange.IDX, "TINS", null);
    }

    @Test
    void aFailedUploadQueuesNoPrices() {
        outcome = response(Status.FAILED, null);
        queue = queueWithPrices();
        submit("HRTA-1.xlsx");

        queue.start();

        verify(errors, timeout(10_000)).record(any());
        verify(prices, org.mockito.Mockito.after(200).never()).queue(any(), any(), any());
    }

    /** The upload's outcome never depends on the price queue. */
    @Test
    void aPriceQueueFailureLeavesTheUploadAsItEnded() {
        when(prices.queue(any(), any(), any())).thenThrow(new IllegalStateException("price queue stopped"));
        queue = queueWithPrices();
        submit("HRTA-1.xlsx");
        submit("INDF-1.xlsx");

        queue.start();

        // the worker survives: both uploads end SUCCEEDED and none is failed afterwards
        verify(jobs, timeout(10_000).times(2)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), any());
        verify(jobs, org.mockito.Mockito.after(200).never()).finish(any(), eq(IngestionJobStatus.FAILED), any(), any(), any());
    }

    private FinancialStatementQueue queueWithPrices() {
        return new FinancialStatementQueue(service, files, jobs, errors, usage, Duration.ofMinutes(5), false,
                new JobProperties(1), prices, null);
    }

    private FinancialStatementQueue queueWithVectorStore() {
        return new FinancialStatementQueue(service, files, jobs, errors, usage, Duration.ofMinutes(5), false,
                new JobProperties(1), null, rag);
    }

    /**
     * After the statements, the workbook's text goes into the vector store as a document of the filing's company: the
     * stored file and its SHA-256; the outcome is the job result's {@code rag} and the end of its stage.
     */
    @Test
    void anUploadStoresTheWorkbookTextInTheVectorStore() {
        XbrlRagService.Result stored = new XbrlRagService.Result(XbrlRagService.Outcome.STORED, 42L, "HRTA-1", 3, 12,
                15_000, "openai/text-embedding-3-small", null);
        when(rag.enabled()).thenReturn(true);
        when(rag.store(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(stored);
        queue = queueWithVectorStore();
        submit("HRTA-1.xlsx");

        queue.start();

        ArgumentCaptor<String> stage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), stage.capture(), any(), result.capture());
        verify(rag).store(any(java.util.UUID.class), eq("HRTA"), eq(1L), eq("HRTA-1.xlsx"),
                eq(IngestionFileRepository.sha256("HRTA-1.xlsx".getBytes(StandardCharsets.UTF_8))), any(), any());
        assertThat(stage.getValue()).startsWith("Stored and verified HRTA").endsWith("; vector store: 12 chunks");
        assertThat(((IngestionResponse) result.getValue()).rag()).isEqualTo(stored);
    }

    /** The vector store never changes the upload's outcome: a failed upload is offered too, a failure is only told. */
    @Test
    void aVectorStoreFailureLeavesTheUploadAsItEnded() {
        when(rag.enabled()).thenReturn(true);
        when(rag.store(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(new XbrlRagService.Result(
                XbrlRagService.Outcome.FAILED, null, "HRTA-1", 0, 0, 0, null, "The embedding API answered HTTP 401"));
        queue = queueWithVectorStore();
        submit("HRTA-1.xlsx");
        queue.start();

        ArgumentCaptor<String> stage = ArgumentCaptor.forClass(String.class);
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), stage.capture(), any(), any());
        assertThat(stage.getValue()).endsWith("; vector store: failed");

        outcome = response(Status.FAILED, null);
        submit("INDF-1.xlsx");
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.FAILED), any(), any(), any());
        verify(rag, timeout(10_000)).store(any(), eq("INDF"), anyLong(), any(), any(), any(), any());
    }

    @Test
    void theVectorStoreSwitchedOffIsLeftAlone() {
        when(rag.enabled()).thenReturn(false);
        queue = queueWithVectorStore();
        submit("HRTA-1.xlsx");

        queue.start();

        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(jobs, timeout(10_000)).finish(any(), eq(IngestionJobStatus.SUCCEEDED), any(), any(), result.capture());
        verify(rag, org.mockito.Mockito.never()).store(any(), any(), anyLong(), any(), any(), any(), any());
        assertThat(((IngestionResponse) result.getValue()).rag()).isNull();
    }

    private FinancialStatementQueue queue(int workers) {
        return queue(workers, false);
    }

    private FinancialStatementQueue queue(int workers, boolean sameCompanyInOrder) {
        return new FinancialStatementQueue(service, files, jobs, errors, usage, Duration.ofMinutes(5), sameCompanyInOrder,
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
