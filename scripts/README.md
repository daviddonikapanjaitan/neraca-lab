# Start / stop scripts

Run the whole application (postgres, redis, backend, frontend) in Docker with one command.
Only Docker is required.

| OS      | Start                       | Stop                       |
|---------|-----------------------------|----------------------------|
| Windows | `scripts\start-windows.bat` | `scripts\stop-windows.bat` |
| macOS   | `scripts/start-mac.sh`      | `scripts/stop-mac.sh`      |
| Linux   | `scripts/start-linux.sh`    | `scripts/stop-linux.sh`    |

| Service  | URL / port                                    |
|----------|-----------------------------------------------|
| frontend | <http://localhost:3000>                       |
| backend  | <http://localhost:8080>                       |
| postgres | localhost:5432 (user/password/db `neracalab`) |
| redis    | localhost:6379 (password `neracalab`)         |

- Start builds and starts everything, waits until it is ready and opens the browser; the first run
  takes several minutes, later runs seconds.
- Stop removes the containers and keeps the database data; it does nothing when nothing runs.
- The start scripts also take `restart`, `status`, `logs [service]` and `help`.
- Ports: `FRONTEND_PORT`, `BACKEND_PORT`, `POSTGRES_PORT`, `REDIS_PORT`
  (e.g. `BACKEND_PORT=8081 ./scripts/start-linux.sh`).
- The AI upload needs `OPENAI_API_KEY` in `backend/.env` (copy `backend/.env.example`).

Full reference (all options, containers, compose files, troubleshooting):
[`docs/v1_docs/DOCKER_DOCS.md`](../docs/v1_docs/DOCKER_DOCS.md).
