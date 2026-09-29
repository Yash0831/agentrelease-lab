#!/usr/bin/env bash
# AgentRelease Lab — one-command demo (native, no Docker required).
#
# Builds the platform, starts platform + worker against local PostgreSQL and
# Redis, runs the baseline-vs-candidate evaluation matrix in fixture mode,
# renders release decisions, and writes a machine-readable report to
# benchmarks/reports/.
#
# Prerequisites: Java 21, Maven, Python 3.12, PostgreSQL 16 + pgvector, Redis.
# (Or: docker compose up --build — see README.md.)
#
# Environment overrides:
#   ARL_OFFLINE=1        pass -o to Maven (sandbox with no Maven Central)
#   ARL_RESET_EVAL=1     clear previous evaluation runs before the matrix
#                        (the matrix is designed for one run per database;
#                        re-running without reset hits the eval-run uniqueness
#                        constraint)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# --- toolchain detection (do not assume fixed paths) ---
if [ -z "${JAVA_HOME:-}" ]; then
  for cand in /opt/jdk21 /usr/lib/jvm/java-21-openjdk-amd64; do
    [ -d "$cand" ] && { JAVA_HOME="$cand"; break; }
  done
fi
export JAVA_HOME="${JAVA_HOME:-}"
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -d /opt/maven/bin ] && export PATH="/opt/maven/bin:$PATH"

MVN_OPTS=""
[ "${ARL_OFFLINE:-}" = "1" ] && MVN_OPTS=" -o"
PYBIN="$ROOT/agent-worker/.venv/bin/python"
[ -x "$PYBIN" ] || PYBIN="python3"

echo "== 1/6 prerequisites =="
command -v java >/dev/null || { echo "Java 21 required"; exit 1; }
java -version 2>&1 | grep -q 'version "21' || { echo "Java 21 required"; exit 1; }
command -v mvn >/dev/null || { echo "Maven required"; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 required"; exit 1; }
command -v psql >/dev/null || { echo "psql required"; exit 1; }
redis-cli ping >/dev/null 2>&1 || { echo "starting redis…"; redis-server --daemonize yes >/dev/null; }
export PGPASSWORD="${DB_PASSWORD:-arl-test}"
psql -U arl -h localhost -tAc "SELECT 1" agentreleaselab >/dev/null 2>&1 || {
  echo "creating database…"
  su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='arl'\"" | grep -q 1 \
    || su postgres -c "psql -c \"CREATE ROLE arl LOGIN PASSWORD 'arl-test'\""
  su postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='agentreleaselab'\"" | grep -q 1 \
    || su postgres -c "createdb -O arl agentreleaselab"
  su postgres -c "psql -c 'CREATE EXTENSION IF NOT EXISTS vector;' agentreleaselab" >/dev/null
}

echo "== 2/6 build platform =="
cd "$ROOT/platform"
JAR="target/agentrelease-lab-platform-0.1.0.jar"
if [ ! -f "$JAR" ] || [ -n "$(find src -newer "$JAR" -name '*.java' | head -1)" ]; then
  # shellcheck disable=SC2086
  mvn -B -q -DskipTests $MVN_OPTS package
fi

echo "== 3/6 start platform =="
if ! curl -sf http://localhost:8080/api/health >/dev/null 2>&1; then
  DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:5432/agentreleaselab?sslmode=disable}" \
  DB_USER="${DB_USER:-arl}" DB_PASSWORD="${DB_PASSWORD:-arl-test}" \
  SEED_ADMIN_API_KEY="${SEED_ADMIN_API_KEY:-demo-admin-key}" \
  EVAL_DATASETS_DIR="${EVAL_DATASETS_DIR:-$ROOT/eval/datasets}" \
    setsid nohup java -jar "$JAR" > /tmp/arl-platform.log 2>&1 < /dev/null &
  echo $! > /tmp/arl-platform.pid
  for _ in $(seq 1 60); do curl -sf http://localhost:8080/api/health >/dev/null 2>&1 && break; sleep 2; done
fi
curl -sf http://localhost:8080/api/health
echo

echo "== 4/6 start worker =="
cd "$ROOT/agent-worker"
"$PYBIN" -c "import fastapi, uvicorn" 2>/dev/null || {
  echo "installing worker dependencies…"; "$PYBIN" -m pip install -q -r requirements.txt; }
if ! curl -sf http://localhost:8001/health >/dev/null 2>&1; then
  PLATFORM_BASE_URL=http://localhost:8080 REDIS_URL=redis://localhost:6379/0 \
    setsid nohup "$PYBIN" -m uvicorn app.main:app --host 127.0.0.1 --port 8001 \
      > /tmp/arl-worker.log 2>&1 < /dev/null &
  echo $! > /tmp/arl-worker.pid
  for _ in $(seq 1 30); do curl -sf http://localhost:8001/health >/dev/null 2>&1 && break; sleep 2; done
fi
curl -sf http://localhost:8001/health
echo

if [ "${ARL_RESET_EVAL:-}" = "1" ]; then
  echo "== resetting previous evaluation runs =="
  psql -U arl -h localhost -d agentreleaselab \
    -c "TRUNCATE trace_events, tool_calls, eval_runs, release_decisions CASCADE;"
fi

echo "== 5/6 run evaluation matrix (fixture mode) =="
cd "$ROOT"
"$PYBIN" benchmarks/run_eval.py --mode fixture --platform http://localhost:8080 --worker http://localhost:8001

echo "== 6/6 verdicts =="
LATEST="$(ls -t benchmarks/reports/eval-report-*.json | head -1)"
"$PYBIN" - "$LATEST" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
print(f"report: {sys.argv[1]}  mode={r['mode']} generated_at={r['generated_at']}")
for d in r["decisions"]:
    print(f"  {d['candidate']}: {d['verdict']}")
print(f"assertions: {sum(a['passed'] for a in r['assertions'])}/{len(r['assertions'])} passed")
EOF
echo
echo "Dashboard: cd dashboard && npm install && npm run dev  (then open http://localhost:5173)"
echo "Logs: /tmp/arl-platform.log /tmp/arl-worker.log"
