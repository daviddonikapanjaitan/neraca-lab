#!/usr/bin/env bash
# Stops Neraca Lab on macOS: removes the postgres, redis, backend and frontend containers
# started by start-mac.sh. The database data is kept (Docker volume backend_postgres-data).
#
#   ./scripts/stop-mac.sh               stop
#   ./scripts/start-mac.sh              start again
#
# Does nothing (and does not start Docker Desktop) when the stack or Docker is not running.
set -euo pipefail

STACK_PLATFORM="mac"
STACK_SCRIPT="$0"
STACK_START_SCRIPT="$(dirname "$0")/start-mac.sh"
# shellcheck source=lib/stack.sh
. "$(cd "$(dirname "$0")" && pwd)/lib/stack.sh"

stack_stop_main "$@"
