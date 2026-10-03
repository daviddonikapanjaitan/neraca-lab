# Neraca Lab - Ingestion Jobs and Stored Uploads (v1)

Every ingestion runs asynchronously in the background and records its progress in the database:

| Ingestion                       | Start                                          | Worker                                                     |
|---------------------------------|------------------------------------------------|------------------------------------------------------------|
| Financial statement (`.xlsx`)   | `POST /api/v1/financial-statements/upload`     | `FinancialStatementQueue`: one thread, AI agent ([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md)) |
| Daily prices (Yahoo Finance)    | `POST /api/v1/prices/ingestions?exchange=&ticker=` | `PriceIngestionQueue`: one thread ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)) |

Both write `ingestion_job` (one row per process); `GET /api/v1/ingestions` lists them. The
frontend page `/ingestion` ([FRONTEND_DOCS.md](FRONTEND_DOCS.md)) uses these APIs.

Code: `backend/src/main/java/com/neracalab/backend/`

| Class                                   | Role                                                                                 |
|-----------------------------------------|--------------------------------------------------------------------------------------|
| `job/IngestionJobRepository`            | `ingestion_job` reads / writes; results stored as JSONB                              |
| `job/controller/IngestionJobController` | `GET /api/v1/ingestions`, `GET /api/v1/ingestions/{id}`                              |
| `job/controller/IngestionFileController`| `GET /api/v1/ingestions/{id}/file`: download of the uploaded workbook                |
| `job/IngestionJobRecovery`              | at startup: fails jobs left active by the previous run                               |
| `ingestion/file/IngestionFileRepository`| `ingestion_file`: workbook bytes, SHA-256 checksum, store-once                       |
| `ingestion/FinancialStatementQueue`     | stores the upload, records the job, runs the AI agent in a background thread          |
| `price/PriceIngestionTracker`           | records every state change of a price job in `ingestion_job`                          |

## 1. Upload: stored once per checksum

```bash
curl -F "file=@data/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

1. The request checks the file (not empty, `.xlsx` name, readable IDX XBRL workbook in a supported
   template, about a second). A wrong file is rejected with **422** and nothing is stored.
2. The SHA-256 of the bytes is computed. A new checksum stores the file in `ingestion_file`
   (`BYTEA`). A known checksum stores nothing: the existing row is reused (`file.reused = true`).
   The checksum column is unique, so two identical uploads at the same moment also store one row.
3. If the same stored file is already QUEUED or RUNNING, that job is returned (**200**). Otherwise a
   new job is recorded as QUEUED and returned (**202**, `Location: /api/v1/ingestions/{id}`).
4. The worker reads the bytes from `ingestion_file`, parses the workbook again and runs the AI agent.
   Re-uploading a known file therefore extracts the data again from the stored copy.

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

The job `result` is the agent's full audit trail (the former synchronous response: filing, plan,
steps, tool calls, rounds, saved rows, verification, metrics).

## 2. API

```bash
curl "http://localhost:8080/api/v1/ingestions?limit=20"                       # all types
curl "http://localhost:8080/api/v1/ingestions?type=PRICE&status=QUEUED,RUNNING" # filtered
curl  http://localhost:8080/api/v1/ingestions/{id}                            # one job, with result
curl -OJ http://localhost:8080/api/v1/ingestions/{id}/file                    # download the uploaded file
```

| Parameter | Default | Values                                                                                       |
|-----------|---------|----------------------------------------------------------------------------------------------|
| `type`    | all     | `FINANCIAL_STATEMENT`, `PRICE` (case-insensitive)                                            |
| `status`  | all     | comma-separated: `QUEUED`, `RUNNING`, `WAITING_RATE_LIMIT`, `SUCCEEDED`, `INCOMPLETE`, `FAILED` |
| `limit`   | 50      | 1-500                                                                                        |

Unknown values and a limit out of range: 400 (`title: "Invalid parameter"`); unknown id: 404.

```json
{
  "counts": { "QUEUED": 0, "RUNNING": 1, "WAITING_RATE_LIMIT": 0, "SUCCEEDED": 12, "INCOMPLETE": 0, "FAILED": 1 },
  "active": 1,
  "limit": 50,
  "jobs": [
    {
      "id": "701b5b9b-8d18-41f0-9250-6ed0cae50c30", "type": "FINANCIAL_STATEMENT", "status": "RUNNING",
      "stage": "AI agent is storing HRTA 2026 H1 (usually 1-4 minutes)", "exchange": "IDX", "ticker": "HRTA",
      "file": { "fileId": 7, "fileName": "FinancialStatement-2026-II-HRTA.xlsx", "sizeBytes": 523787,
                "checksumSha256": "77372cab...", "reused": true },
      "fullHistory": null, "attempts": 1, "message": null,
      "requestedAt": "2026-10-03T07:36:09.401Z", "startedAt": "2026-10-03T07:36:09.420Z",
      "finishedAt": null, "resumeAt": null, "updatedAt": "2026-10-03T07:36:10.002Z", "result": null
    }
  ]
}
```

| Field         | Meaning                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------|
| `counts`      | jobs per status (all jobs of `type`, not only the listed ones)                                |
| `active`      | QUEUED + RUNNING + WAITING_RATE_LIMIT                                                         |
| `stage`       | current step of an active job, or a one-line summary of a finished one                        |
| `ticker`      | for an upload: known once the workbook has been read                                          |
| `file`        | uploads only; `reused` = the content was already stored (same checksum)                       |
| `fullHistory` | price jobs only (`full=true`)                                                                 |
| `attempts`    | runs of the job (more than 1 after price provider rate-limit waits)                           |
| `message`     | why the job failed, is waiting (rate limit) or is incomplete                                  |
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

## 3. Restarts

The queues are in memory. At startup, before the web server accepts requests,
`IngestionJobRecovery` marks every job still QUEUED / RUNNING / WAITING_RATE_LIMIT as `FAILED`
(`stage: Interrupted`). Submit it again; an uploaded file is not stored twice. The database is meant
for one backend instance: a second instance (or a test run) on the same database also fails the jobs
the first one is still running.

## 4. Tables (`V1.0.7__schema_ingestion.sql`)

`ingestion_file`: `file_id`, `file_name` (first upload), `content_type`, `size_bytes`,
`checksum_sha256` (unique, lower-case hex), `content` (`BYTEA`), `created_at`.

`ingestion_job`: `job_id` (UUID), `job_type`, `status`, `stage`, `exchange`, `ticker`, `file_id`
(FK `ingestion_file`, required for uploads), `file_name` (this upload), `file_reused`,
`full_history`, `attempts`, `message`, `result` (`JSONB`), `requested_at`, `started_at`,
`finished_at`, `resume_at`, `updated_at`. Details: [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md), section 4.8.

## 5. Tests

| Test                            | What it proves                                                                                                   |
|---------------------------------|------------------------------------------------------------------------------------------------------------------|
| `FinancialStatementUploadTest`  | upload returns 202 at once, the background job records stages and the result, the same file is stored once and reused, wrong files are rejected without storing, list filters / counts / errors (AI agent mocked) |
| `FinancialStatementUploadTest` (download) | the downloaded bytes equal the upload, type / size / checksum headers, each job's own file name for one stored file, 404 / 400 |
| `PriceIngestionControllerTest`  | a price job is recorded in `ingestion_job` with the same final state as the queue                                |

Both tests delete the rows they create. Because the application startup fails jobs left active,
run the tests against a separate database when a backend on the same database is processing jobs
(`DB_URL=jdbc:postgresql://localhost:5432/neracalab_test ./mvnw test`).
