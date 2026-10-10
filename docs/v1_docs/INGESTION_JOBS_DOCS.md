# Neraca Lab - Ingestion Jobs and Stored Uploads (v1)

Every ingestion runs asynchronously in the background and records its progress in the database:

| Ingestion                       | Start                                          | Worker                                                     |
|---------------------------------|------------------------------------------------|------------------------------------------------------------|
| Financial statement (`.xlsx`)   | `POST /api/v1/financial-statements/upload`     | `FinancialStatementQueue`: one thread, AI agent ([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md)) |
| Daily prices (Yahoo Finance)    | `POST /api/v1/prices/ingestions?exchange=&ticker=` | `PriceIngestionQueue`: one thread ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)) |
| Screening data ETL (Yahoo Finance) | `POST /api/v1/fundamentals/ingestions?exchange=` | `FundamentalsQueue`: one thread ([SCREENING_DOCS.md](SCREENING_DOCS.md)) |
| AI stock screening              | `POST /api/v1/screenings`                      | `ScreeningQueue`: one thread ([SCREENING_DOCS.md](SCREENING_DOCS.md)) |
| RAG PDF document (`.pdf`)       | `POST /api/v1/rag/pdf`                         | `RagQueue`: one thread for both RAG types ([RAG_DOCS.md](RAG_DOCS.md)) |
| RAG news of a date range        | `POST /api/v1/rag/news?exchange=&ticker=&from=&to=` | `RagQueue` ([RAG_DOCS.md](RAG_DOCS.md))                |
| AI analysis of one stock        | `POST /api/v1/analyses`                        | `AnalysisQueue`: one thread ([ANALYSIS_DOCS.md](ANALYSIS_DOCS.md)) |

All of them write `ingestion_job` (one row per process; `job_type` `FINANCIAL_STATEMENT`, `PRICE`, `FUNDAMENTALS`, `SCREENING`, `RAG_PDF`, `RAG_NEWS`, `ANALYSIS`); `GET /api/v1/ingestions` lists them. The
frontend Ingestion pages (`/ingestion/xbrl`, `/ingestion/prices`, `/ingestion/screening-data`, `/ingestion/rag-pdf`, `/ingestion/rag-news`, [FRONTEND_DOCS.md](FRONTEND_DOCS.md)) use these APIs. Every API here
needs the `INGESTION` permission, except the screening and analysis APIs (`SCREENING`). Every API needs a login: `AUTH="Authorization: Bearer <token>"` from `POST /api/v1/auth/login`
([AUTH_DOCS.md](AUTH_DOCS.md), section 3).

Code: `backend/src/main/java/com/neracalab/backend/`

| Class                                   | Role                                                                                 |
|-----------------------------------------|--------------------------------------------------------------------------------------|
| `job/IngestionJobRepository`            | `ingestion_job` reads / writes; results stored as JSONB                              |
| `job/controller/IngestionJobController` | `GET /api/v1/ingestions`, `GET /api/v1/ingestions/{id}`                              |
| `job/controller/IngestionFileController`| `GET /api/v1/ingestions/{id}/file`: download of the uploaded workbook or RAG PDF (`application/pdf`) |
| `job/IngestionJobRecovery`              | at startup: fails jobs left active by the previous run                               |
| `ingestion/file/IngestionFileRepository`| `ingestion_file`: workbook / PDF bytes, SHA-256 checksum, store-once                 |
| `ingestion/FinancialStatementQueue`     | stores the upload, records the job, runs the AI agent in a background thread          |
| `price/PriceIngestionTracker`           | records every state change of a price job in `ingestion_job`                          |

## 1. Upload: stored once per checksum

```bash
curl -H "$AUTH" -F "file=@data/IDX_XBRL/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

1. The request checks the file (not empty, `.xlsx` name, readable IDX XBRL workbook in a supported
   template, about a second). A wrong file is rejected with **422** and nothing is stored.
2. The SHA-256 of the bytes is computed. A new checksum stores the file in `ingestion_file`
   (`BYTEA`). A known checksum stores nothing: the existing row is reused (`file.reused = true`).
   The checksum column is unique, so two identical uploads at the same moment also store one row.
3. If the same stored file is already QUEUED or RUNNING, that job is returned (**200**). Otherwise a
   new job is recorded as QUEUED and returned (**202**, `Location: /api/v1/ingestions/{id}`).
4. A worker reads the bytes from `ingestion_file`, parses the workbook again and runs the AI agent.
   Re-uploading a known file therefore extracts the data again from the stored copy. Several
   uploads are stored at the same time (section 4).

| HTTP | Meaning                                                                         |
|------|---------------------------------------------------------------------------------|
| 202  | new job (QUEUED)                                                                |
| 200  | the same file is already queued / being stored: that job                        |
| 422  | not an `.xlsx`, not an IDX XBRL workbook, unsupported template (ProblemDetail)  |
| 413  | larger than 20 MB                                                               |

Upload stages: `Waiting in the upload queue` -> `Reading the workbook` -> `AI agent is storing HRTA 2026 H1`
-> final status:

| Final status | When                                                                                    |
|--------------|-----------------------------------------------------------------------------------------|
| `SUCCEEDED`  | agent `COMPLETED`: everything stored and verified by the database read-back             |
| `INCOMPLETE` | agent `INCOMPLETE`: stored, the verification lists pending items / problems (`message`) |
| `FAILED`     | the workbook could not be read, or the agent failed (AI provider unreachable, ...)      |
| `FAILED`     | the job ran longer than `neracalab.ingestion.job-timeout` (5 minutes, from the start of the run; queue time not counted): stage `Stopped after the 5 minutes limit on <filing> (...)`; what was saved before the limit is kept |

A typical upload job takes 1-2 minutes (the AI model runs without reasoning; a model call stalled for 45 s is
retried, and a model that keeps failing mid-run no longer fails the job: the remaining standard steps are done
deterministically and verified), see [AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 1.

The job `result` is the agent's full audit trail (the former synchronous response: filing, plan,
steps, tool calls, rounds, saved rows, verification, metrics).

## 2. API

```bash
curl -H "$AUTH" "http://localhost:8080/api/v1/ingestions?limit=20"                       # all types
curl -H "$AUTH" "http://localhost:8080/api/v1/ingestions?type=PRICE&limit=10&offset=10"   # second page of 10
curl -H "$AUTH" "http://localhost:8080/api/v1/ingestions?type=PRICE&status=QUEUED,RUNNING" # filtered
curl -H "$AUTH"  http://localhost:8080/api/v1/ingestions/{id}                            # one job, with result
curl -H "$AUTH" -OJ http://localhost:8080/api/v1/ingestions/{id}/file                    # download the uploaded file
```

| Parameter | Default | Values                                                                                       |
|-----------|---------|----------------------------------------------------------------------------------------------|
| `type`    | all     | `FINANCIAL_STATEMENT`, `PRICE`, `FUNDAMENTALS`, `SCREENING`, `RAG_PDF`, `RAG_NEWS`, `ANALYSIS` (case-insensitive) |
| `status`  | all     | comma-separated: `QUEUED`, `RUNNING`, `WAITING_RATE_LIMIT`, `SUCCEEDED`, `INCOMPLETE`, `FAILED` |
| `limit`   | 50      | 1-500: jobs per page                                                                         |
| `offset`  | 0       | jobs skipped (most recent first): page `n` (1-based) of `limit` jobs is `offset = (n - 1) * limit` |

Unknown values, a limit out of range and a negative offset: 400 (`title: "Invalid parameter"`); an
offset past the last job gives an empty `jobs`; unknown id: 404.

```json
{
  "counts": { "QUEUED": 0, "RUNNING": 1, "WAITING_RATE_LIMIT": 0, "SUCCEEDED": 12, "INCOMPLETE": 0, "FAILED": 1 },
  "active": 1,
  "total": 14,
  "limit": 50,
  "offset": 0,
  "jobs": [
    {
      "id": "701b5b9b-8d18-41f0-9250-6ed0cae50c30", "type": "FINANCIAL_STATEMENT", "status": "RUNNING",
      "stage": "AI agent is storing HRTA 2026 H1 (usually 1-2 minutes, stopped after 5 minutes)", "exchange": "IDX", "ticker": "HRTA",
      "file": { "fileId": 7, "fileName": "FinancialStatement-2026-II-HRTA.xlsx", "sizeBytes": 523787,
                "checksumSha256": "77372cab...", "reused": true },
      "fullHistory": null, "attempts": 1, "message": null,
      "requestedAt": "2026-10-03T07:36:09.401Z", "startedAt": "2026-10-03T07:36:09.420Z",
      "finishedAt": null, "resumeAt": null, "updatedAt": "2026-10-03T07:36:10.002Z",
      "createdBy": { "userId": 3, "username": "budi", "fullName": "Budi Santoso" }, "result": null
    }
  ]
}
```

| Field         | Meaning                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------|
| `counts`      | jobs per status (all jobs of `type`, not only the listed ones)                                |
| `active`      | QUEUED + RUNNING + WAITING_RATE_LIMIT                                                         |
| `total`       | jobs matching `type` and `status` (every page together; the sum of the matching `counts`)    |
| `limit`, `offset` | the page asked for; `jobs` holds at most `limit` jobs from `offset`                       |
| `stage`       | current step of an active job, or a one-line summary of a finished one                        |
| `ticker`      | for an upload: known once the workbook has been read                                          |
| `file`        | uploads only; `reused` = the content was already stored (same checksum)                       |
| `fullHistory` | price jobs only (`full=true`)                                                                 |
| `attempts`    | runs of the job (more than 1 after price provider rate-limit waits)                           |
| `message`     | why the job failed, is waiting (rate limit) or is incomplete                                  |
| `createdBy`   | who started the job (section 3); `null` for a scheduled price run                             |
| `result`      | only in `GET /api/v1/ingestions/{id}`: agent audit trail (upload) or price result (prices)    |

The list is read from the database, so it survives restarts and keeps every job;
`GET /api/v1/prices/ingestions` still reports the in-memory price queue.

### Download

`GET /api/v1/ingestions/{id}/file` returns the workbook of an upload job exactly as stored in
`ingestion_file` (byte for byte, the same SHA-256 as at upload).

| Header                | Value                                                                                   |
|-----------------------|-----------------------------------------------------------------------------------------|
| `Content-Type`        | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`                     |
| `Content-Disposition` | `attachment` with the file name of **this job's** upload (`filename` and UTF-8 `filename*`) |
| `Content-Length`      | size in bytes                                                                           |
| `ETag`, `X-Checksum-SHA256` | SHA-256 of the content                                                            |

The same content uploaded twice under different names is stored once, but each job downloads under
its own upload's name. 404 (`title: "File not found"`) for an unknown job and for a price job (no
file); 400 for an id that is not a UUID.

## 3. Who started a job

Every job records the logged-in user who uploaded the workbook or requested the prices:
`ingestion_job.created_by` (user id) and `created_by_username` (the username at that time). The
API returns them as `createdBy`:

| `createdBy`                                        | Meaning                                                        |
|----------------------------------------------------|----------------------------------------------------------------|
| `{userId, username, fullName}`                     | started by this user (`fullName` = the user's current name)    |
| `{userId: null, username, fullName: null}`         | started by a user who has been deleted since (name kept)       |
| `null`                                             | a scheduled price run (`neracalab.prices.schedule`), no user   |

When an identical upload or a price request for a company finds a job already queued / running,
that job is returned and keeps its own requester. The Ingestion pages show "by <username>" in the
jobs table and "Started by" in the job details.

Jobs recorded before this column existed were attributed **once** to the root user `admin`: at
startup, after the root user is ensured, `IngestionCreatorBackfill` sets their creator and records
the migration in `app_migration`, so it never runs again (later scheduled runs stay `null`).

## 4. Workers and restarts

Each ingestion queue has `neracalab.jobs.workers` worker threads (default 5, env `INGESTION_WORKERS`):
that many of its jobs run at the same time and the others wait as QUEUED. To run 6 at a time, set
`workers: 6` under `neracalab.jobs` in `application.yaml` (or `INGESTION_WORKERS=6` in `backend/.env`)
and restart the backend.

| Queue (job type)                              | Runs at the same time                     | Still one after the other                                                                 |
|-----------------------------------------------|-------------------------------------------|-------------------------------------------------------------------------------------------|
| Financial statement uploads (`FINANCIAL_STATEMENT`) | any filings, also several of one company | the write steps of filings of one company (never two at the same moment); the recalculation of the derived data |
| Prices (`PRICE`)                              | different companies (one job per company) | the requests to the provider: one at a time with the 1-2 s pause; an HTTP 429 pauses every worker |
| RAG (`RAG_PDF`, `RAG_NEWS`)                   | different documents / companies           | the requests to one news site                                                             |
| Screening data (`FUNDAMENTALS`)               | different exchanges (one run per exchange) | the requests to Yahoo Finance                                                            |

**Filings of one company.** Uploaded together (for example five years of MAPA) they are stored at the
same time, each by its own agent. Every write step of an agent (save the statements of a column, save
its revenue segments, save share counts, register the company) holds the company's write lock, so the
stored data is what storing the filings one after the other in some order gives: the period's own
filing replaces, comparatives only fill gaps. A share split restated by a later filing ends on the basis
after the split whatever the order ([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md), section 4). One thing can
differ from storing them one by one: a job may end `INCOMPLETE` because it verified a period while the
period's own filing was between two of its steps (revenue already replaced, segments not yet): upload
that file again.

`neracalab.ingestion.same-company-in-order=true` (env `INGESTION_SAME_COMPANY_IN_ORDER`) stores the
filings of one company one after the other in upload order instead (other companies still run beside
them); the waiting ones stay `QUEUED`.

More workers therefore store more uploads and documents at the same time; they do not send requests
to Yahoo Finance or a news site faster. Screening runs and analyses are not ingestion jobs: each of
those queues keeps one worker.

The queues are in memory. At startup, before the web server accepts requests,
`IngestionJobRecovery` marks every job still QUEUED / RUNNING / WAITING_RATE_LIMIT as `FAILED`
(`stage: Interrupted`). Submit it again; an uploaded file is not stored twice. The database is meant
for one backend instance: a second instance (or a test run) on the same database also fails the jobs
the first one is still running.

## 5. Tables (`V1.0.7__schema_ingestion.sql`, `V1.0.9__schema_ingestion_created_by.sql`)

`ingestion_file`: `file_id`, `file_name` (first upload), `content_type`, `size_bytes`,
`checksum_sha256` (unique, lower-case hex), `content` (`BYTEA`), `created_at`.

`ingestion_job`: `job_id` (UUID), `job_type`, `status`, `stage`, `exchange`, `ticker`, `file_id`
(FK `ingestion_file`, required for `FINANCIAL_STATEMENT` and `RAG_PDF`), `file_name` (this upload), `file_reused`,
`full_history`, `attempts`, `message`, `result` (`JSONB`), `requested_at`, `started_at`,
`finished_at`, `resume_at`, `updated_at`, `created_by` (FK `users`, `ON DELETE SET NULL`),
`created_by_username`. Details: [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md), section 4.8.

## 6. Tests

| Test                            | What it proves                                                                                                   |
|---------------------------------|------------------------------------------------------------------------------------------------------------------|
| `FinancialStatementUploadTest`  | upload returns 202 at once, the background job records stages and the result, the same file is stored once and reused, wrong files are rejected without storing, list filters / counts / errors (AI agent mocked) |
| `FinancialStatementUploadTest` (download) | the downloaded bytes equal the upload, type / size / checksum headers, each job's own file name for one stored file, 404 / 400 |
| `FinancialStatementUploadTest` (creator) | an upload is recorded as started by the uploading user; after the user is deleted the job keeps the username |
| `IngestionCreatorBackfillTest`  | existing jobs are attributed to the root user exactly once; later jobs without a user stay unattributed        |
| `PriceIngestionControllerTest`  | a price job is recorded in `ingestion_job` with the same final state and requester as the queue                  |
| `JobWorkerPoolTest`             | five workers process five jobs at the same time, never more jobs than workers, one worker keeps the order, a failing job does not stop its worker |
| `FinancialStatementQueueWorkersTest` | five filings of one company and filings of different companies are stored at the same time; with `same-company-in-order` filings of one company one after the other in upload order and a failed filing lets the next one run (scripted agent, no database) |
| `CompanyWriteLockTest`          | one write lock per company whatever the spelling of the ticker, another lock for another company; a write step waits while another filing of the company holds it |

Both tests delete the rows they create. Because the application startup fails jobs left active,
run the tests against a separate database when a backend on the same database is processing jobs
(`DB_URL=jdbc:postgresql://localhost:5432/neracalab_test ./mvnw test`).
