# Neraca Lab - Docker and start / stop scripts (v1)

The whole application (postgres, redis, backend, frontend) runs in Docker and is started and
stopped with one script per operating system. Only Docker is needed on the machine; Java, Maven
and Node.js are not (both apps are built inside Docker).

## 1. Scripts

| OS      | Start                       | Stop                       | Requirements                                            |
|---------|-----------------------------|----------------------------|---------------------------------------------------------|
| Windows | `scripts\start-windows.bat` | `scripts\stop-windows.bat` | Docker Desktop (cmd, PowerShell or double-click)        |
| macOS   | `scripts/start-mac.sh`      | `scripts/stop-mac.sh`      | Docker Desktop for Mac (Intel or Apple Silicon)         |
| Linux   | `scripts/start-linux.sh`    | `scripts/stop-linux.sh`    | Docker Engine + Compose plugin 2.20+, or Docker Desktop |

The scripts can be run from any directory (they locate the repository themselves).

```bash
./scripts/start-linux.sh      # macOS: ./scripts/start-mac.sh
./scripts/stop-linux.sh       # macOS: ./scripts/stop-mac.sh
```

```bat
scripts\start-windows.bat
scripts\stop-windows.bat
```

### 1.1 Start

`start` (the default command of the start scripts):

1. Checks that Docker is installed and running. On Windows and macOS an installed but stopped
   Docker Desktop is started and awaited (up to 180 s); on Linux the script explains how to start
   Docker, or how to fix a missing permission (`docker` group).
2. Checks Docker Compose 2.20 or newer (needed for `include`).
3. Warns (does not fail) when `backend/.env` is missing: everything works except the AI upload.
4. Checks the host ports; a port held by another program (e.g. a backend started from the IDE,
   or `npm run dev`) stops the script with the port and the variable to change it. Ports held by
   the stack's own running containers are fine.
5. Builds and starts everything: `docker compose up -d --build`.
6. Waits until all four containers are healthy (`WAIT_TIMEOUT`, default 600 s). A container that
   exits or becomes unhealthy stops the script with its last 60 log lines.
7. Prints the URLs and opens the frontend in the browser (`NO_BROWSER=1` to skip).

The first start downloads the base images and the Maven / npm dependencies (several minutes);
later starts reuse the build cache and take seconds. `start` on a running stack only rebuilds
what changed.

Other commands of the start scripts:

| Command          | Does                                                                                                |
|------------------|-----------------------------------------------------------------------------------------------------|
| `stop`           | same as the stop script                                                                             |
| `restart`        | stop, then start (e.g. after changing `backend/.env` or the code)                                   |
| `status`         | containers and their health (`docker compose ps -a`)                                                |
| `logs [service]` | follows the logs of `frontend`, `backend`, `postgres` or `redis` (all when omitted), Ctrl+C to quit |
| `help`           | usage                                                                                               |

### 1.2 Stop

The stop scripts (and the `stop` command) run `docker compose down`: the four containers and the
network are removed; the volumes `backend_postgres-data` and `backend_redis-data` are kept, so the
next start has all data. When the stack or Docker is not running they only report "nothing to
stop" and never start Docker Desktop. They take no argument except `help`; anything else is
rejected.

On Windows, double-clicking a `.bat` file keeps the window open at the end (`NO_PAUSE=1` to skip).

### 1.3 Options

Environment variables of the start scripts (`NO_PAUSE` also applies to `stop-windows.bat`):

| Variable        | Default | Meaning                                              |
|-----------------|---------|------------------------------------------------------|
| `FRONTEND_PORT` | `3000`  | host port of the frontend                            |
| `BACKEND_PORT`  | `8080`  | host port of the backend API                         |
| `POSTGRES_PORT` | `5432`  | host port of PostgreSQL                              |
| `REDIS_PORT`    | `6379`  | host port of Redis                                   |
| `NO_BROWSER`    | -       | `1`: do not open the browser after start             |
| `NO_PAUSE`      | -       | `1`: Windows only, no "press any key" at the end     |
| `WAIT_TIMEOUT`  | `600`   | seconds to wait for the containers to become healthy |

```bash
BACKEND_PORT=8081 FRONTEND_PORT=3001 ./scripts/start-linux.sh
```

```bat
set "BACKEND_PORT=8081" & set "FRONTEND_PORT=3001" & scripts\start-windows.bat
```

In cmd, keep the quotes in `set "NAME=value"`; without them the value includes the space before
`&`. Ports only change the host side: inside Docker the frontend always calls
`http://backend:8080`.

### 1.4 AI upload key

```bash
cp backend/.env.example backend/.env      # Windows: copy backend\.env.example backend\.env
# set OPENAI_API_KEY=... in backend/.env, then
./scripts/start-linux.sh restart
```

`backend/.env` is ignored by git and docker; compose passes its values to the backend container at
runtime, so they are never baked into an image.

### 1.5 Price ingestion settings

Optional, also in `backend/.env` (details: [PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)):

| Variable                 | Default | Meaning                                                              |
|--------------------------|---------|----------------------------------------------------------------------|
| `PRICE_PROVIDER`         | `yahoo` | `yahoo` (no key) or `eodhd`                                          |
| `EODHD_API_TOKEN`        | -       | required for `eodhd`                                                 |
| `PRICE_SCHEDULE_ENABLED` | `false` | `true`: queue every active IDX company at 17:30 WIB, Monday-Friday   |
| `AUTH_SESSION_TTL`       | `12h`   | how long a login stays valid                                        |
| `AUTH_ROOT_PASSWORD`     | `admin` | password of the root user `admin` when it is first created ([AUTH_DOCS.md](AUTH_DOCS.md)) |

### 1.6 AI screening settings

Also in `backend/.env` (details: [SCREENING_DOCS.md](SCREENING_DOCS.md)); the screening's models run on
the same `OPENAI_API_KEY` (OpenRouter):

| Variable                         | Default | Meaning                                                              |
|----------------------------------|---------|----------------------------------------------------------------------|
| `TAVILY_API_KEY`                 | -       | Tavily news search of the research agent (without it: crawled sites only) |
| `SCREENING_BUDGET_USD`           | `0.45`  | cost cap of one screening run                                        |
| `SCREENING_ETL_SCHEDULE_ENABLED` | `true`  | daily screening data ETL (Yahoo Finance) at 18:00 WIB, Monday-Friday |

The RAG vector store ([RAG_DOCS.md](RAG_DOCS.md)) embeds with the same `OPENAI_API_KEY`; its news
ingestion reads the news sites only (no Tavily). `RAG_EMBEDDING_MODEL` (default
`openai/text-embedding-3-small`, 1536 dimensions) can be set in `backend/.env` and passed on in
`backend/docker-compose.yaml`; another model must also have 1536 dimensions.

After changing backend code, rebuild with `docker compose up -d --build backend`; when the
container keeps the old image (compose prints `Running` instead of `Recreated`), add
`--force-recreate`.

## 2. Containers

| Service  | Container            | Image                            | Host port | Health check                                 |
|----------|----------------------|----------------------------------|-----------|----------------------------------------------|
| postgres | `neracalab-postgres` | built from `backend/postgres/Dockerfile` (`neracalab-postgres:17-pgvector`) | `5432` | `pg_isready` |
| redis    | `neracalab-redis`    | `redis:7-alpine`                 | `6379`    | `redis-cli ping`                             |
| backend  | `neracalab-backend`  | built from `backend/Dockerfile`  | `8080`    | `GET /api/v1/health` (120 s start period)    |
| frontend | `neracalab-frontend` | built from `frontend/Dockerfile` | `3000`    | `GET /login`                                 |

Start order follows the health checks: postgres and redis healthy, then the backend (its first
start runs the SQL scripts and seeds HRTA), then the frontend.

| Image    | Build                                                                                       | Runtime                                                       |
|----------|---------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| postgres | `postgres:17-alpine` + pgvector 0.8.7 compiled from its release tag (portable: no `-march=native`, no LLVM bitcode; build tools removed) | `postgres:17-alpine` with the `vector` extension (RAG vector store) |
| backend  | `maven:3.9-eclipse-temurin-21`: `mvn dependency:go-offline`, then `mvn package -DskipTests` | `eclipse-temurin:21-jre-alpine`, user `spring`, `java -jar`   |
| frontend | `node:22-alpine`: `npm ci`, `next build` with `NEXT_OUTPUT=standalone`                      | `node:22-alpine`, user `nextjs`, `node server.js` (port 3000) |

All base images are published for amd64 and arm64 (Apple Silicon, ARM Linux), and
`frontend/package-lock.json` contains the native packages for both (`@next/swc`, `lightningcss`,
`@tailwindcss/oxide`, `linux-x64-musl` / `linux-arm64-musl`).

## 3. Compose files

```text
docker-compose.yaml              full stack: include backend/docker-compose.yaml + frontend service
backend/docker-compose.yaml      postgres, redis, backend
backend/postgres/Dockerfile      the postgres image: postgres:17-alpine + pgvector
```

- The postgres image keeps the official base (`postgres:17-alpine`, same major version), so a
  database volume created before pgvector was added is used as it is: the next start builds the
  image, recreates the container on the same volume, and the backend's `V1.0.14__schema_rag.sql`
  runs `CREATE EXTENSION IF NOT EXISTS vector`. Rebuild it alone with
  `cd backend && docker compose up -d --build --no-deps postgres` (no jobs should be running).

- Both files declare the compose project `name: backend`, so the full stack and
  `cd backend && docker compose up` manage the same containers and the same volumes
  (`backend_postgres-data`, `backend_redis-data`). Use one or the other at a time.
- The root file uses `include` (Compose 2.20+); the included file is resolved from `backend/`
  (build context and `backend/.env`).
- `docker compose down` inside `backend/` only knows postgres, redis and backend; stop the full
  stack with the stop scripts (or `docker compose down` in the repository root).
- To develop the backend in the IDE, start only the infrastructure
  (`cd backend && docker compose up -d postgres redis`); the IDE backend then uses port 8080, so
  stop the full stack first.

## 4. Scripts internals

| File                        | Content                                                                     |
|-----------------------------|-----------------------------------------------------------------------------|
| `scripts/lib/stack.sh`      | shared logic of the macOS and Linux scripts (bash 3.2 compatible)           |
| `scripts/start-linux.sh`    | `STACK_PLATFORM=linux`, all commands                                        |
| `scripts/start-mac.sh`      | `STACK_PLATFORM=mac` (starts Docker Desktop, opens the browser with `open`) |
| `scripts/stop-*.sh`         | stop only                                                                   |
| `scripts/start-windows.bat` | cmd script, all commands                                                    |
| `scripts/stop-windows.bat`  | runs the stop of `start-windows.bat`, pauses when double-clicked            |
| `.gitattributes`            | `*.sh` always LF, `*.bat` always CRLF, on every OS                          |

Port checks: bash uses a `/dev/tcp` connect (no extra tools), cmd uses `netstat -ano`.

## 5. Troubleshooting

| Problem                                    | Fix                                                                                                                                           |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `port 8080 (backend) is in use`            | another program uses the port, often a backend started from the IDE or `npm run dev` (3000). Stop it, or set `BACKEND_PORT` / `FRONTEND_PORT` |
| `Docker is not running`                    | start Docker Desktop, or on Linux `sudo systemctl start docker`                                                                               |
| `No permission to use Docker` (Linux)      | `sudo usermod -aG docker $USER`, log out and in again (or run with `sudo`)                                                                    |
| `Docker Compose ... is too old`            | update Docker Desktop / the Compose plugin to 2.20 or newer                                                                                   |
| `Permission denied` running a `.sh` script | `chmod +x scripts/*.sh`, or run it with `bash scripts/start-linux.sh`                                                                         |
| a container is not healthy                 | the script prints its last log lines; full logs with the `logs` command                                                                       |
| backend fails with `extension "vector" is not available` | the postgres container still runs the plain image: `docker compose up -d --build postgres`, then restart the backend |
| start from scratch (wipes the database)    | stop, then `docker volume rm backend_postgres-data backend_redis-data`; the next start re-seeds HRTA                                          |

## 6. Verification (v1)

Tested on Windows 11 with Docker 29.8 / Compose 5.5: `start-windows.bat` and `stop-windows.bat`
in cmd and PowerShell (including double-click behaviour and another working directory),
`start-linux.sh` / `stop-linux.sh` in bash, cold start, start on a running stack, stop, restart,
status, logs, port conflict, alternative ports, stopped stack, unreachable Docker, data kept across
stop / start. The bash scripts pass ShellCheck and `bash -n` with bash 3.2 (the macOS version).
