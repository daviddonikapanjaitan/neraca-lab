package com.neracalab.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.file.IngestionFileRepository;

/**
 * The cost of an upload job as the API returns it: the model calls recorded for the job ({@code llm_usage.job_id})
 * summed per job (the jobs table) and per stage and model (the job details). Runs against the Docker Postgres;
 * rolled back.
 */
@SpringBootTest
@Transactional
class IngestionJobCostTest {

    private static final String DEEPSEEK = "deepseek/deepseek-v4-flash-0731";
    private static final String HAIKU = "anthropic/claude-haiku-5.5";

    @Autowired
    private IngestionJobRepository jobs;

    @Autowired
    private IngestionFileRepository files;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theModelCallsOfAnUploadAreSummedPerJobAndPerStageAndModel() {
        UUID job = upload("cost-test-a.xlsx");
        // the standard extraction (DeepSeek): a plan, two tool turns of which the first attempt failed, a review
        jobs.recordModelCall(job, call("PLAN", DEEPSEEK, 2000, 400, 0, 0, 0.00054, false, null));
        jobs.recordModelCall(job, call("EXECUTE", DEEPSEEK, 0, 0, 0, 0, 0, false, "stream was reset: CANCEL"));
        jobs.recordModelCall(job, call("EXECUTE", DEEPSEEK, 5000, 300, 0, 1500, 0.00045, false, null));
        jobs.recordModelCall(job, call("EXECUTE", DEEPSEEK, 6000, 200, 0, 4000, 0.00034, false, null));
        jobs.recordModelCall(job, call("REVIEW", DEEPSEEK, 3000, 150, 0, 0, 0.00023, false, null));
        // the full-text extraction (Haiku): two answers, the second without a reported cost
        jobs.recordModelCall(job, call("FULL_TEXT", HAIKU, 40000, 6000, 10, 0, 0.007, false, null));
        jobs.recordModelCall(job, call("FULL_TEXT", HAIKU, 47000, 5000, 20, 38000, 0.0072, true, null));

        IngestionJob found = jobs.find(job).orElseThrow();

        IngestionJob.Cost cost = found.cost();
        assertThat(cost.modelCalls()).isEqualTo(7);
        assertThat(cost.failedCalls()).isEqualTo(1);
        assertThat(cost.promptTokens()).isEqualTo(103_000);
        assertThat(cost.completionTokens()).isEqualTo(12_050);
        assertThat(cost.reasoningTokens()).isEqualTo(30);
        assertThat(cost.cachedTokens()).isEqualTo(43_500);
        assertThat(cost.costUsd()).isEqualTo(0.01576, within(1e-9));
        assertThat(cost.costEstimated()).isTrue();

        // per stage and model, in the order of the calls
        assertThat(found.usage()).extracting(IngestionJob.Usage::stage).containsExactly("PLAN", "EXECUTE", "REVIEW", "FULL_TEXT");
        IngestionJob.Usage execute = found.usage().get(1);
        assertThat(execute.model()).isEqualTo(DEEPSEEK);
        assertThat(execute.calls()).isEqualTo(3);
        assertThat(execute.errors()).isEqualTo(1);
        assertThat(execute.promptTokens()).isEqualTo(11_000);
        assertThat(execute.cachedTokens()).isEqualTo(5_500);
        assertThat(execute.costUsd()).isEqualTo(0.00079, within(1e-9));
        assertThat(execute.costEstimated()).isFalse();
        IngestionJob.Usage fullText = found.usage().get(3);
        assertThat(fullText.model()).isEqualTo(HAIKU);
        assertThat(fullText.calls()).isEqualTo(2);
        assertThat(fullText.completionTokens()).isEqualTo(11_000);
        assertThat(fullText.costUsd()).isEqualTo(0.0142, within(1e-9));
        assertThat(fullText.costEstimated()).isTrue();
        // the parts add up to the job's cost
        assertThat(found.usage().stream().mapToDouble(IngestionJob.Usage::costUsd).sum()).isEqualTo(cost.costUsd(), within(1e-9));
        assertThat(found.usage().stream().mapToInt(IngestionJob.Usage::calls).sum()).isEqualTo(cost.modelCalls());
    }

    @Test
    void theJobsTableCarriesTheCostOfEachJobAndNoneWhereNoModelWasCalled() {
        UUID costly = upload("cost-test-b.xlsx");
        UUID other = upload("cost-test-c.xlsx");
        UUID none = upload("cost-test-d.xlsx");
        jobs.recordModelCall(costly, call("PLAN", DEEPSEEK, 1000, 100, 0, 0, 0.0002, false, null));
        jobs.recordModelCall(costly, call("FULL_TEXT", HAIKU, 30000, 4000, 0, 0, 0.005, false, null));
        jobs.recordModelCall(other, call("PLAN", DEEPSEEK, 500, 50, 0, 0, 0.0001, false, null));

        List<IngestionJob> listed = jobs.list(IngestionJobType.FINANCIAL_STATEMENT, List.of(), 500, 0);

        IngestionJob a = listed.stream().filter(j -> j.id().equals(costly)).findFirst().orElseThrow();
        assertThat(a.cost().modelCalls()).isEqualTo(2);
        assertThat(a.cost().costUsd()).isEqualTo(0.0052, within(1e-9));
        assertThat(a.cost().promptTokens()).isEqualTo(31_000);
        assertThat(a.usage()).isNull();     // the breakdown is only part of a single job
        // the calls of one job do not count for another
        IngestionJob b = listed.stream().filter(j -> j.id().equals(other)).findFirst().orElseThrow();
        assertThat(b.cost().modelCalls()).isEqualTo(1);
        assertThat(b.cost().costUsd()).isEqualTo(0.0001, within(1e-9));
        // a job without model calls (not started yet, or stored before the cost was recorded) has no cost
        IngestionJob c = listed.stream().filter(j -> j.id().equals(none)).findFirst().orElseThrow();
        assertThat(c.cost()).isNull();
        assertThat(jobs.find(none).orElseThrow().usage()).isNull();
    }

    @Test
    void aLongErrorIsShortenedAndTheCallsGoWithTheJob() {
        UUID job = upload("cost-test-e.xlsx");
        jobs.recordModelCall(job, call("EXECUTE", DEEPSEEK, 0, 0, 0, 0, 0, false, "x".repeat(900)));

        assertThat(jdbc.sql("SELECT length(error) FROM llm_usage WHERE job_id = :id").param("id", job)
                .query(Integer.class).single()).isEqualTo(500);
        assertThat(jdbc.sql("SELECT run_id IS NULL AND analysis_id IS NULL FROM llm_usage WHERE job_id = :id")
                .param("id", job).query(Boolean.class).single()).isTrue();

        // the failed calls of the job, for the error log
        jobs.recordModelCall(job, call("FULL_TEXT", HAIKU, 30000, 4000, 0, 0, 0.005, false, null));
        jobs.recordModelCall(job, call("FULL_TEXT", HAIKU, 0, 0, 0, 0, 0, false, "no model response within 150 s"));
        assertThat(jobs.failedModelCalls(job)).extracting(IngestionJob.FailedCall::stage, IngestionJob.FailedCall::model)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EXECUTE", DEEPSEEK),
                        org.assertj.core.groups.Tuple.tuple("FULL_TEXT", HAIKU));
        assertThat(jobs.failedModelCalls(job).get(1).error()).isEqualTo("no model response within 150 s");
        assertThat(jobs.failedModelCalls(job).get(1).durationMs()).isEqualTo(1234);
        assertThat(jobs.failedModelCalls(UUID.randomUUID())).isEmpty();

        // deleting the job deletes its model calls (ON DELETE CASCADE)
        jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", job).update();
        assertThat(jdbc.sql("SELECT count(*) FROM llm_usage WHERE job_id = :id").param("id", job)
                .query(Integer.class).single()).isZero();
    }

    private UUID upload(String fileName) {
        UUID id = UUID.randomUUID();
        var file = files.store(("cost test " + id).getBytes(StandardCharsets.UTF_8), fileName, "application/test");
        jobs.insertUpload(new IngestionJobRepository.NewUpload(id, file.fileId(), fileName, false, "Queued", null));
        return id;
    }

    private static IngestionJob.ModelCall call(String stage, String model, int prompt, int completion, int reasoning,
                                               int cached, double cost, boolean estimated, String error) {
        return new IngestionJob.ModelCall(stage, "TEST", model, prompt, completion, reasoning, cached, cost, estimated,
                1234, error);
    }
}
