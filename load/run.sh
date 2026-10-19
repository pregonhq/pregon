#!/usr/bin/env bash
# Runs one load scenario against the local Compose `lite` stack (deploy/docker-compose.yml).
#
#   load/run.sh steady SOURCES=100 DURATION=5m
#   load/run.sh storm SOURCES=50 BUFFER=300
#
# Creates an admin key and an ingest key inside the server container for this run (ADR-0018),
# passes them to k6 through the environment and revokes them when k6 exits. PREGON_URL may be
# set to point at another server; it defaults to the port Compose publishes on this machine.
# The summary lands in load/results/ (gitignored) so a before and an after can be compared.
set -euo pipefail
cd "$(dirname "$0")/.."

scenario="${1:?usage: load/run.sh steady|storm [VAR=value ...]}"
shift

compose() { docker compose -f deploy/docker-compose.yml --profile lite "$@"; }

if ! compose ps --status running --format '{{.Service}}' | grep -qx server; then
  echo "the server is not running; start it with:" >&2
  echo "  docker compose -f deploy/docker-compose.yml --profile lite up -d --wait" >&2
  exit 1
fi

run_id="$(date -u +%Y%m%dT%H%M%SZ)"
key_ids=()

# Sets KEY to the new key and remembers its id so the key is revoked at exit. Not a subshell:
# `$(create_key ...)` would lose the id. Keys never reach the logs.
create_key() {
  local output id
  output="$(compose exec -T server pregon keys create --scope "$1" --name "load-$run_id-$1" 2>&1)"
  KEY="$(printf '%s\n' "$output" | grep -E '^pgn_' | head -1)"
  id="$(printf '%s\n' "$output" | sed -n 's/^Created key \([^ ]*\) .*/\1/p' | head -1)"
  if [ -z "$KEY" ] || [ -z "$id" ]; then
    echo "could not create the $1 key:" >&2
    printf '%s\n' "$output" | grep -v '^pgn_' >&2
    exit 1
  fi
  key_ids+=("$id")
}

revoke_keys() {
  for id in "${key_ids[@]}"; do
    compose exec -T server pregon keys revoke "$id" > /dev/null 2>&1 || echo "could not revoke key $id" >&2
  done
}
trap revoke_keys EXIT

create_key admin
admin_key="$KEY"
create_key ingest
ingest_key="$KEY"
unset KEY

mkdir -p load/results
env PREGON_URL="${PREGON_URL:-http://localhost:8080}" \
  PREGON_ADMIN_KEY="$admin_key" \
  PREGON_INGEST_KEY="$ingest_key" \
  SCENARIO="$scenario" \
  "$@" \
  k6 run --summary-export "load/results/$scenario-$run_id.json" load/ingest.js
