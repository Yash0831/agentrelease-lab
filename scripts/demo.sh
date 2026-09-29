#!/usr/bin/env bash
# AgentRelease Lab — one-command demo (native, no Docker required).
#
# Builds the platform, starts platform + worker against local PostgreSQL and
# Redis, registers agent versions, runs the baseline-vs-candidate evaluation
# matrix in fixture mode, renders release decisions, and writes a
# machine-readable report to benchmarks/reports/.
#
# Prerequisites: Java 21, Maven, Python 3.12, PostgreSQL 16 + pgvector, Redis.
# (Or: docker compose up --build — see README.md.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/jdk21}"
export PATH="$JAVA_HOME/bin:/opt/maven/bin:$PATH"

echo "== 1/6 prerequisites =="
command -v java >/dev/null || { echo "Java 21 required"; exit 1; }
command -v mvn >/dev/null || { echo "Maven required"; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 required"; exit 1; }
command -v psql >/dev/null || { echo "psql required"; exit 1; }
redis-cli ping >/dev/null 2>&1 || { echo "starting redis…"; redis-server --daemonize yes >/dev/null; }
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
[ -f target/agentrelease-lab-platform-0.1.0.jar ] || mvn -B -q -DskipTests package
JAR="$(ls target/agentrelease-lab-platform-*.jar | head -1)"

echo "== 3/6 start platform =="
if ! curl -sf http://localhost:8080/api/health >/dev/null 2>&1; then
  DATABASE_URL=jdbc:postgresql://localhost:5432/agentreleaselab DB_USER=arl DB_PASSWORD=arl-test \
    java -jar "$JAR" > /tmp/arl-platform.log 2>&1 &
  echo $! > /tmp/arl-platform.pid
  for _ in $(seq 1 60); do curl -sf http://localhost:8080/api/health >/dev/null 2>&1 && break; sleep 2; done
fi
curl -sf http://localhost:8080/api/health

echo "== 4/6 start worker =="
cd "$ROOT/agent-worker"
python3 -c "import fastapi" 2>/dev/null || pip install -q -r requirements.txt
if ! curl -sf http://localhost:8001/health >/dev/null 2>&1; then
  PLATFORM_BASE_URL=http://localhost:8080 REDIS_URL=redis://localhost:6379/0 \
    python3 -m uvicorn app.main:app --host 127.0.0.1 --port 8001 > /tmp/arl-worker.log 2>&1 &
  echo $! > /tmp/arl-worker.pid
  for _ in $(seq 1 30); do curl -sf http://localhost:8001/health >/dev/null 2>&1 && break; sleep 2; done
fi

echo "== 5/6 run evaluation matrix (fixture mode) =="
cd "$ROOT"
python3 benchmarks/run_eval.py --mode fixture --platform http://localhost:8080 --worker http://localhost:8001

echo "== 6/6 verdicts =="
LATEST="$(ls -t benchmarks/reports/eval-report-*.json | head -1)"
python3 - "$LATEST" <<'EOF'
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
