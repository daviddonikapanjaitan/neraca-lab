#!/usr/bin/env bash
# Stops Neraca Lab on Linux: removes the postgres, redis, backend and frontend containers
# started by start-linux.sh. The database data is kept (Docker volume backend_postgres-data).
#
#   ./scripts/stop-linux.sh             stop
#   ./scripts/start-linux.sh            start again
#
# Does nothing (and does not start Docker) when the stack or Docker is not running.
set -euo pipefail

STACK_PLATFORM="linux"
STACK_SCRIPT="$0"
STACK_START_SCRIPT="$(dirname "$0")/start-linux.sh"
# shellcheck source=lib/stack.sh
. "$(cd "$(dirname "$0")" && pwd)/lib/stack.sh"

stack_stop_main "$@"
