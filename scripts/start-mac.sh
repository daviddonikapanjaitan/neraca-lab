#!/usr/bin/env bash
# Neraca Lab full stack on macOS: postgres, redis, backend and frontend in Docker.
#
#   ./scripts/start-mac.sh              build, start and wait until ready (opens http://localhost:3000)
#   ./scripts/stop-mac.sh               stop (database data is kept)
#   ./scripts/start-mac.sh help         all commands and options
#
# Needs Docker Desktop for Mac (started automatically when it is installed but not running).
set -euo pipefail

STACK_PLATFORM="mac"
STACK_SCRIPT="$0"
STACK_STOP_SCRIPT="$(dirname "$0")/stop-mac.sh"
# shellcheck source=lib/stack.sh
. "$(cd "$(dirname "$0")" && pwd)/lib/stack.sh"

stack_main "$@"
