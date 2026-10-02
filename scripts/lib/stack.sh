#!/usr/bin/env bash
# Shared logic of scripts/start-linux.sh and scripts/start-mac.sh (sourced, not run directly).
# Runs the full stack (postgres, redis, backend, frontend) with docker-compose.yaml in the
# repository root. Compatible with bash 3.2 (macOS default).
#
# The caller sets STACK_PLATFORM=linux|mac and then calls: stack_main "$@"
# Optional: STACK_START_SCRIPT / STACK_STOP_SCRIPT, the script paths shown in hints.

set -euo pipefail

STACK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK_SCRIPT="${STACK_SCRIPT:-$0}"

export FRONTEND_PORT="${FRONTEND_PORT:-3000}"
export BACKEND_PORT="${BACKEND_PORT:-8080}"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export REDIS_PORT="${REDIS_PORT:-6379}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-600}"     # seconds to wait for healthy containers after start

MIN_COMPOSE_MAJOR=2
MIN_COMPOSE_MINOR=20                     # "include" in docker-compose.yaml

# ------------------------------------------------------------------ output

if [ -t 1 ]; then
  C_BLUE=$'\033[1;34m'; C_GREEN=$'\033[1;32m'; C_YELLOW=$'\033[1;33m'; C_RED=$'\033[1;31m'; C_RESET=$'\033[0m'
else
  C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_RESET=""
fi

info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s ok%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
fail() { printf '%serror%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

usage() {
  cat <<EOF
Neraca Lab full stack in Docker (postgres, redis, backend, frontend).

Usage: $STACK_SCRIPT [command]

Commands:
  start      build and start everything, wait until it is ready (default)
  stop       stop and remove the containers (database data is kept)
  restart    stop, then start
  status     show the containers and their health
  logs [svc] follow the logs (svc: frontend, backend, postgres, redis)
  help       show this help

Environment (optional):
  FRONTEND_PORT=$FRONTEND_PORT  BACKEND_PORT=$BACKEND_PORT  POSTGRES_PORT=$POSTGRES_PORT  REDIS_PORT=$REDIS_PORT
  NO_BROWSER=1   do not open the browser after start
  WAIT_TIMEOUT=$WAIT_TIMEOUT seconds to wait for the containers to become healthy

AI upload needs backend/.env with OPENAI_API_KEY (copy backend/.env.example).
EOF
}

compose() {
  docker compose --project-directory "$STACK_ROOT" -f "$STACK_ROOT/docker-compose.yaml" "$@"
}

# ------------------------------------------------------------------ checks

docker_install_hint() {
  if [ "$STACK_PLATFORM" = "mac" ]; then
    echo "Install Docker Desktop for Mac: https://docs.docker.com/desktop/setup/install/mac-install/"
  else
    echo "Install Docker Engine + Compose plugin: https://docs.docker.com/engine/install/ (or Docker Desktop)"
  fi
}

ensure_docker_running() {
  local out
  if out="$(docker info 2>&1)"; then
    return 0
  fi
  case "$out" in
    *"permission denied"*)
      fail "No permission to use Docker. Add your user to the docker group:
       sudo usermod -aG docker \$USER   (then log out and in again)
       or run this script with sudo." ;;
  esac
  if [ "$STACK_PLATFORM" = "mac" ] && [ -d "/Applications/Docker.app" ]; then
    info "Docker is not running; starting Docker Desktop..."
    open -a Docker || true
  elif [ "$STACK_PLATFORM" = "linux" ]; then
    fail "Docker is not running. Start it, e.g.:  sudo systemctl start docker   (or start Docker Desktop)"
  else
    fail "Docker is not running. Start Docker Desktop and run this script again."
  fi
  local waited=0
  until docker info >/dev/null 2>&1; do
    if [ "$waited" -ge 180 ]; then
      fail "Docker did not start within 180 s. Start Docker Desktop manually and run this script again."
    fi
    sleep 3; waited=$((waited + 3))
  done
  ok "Docker is running"
}

check_docker() {
  command -v docker >/dev/null 2>&1 || fail "Docker is not installed. $(docker_install_hint)"
  ensure_docker_running

  local version major minor
  version="$(docker compose version --short 2>/dev/null || true)"
  [ -n "$version" ] || fail "Docker Compose v2 is missing ('docker compose'). $(docker_install_hint)"
  version="${version#v}"
  major="${version%%.*}"
  minor="${version#*.}"; minor="${minor%%.*}"
  case "$major$minor" in
    *[!0-9]*|"") warn "Cannot read the Docker Compose version '$version'; continuing" ;;
    *)
      if [ "$major" -lt "$MIN_COMPOSE_MAJOR" ] || { [ "$major" -eq "$MIN_COMPOSE_MAJOR" ] && [ "$minor" -lt "$MIN_COMPOSE_MINOR" ]; }; then
        fail "Docker Compose $version is too old; $MIN_COMPOSE_MAJOR.$MIN_COMPOSE_MINOR or newer is required. $(docker_install_hint)"
      fi ;;
  esac
  ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?'), Compose $version"
}

check_env_file() {
  if [ -f "$STACK_ROOT/backend/.env" ]; then
    ok "backend/.env found (AI settings)"
  else
    warn "backend/.env not found: the app runs, but the AI upload needs OPENAI_API_KEY."
    warn "  cp backend/.env.example backend/.env   then set OPENAI_API_KEY and run '$STACK_SCRIPT restart'"
  fi
}

container_running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]
}

port_in_use() {
  # bash built-in TCP connect: works on Linux and macOS without extra tools
  (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
}

check_ports() {
  local conflicts="" entry name container port var
  for entry in "frontend:neracalab-frontend:$FRONTEND_PORT:FRONTEND_PORT" \
               "backend:neracalab-backend:$BACKEND_PORT:BACKEND_PORT" \
               "postgres:neracalab-postgres:$POSTGRES_PORT:POSTGRES_PORT" \
               "redis:neracalab-redis:$REDIS_PORT:REDIS_PORT"; do
    name="${entry%%:*}"; entry="${entry#*:}"
    container="${entry%%:*}"; entry="${entry#*:}"
    port="${entry%%:*}"; var="${entry#*:}"
    # a port held by our own running container is fine (compose reuses it)
    if ! container_running "$container" && port_in_use "$port"; then
      conflicts="$conflicts
       port $port ($name) - choose another with $var=<port>"
    fi
  done
  if [ -n "$conflicts" ]; then
    fail "These ports are already used by another program:$conflicts
       Stop that program (e.g. a backend started from the IDE, or 'npm run dev'), or start with another port,
       e.g.  BACKEND_PORT=8081 FRONTEND_PORT=3001 $STACK_SCRIPT"
  fi
  ok "Ports free: frontend $FRONTEND_PORT, backend $BACKEND_PORT, postgres $POSTGRES_PORT, redis $REDIS_PORT"
}

# ------------------------------------------------------------------ start / wait

wait_healthy() {
  local container="$1" label="$2" waited=0 state
  while :; do
    state="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container" 2>/dev/null || echo "missing")"
    case "$state" in
      "running healthy") ok "$label is ready"; return 0 ;;
      "running unhealthy"|exited*|dead*|missing)
        warn "$label is not healthy ($state). Last log lines:"
        docker logs --tail 60 "$container" 2>&1 || true
        fail "$label failed to start. Full logs: $STACK_SCRIPT logs ${container#neracalab-}" ;;
    esac
    if [ "$waited" -ge "$WAIT_TIMEOUT" ]; then
      fail "$label not ready after $WAIT_TIMEOUT s ($state). Logs: $STACK_SCRIPT logs ${container#neracalab-}"
    fi
    sleep 3; waited=$((waited + 3))
  done
}

open_browser() {
  [ -n "${NO_BROWSER:-}" ] && return 0
  if [ "$STACK_PLATFORM" = "mac" ]; then
    open "$1" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1 && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    xdg-open "$1" >/dev/null 2>&1 || true
  fi
}

cmd_start() {
  info "Neraca Lab full stack ($STACK_PLATFORM)"
  check_docker
  check_env_file
  check_ports
  info "Building and starting containers (the first run downloads images and builds: several minutes)..."
  if ! compose up -d --build; then
    warn "docker compose up failed. Container status:"
    compose ps -a || true
    fail "Start failed. See the messages above, or: $STACK_SCRIPT logs"
  fi
  info "Waiting for the services to become ready..."
  wait_healthy neracalab-postgres "Postgres"
  wait_healthy neracalab-redis "Redis"
  wait_healthy neracalab-backend "Backend"
  wait_healthy neracalab-frontend "Frontend"

  local url="http://localhost:$FRONTEND_PORT"
  printf '\n%sNeraca Lab is running%s\n' "$C_GREEN" "$C_RESET"
  printf '  Frontend   %s\n' "$url"
  printf '  Backend    http://localhost:%s/api/v1/exchanges\n' "$BACKEND_PORT"
  printf '  Postgres   localhost:%s  (db/user/password: neracalab)\n' "$POSTGRES_PORT"
  printf '\n  Logs: %s logs    Stop: %s\n\n' "$STACK_SCRIPT" "${STACK_STOP_SCRIPT:-$STACK_SCRIPT stop}"
  open_browser "$url"
}

cmd_stop() {
  # never starts Docker: when Docker is not running, nothing of the stack is running either
  command -v docker >/dev/null 2>&1 || fail "Docker is not installed. $(docker_install_hint)"
  local out
  if ! out="$(docker info 2>&1)"; then
    case "$out" in
      *"permission denied"*)
        fail "No permission to use Docker. Add your user to the docker group:
       sudo usermod -aG docker \$USER   (then log out and in again)
       or run this script with sudo." ;;
    esac
    ok "Docker is not running, so Neraca Lab is not running either: nothing to stop"
    return 0
  fi
  if [ -z "$(compose ps -a -q 2>/dev/null)" ]; then
    ok "Neraca Lab is not running: nothing to stop"
    return 0
  fi
  info "Stopping Neraca Lab (database data is kept)..."
  compose down
  ok "Stopped. Start again with: ${STACK_START_SCRIPT:-$STACK_SCRIPT}"
}

cmd_status() {
  check_docker
  compose ps -a
}

cmd_logs() {
  check_docker
  if [ $# -gt 0 ]; then
    compose logs -f --tail=200 "$@"
  else
    compose logs -f --tail=200
  fi
}

# Entry point of the stop-*.sh scripts: only stops (or shows help).
stack_stop_main() {
  case "${1:-}" in
    "")
      cmd_stop ;;
    help|-h|--help)
      cat <<EOF
Stops Neraca Lab: removes the postgres, redis, backend and frontend containers.
The database data is kept. Does nothing when the stack or Docker is not running.

Usage: $STACK_SCRIPT
Start again: ${STACK_START_SCRIPT:-the start script}
EOF
      ;;
    *)
      fail "Unknown argument: $1 (usage: $STACK_SCRIPT, or $STACK_SCRIPT help)" ;;
  esac
}

stack_main() {
  local command="${1:-start}"
  [ $# -gt 0 ] && shift
  case "$command" in
    start)            cmd_start ;;
    stop|down)        cmd_stop ;;
    restart)          cmd_stop; cmd_start ;;
    status|ps)        cmd_status ;;
    logs)             cmd_logs "$@" ;;
    help|-h|--help)   usage ;;
    *)                usage; echo; fail "Unknown command: $command" ;;
  esac
}
