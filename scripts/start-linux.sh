#!/usr/bin/env bash
# Neraca Lab full stack on Linux: postgres, redis, backend and frontend in Docker.
#
#   ./scripts/start-linux.sh            build, start and wait until ready (http://localhost:3000)
#   ./scripts/stop-linux.sh             stop (database data is kept)
#   ./scripts/start-linux.sh help       all commands and options
#
# Needs Docker Engine with the Compose plugin (2.20+) or Docker Desktop.
set -euo pipefail

STACK_PLATFORM="linux"
STACK_SCRIPT="$0"
STACK_STOP_SCRIPT="$(dirname "$0")/stop-linux.sh"
# shellcheck source=lib/stack.sh
. "$(cd "$(dirname "$0")" && pwd)/lib/stack.sh"

stack_main "$@"
