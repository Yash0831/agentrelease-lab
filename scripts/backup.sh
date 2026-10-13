#!/usr/bin/env bash
# AgentRelease Lab — backup.
#
# Captures everything needed to preserve evaluation evidence:
#   1. PostgreSQL custom-format dump of the platform database (tenants,
#      agent versions, eval runs/batches, traces, approvals, decisions).
#   2. Evaluation evidence files: benchmarks/reports/*.json.
#   3. Recordings and config, when present (eval/recordings/, .env.example
#      as config reference — never real secrets).
#
# Sources: the Compose postgres container by default, or DATABASE_URL when
# ARL_BACKUP_VIA_URL=1. Writes to a timestamped directory with SHA-256
# checksums and a manifest. The database password is passed via PGPASSWORD
# and never echoed.
#
# Usage: ./scripts/backup.sh [--out DIR]   (default DIR: ./backups)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_BASE="${1:-}"
if [ "${1:-}" = "--out" ]; then OUT_BASE="$2"; fi
OUT_BASE="${OUT_BASE:-$ROOT/backups}"

POSTGRES_USER="${POSTGRES_USER:-arl}"
POSTGRES_DB="${POSTGRES_DB:-agentreleaselab}"
export PGPASSWORD="${POSTGRES_PASSWORD:-change-me-in-dev-only}"

TS="$(date -u +%Y%m%d-%H%M%S)"
OUT="$OUT_BASE/arl-backup-$TS"
mkdir -p "$OUT"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

echo "backup destination: $OUT"

echo "== 1/4 database dump =="
if [ "${ARL_BACKUP_VIA_URL:-0}" = "1" ]; then
  [ -n "${DATABASE_URL:-}" ] || fail "ARL_BACKUP_VIA_URL=1 requires DATABASE_URL"
  # DATABASE_URL is jdbc:...; derive a postgres:// URL for pg_dump.
  PGURL="$(printf '%s' "$DATABASE_URL" | sed -e 's/^jdbc:postgresql:\/\///' -e 's/?sslmode=disable$//')"
  pg_dump "postgresql://${POSTGRES_USER}:${PGPASSWORD}@${PGURL}" -Fc -f "$OUT/db.dump" \
    || fail "pg_dump via DATABASE_URL failed"
else
  cd "$ROOT"
  docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
    > "$OUT/db.dump" || fail "pg_dump from compose postgres failed"
fi
[ -s "$OUT/db.dump" ] || fail "db.dump is empty"
pass "database dumped: $(du -h "$OUT/db.dump" | cut -f1)"

echo "== 2/4 evaluation reports =="
mkdir -p "$OUT/reports"
if ls "$ROOT"/benchmarks/reports/eval-report-*.json >/dev/null 2>&1; then
  cp "$ROOT"/benchmarks/reports/eval-report-*.json "$OUT/reports/"
  pass "copied $(ls "$OUT/reports" | wc -l) report(s)"
else
  echo "note: no benchmarks/reports/eval-report-*.json found; skipping"
fi

echo "== 3/4 recordings and config =="
if [ -d "$ROOT/eval/recordings" ] && [ -n "$(ls -A "$ROOT/eval/recordings" 2>/dev/null)" ]; then
  cp -r "$ROOT/eval/recordings" "$OUT/recordings"
  pass "recordings copied"
else
  echo "note: no eval/recordings present; skipping"
fi
cp "$ROOT/.env.example" "$OUT/env.example" 2>/dev/null && pass "config reference (.env.example) copied"

echo "== 4/4 manifest and checksums =="
{
  echo "generated_at_utc=$TS"
  echo "source_db=$POSTGRES_DB"
  echo "source_user=$POSTGRES_USER"
} > "$OUT/MANIFEST.txt"
cd "$OUT" && sha256sum db.dump $(ls reports/*.json 2>/dev/null) $(find recordings -type f 2>/dev/null) env.example 2>/dev/null > SHA256SUMS
sha256sum -c SHA256SUMS --quiet || fail "checksum verification failed"
pass "checksums written and verified"

echo
echo "backup complete: $OUT"
echo "restore with: ./scripts/restore.sh $OUT <target-database-name>"
