#!/usr/bin/env bash
# AgentRelease Lab — Docker end-to-end verification.
#
# From a checkout, builds and starts the complete stack with Docker Compose,
# then verifies: database migrations, Redis, datasets, runbooks, price table,
# backend, worker, dashboard, and API connectivity. It then runs the fixture
# evaluation workflow TWICE against the same database and asserts that the
# two batches stay separate, that both batches' evidence remains retrievable
# afterwards, and that verdicts match the fixture expectations
# (flawed→BLOCKED, fixed→PASS, regressed→FAIL).
#
# Fixture mode is deterministic and labeled; it exercises the harness, not a
# live model. See docs/LIMITATIONS.md.
#
# Usage: ./scripts/docker_e2e.sh [--down]
#   Without --down the stack is left running for inspection (reports in
#   /tmp/arl-docker-e2e). With --down the stack is torn down at the end.
#
# Exit codes: non-zero on any failed check. Every check prints what it
# verified or exactly what failed, so a CI log shows the failing step.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOWN=0
if [ "${1:-}" = "--down" ]; then DOWN=1; fi

PLATFORM_PORT="${PLATFORM_PORT:-8080}"
WORKER_PORT="${WORKER_PORT:-8001}"
POSTGRES_USER="${POSTGRES_USER:-arl}"
POSTGRES_DB="${POSTGRES_DB:-agentreleaselab}"
WORKER_API_KEY="${WORKER_API_KEY:-change-me-worker-key}"
ADMIN_KEY="${ARL_E2E_ADMIN_KEY:-arl-acme-admin-demo}"  # seeded demo admin key
PLATFORM="http://localhost:${PLATFORM_PORT}"
WORKER="http://localhost:${WORKER_PORT}"
REPORT_DIR="/tmp/arl-docker-e2e"

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

wait_for() { # wait_for <name> <url> <timeout_s>
  local name="$1" url="$2" timeout="$3" i=0
  echo "waiting for ${name} (${url})…"
  until curl -sf -o /dev/null "$url"; do
    i=$((i + 1))
    [ "$i" -ge "$timeout" ] && fail "${name} did not become healthy within ${timeout}s: ${url}"
    sleep 1
  done
  pass "${name} healthy"
}

psqlc() { # psqlc <sql> — run against the compose postgres container
  docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"
}

pyjson() { # pyjson — read JSON assertions from stdin via a python snippet arg
  python3 -c "$1"
}

echo "== 0/9 prerequisites =="
docker compose version >/dev/null 2>&1 || fail "docker compose is required"
docker info >/dev/null 2>&1 || fail "docker daemon is not reachable"
command -v python3 >/dev/null || fail "python3 is required"
command -v curl >/dev/null || fail "curl is required"
python3 -c "import httpx" 2>/dev/null || {
  echo "installing httpx (run_eval.py dependency)…"
  python3 -m pip install -q httpx || fail "could not install httpx"
}
rm -rf "$REPORT_DIR"; mkdir -p "$REPORT_DIR/run1" "$REPORT_DIR/run2"
pass "prerequisites ok"

echo "== 1/9 build and start stack =="
cd "$ROOT"
docker compose up --build -d
pass "compose stack started"

echo "== 2/9 service health =="
wait_for "platform /api/health" "${PLATFORM}/api/health" 180
wait_for "worker /health" "${WORKER}/health" 120
wait_for "dashboard" "http://localhost:5173/" 120
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/)" = "200" ] \
  || fail "dashboard did not return HTTP 200"
pass "dashboard returns HTTP 200"
redis_pong="$(docker compose exec -T redis redis-cli ping 2>/dev/null | tr -d '\r')" \
  || fail "could not reach redis in compose stack"
[ "$redis_pong" = "PONG" ] || fail "redis ping returned '${redis_pong}' (expected PONG)"
pass "redis answers PONG"

echo "== 3/9 database migrations =="
mig_count="$(psqlc "SELECT count(*) FROM flyway_schema_history;")"
[ "$mig_count" -ge 6 ] || fail "expected >= 6 applied migrations, got ${mig_count}"
pass "flyway migrations applied: ${mig_count}"
psqlc "SELECT version FROM flyway_schema_history ORDER BY installed_rank;" | sed 's/^/  migration: /'

echo "== 4/9 datasets, runbooks, price table =="
datasets_json="$(curl -sf -H "X-API-Key: ${ADMIN_KEY}" "${PLATFORM}/api/datasets")" \
  || fail "GET /api/datasets failed"
n_datasets="$(printf '%s' "$datasets_json" | pyjson 'import json,sys; print(len(json.load(sys.stdin)))')"
[ "$n_datasets" -ge 1 ] || fail "expected >= 1 dataset, got ${n_datasets}"
pass "datasets loaded via platform API: ${n_datasets}"
runbooks_json="$(curl -sf -H "X-API-Key: ${ADMIN_KEY}" "${PLATFORM}/api/runbooks")" \
  || fail "GET /api/runbooks failed"
n_runbooks="$(printf '%s' "$runbooks_json" | pyjson 'import json,sys; print(len(json.load(sys.stdin)))')"
[ "$n_runbooks" -ge 1 ] || fail "expected >= 1 runbook, got ${n_runbooks}"
pass "runbooks loaded via platform API: ${n_runbooks}"
docker compose exec -T worker python3 -c \
  "import json; d=json.load(open('/srv/worker/eval/price-table.json')); assert d.get('as_of') and d.get('models'), 'price table missing as_of/models'; print('price table as_of:', d['as_of'], '| models:', len(d['models']))" \
  || fail "price table not readable in worker container"
pass "price table present in worker container"

echo "== 5/9 fixture evaluation run 1 =="
python3 benchmarks/run_eval.py --mode fixture \
  --platform "$PLATFORM" --worker "$WORKER" --worker-key "$WORKER_API_KEY" \
  --out "$REPORT_DIR/run1" || fail "run_eval.py run 1 exited non-zero"
R1="$(ls -t "$REPORT_DIR"/run1/eval-report-*.json | head -1)"
[ -n "$R1" ] || fail "run 1 produced no report"
pass "run 1 report: $(basename "$R1")"

echo "== 6/9 fixture evaluation run 2 (same database) =="
python3 benchmarks/run_eval.py --mode fixture \
  --platform "$PLATFORM" --worker "$WORKER" --worker-key "$WORKER_API_KEY" \
  --out "$REPORT_DIR/run2" || fail "run_eval.py run 2 exited non-zero"
R2="$(ls -t "$REPORT_DIR"/run2/eval-report-*.json | head -1)"
[ -n "$R2" ] || fail "run 2 produced no report"
pass "run 2 report: $(basename "$R2")"

echo "== 7/9 report assertions (both runs) =="
check_report() { # check_report <report>
  pyjson '
import json, sys
r = json.load(open(sys.argv[1]))
assert r["mode"] == "fixture", f"mode is {r[\"mode\"]}, expected fixture"
a = r["assertions"]
assert len(a) == 6, f"expected 6 assertions, got {len(a)}"
assert all(x["passed"] for x in a), "not all assertions passed"
verdicts = {d["candidate"]: d["verdict"] for d in r["decisions"]}
expected = {"candidate-flawed": "BLOCKED", "candidate-fixed": "PASS",
            "candidate-regressed": "FAIL"}
assert verdicts == expected, f"verdicts {verdicts} != expected {expected}"
# decision evidence bundles are present in the report
found = any(d.get("evidence") for d in r["decisions"])
assert found, "no decision evidence in report"
print("batch_id:", r["batch_id"])
' "$1"
}
B1="$(check_report "$R1")"; B1="${B1#batch_id: }"
B2="$(check_report "$R2")"; B2="${B2#batch_id: }"
pass "run 1: 6/6 assertions, verdicts flawed→BLOCKED fixed→PASS regressed→FAIL"
pass "run 2: 6/6 assertions, verdicts flawed→BLOCKED fixed→PASS regressed→FAIL"
[ "$B1" != "$B2" ] || fail "batch ids are identical (${B1}); runs must produce distinct batches"
pass "distinct batch ids: ${B1} / ${B2}"

echo "== 8/9 batch separation and historical evidence retrieval =="
for b in "$B1" "$B2"; do
  n_runs="$(psqlc "SELECT count(*) FROM eval_runs WHERE batch_id='${b}';")"
  [ "$n_runs" -gt 0 ] || fail "no eval_runs stored for batch ${b}"
  n_traces="$(psqlc "SELECT count(*) FROM trace_events t JOIN eval_runs e ON t.eval_run_id=e.id WHERE e.batch_id='${b}';")"
  [ "$n_traces" -gt 0 ] || fail "no trace_events stored for batch ${b}"
  n_dec="$(psqlc "SELECT count(*) FROM release_decisions WHERE batch_id='${b}';")"
  [ "$n_dec" -eq 3 ] || fail "expected 3 release_decisions for batch ${b}, got ${n_dec}"
  pass "batch ${b}: ${n_runs} eval runs, ${n_traces} trace events, ${n_dec} decisions in DB"
done
# Both batches' decisions remain retrievable through the platform API
# (after both runs completed — this is the historical-evidence check).
decisions_json="$(curl -sf -H "X-API-Key: ${ADMIN_KEY}" "${PLATFORM}/api/release-decisions")" \
  || fail "GET /api/release-decisions failed"
for b in "$B1" "$B2"; do
  n_api="$(printf '%s' "$decisions_json" | B="$b" pyjson '
import json, os, sys
ds = json.load(sys.stdin)
hits = [d for d in ds if d.get("batchId") == os.environ["B"]]
assert len(hits) == 3, f"batch {os.environ[\"B\"]}: expected 3 decisions via API, got {len(hits)}"
print(len(hits))')"
  pass "batch ${b}: ${n_api} decisions retrievable via platform API"
  dec_id="$(printf '%s' "$decisions_json" | B="$b" pyjson '
import json, os, sys
print([d for d in json.load(sys.stdin) if d.get("batchId") == os.environ["B"]][0]["id"])')"
  dec_detail="$(curl -sf -H "X-API-Key: ${ADMIN_KEY}" "${PLATFORM}/api/release-decisions/${dec_id}")" \
    || fail "GET /api/release-decisions/${dec_id} failed"
  printf '%s' "$dec_detail" | pyjson 'import json,sys; d=json.load(sys.stdin); assert d.get("evidence"), "empty evidence bundle"; assert d.get("verdict") in ("PASS","FAIL","BLOCKED","INSUFFICIENT_EVIDENCE")'
  pass "batch ${b}: decision ${dec_id} detail with evidence bundle retrievable via API"
  run_id="$(psqlc "SELECT id FROM eval_runs WHERE batch_id='${b}' LIMIT 1;")"
  run_detail="$(curl -sf -H "X-API-Key: ${ADMIN_KEY}" "${PLATFORM}/api/eval-runs/${run_id}")" \
    || fail "GET /api/eval-runs/${run_id} failed"
  got_batch="$(printf '%s' "$run_detail" | pyjson 'import json,sys; print(json.load(sys.stdin)["batchId"])')"
  [ "$got_batch" = "$b" ] || fail "eval run ${run_id} returned batchId ${got_batch}, expected ${b}"
  pass "batch ${b}: eval run ${run_id} retrievable via platform API with matching batchId"
done

echo "== 9/9 summary =="
echo "stack:      $(docker compose ps --format '{{.Name}}' | tr '\n' ' ')"
echo "run 1:      $(basename "$R1")  batch ${B1}"
echo "run 2:      $(basename "$R2")  batch ${B2}"
echo "reports:    ${REPORT_DIR}/run1  ${REPORT_DIR}/run2"
if [ "$DOWN" = "1" ]; then
  echo "tearing down (--down given)…"
  docker compose down
  pass "stack torn down"
else
  echo "stack left running (pass --down to tear down)"
fi
echo "ALL DOCKER E2E CHECKS PASSED"
