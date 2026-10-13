#!/usr/bin/env bash
# AgentRelease Lab — restore.
#
# Restores a backup produced by scripts/backup.sh into a NAMED TARGET
# database. The target must differ from the backup's source database unless
# --force is given (prevents accidentally overwriting the live database).
# After restore, verification counts are printed for comparison against the
# backup: tenants, agent versions, eval batches, eval runs, trace events,
# approvals, release decisions.
#
# The target database is created if missing (needs a postgres superuser or
# the CREATEDB privilege for POSTGRES_USER). pgvector's `vector` extension
# is enabled on the target.
#
# Usage: ./scripts/restore.sh <backup-dir> <target-db> [--force]
set -euo pipefail

BACKUP_DIR="${1:-}"; TARGET_DB="${2:-}"; FORCE=0
[ "${3:-}" = "--force" ] && FORCE=1
[ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ] || { echo "usage: $0 <backup-dir> <target-db> [--force]" >&2; exit 1; }
[ -n "$TARGET_DB" ] || { echo "usage: $0 <backup-dir> <target-db> [--force]" >&2; exit 1; }
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

POSTGRES_USER="${POSTGRES_USER:-arl}"
export PGPASSWORD="${POSTGRES_PASSWORD:-change-me-in-dev-only}"
SOURCE_DB="$(grep '^source_db=' "$BACKUP_DIR/MANIFEST.txt" | cut -d= -f2)"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

if [ "$TARGET_DB" = "$SOURCE_DB" ] && [ "$FORCE" = "0" ]; then
  fail "target database '${TARGET_DB}' equals the backup source; refusing without --force"
fi

# Connection: Compose postgres container by default; with
# ARL_RESTORE_VIA_URL=1, local psql/pg_restore against PG_HOST/PG_PORT
# (for non-Docker environments, mirroring backup.sh's ARL_BACKUP_VIA_URL).
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
if [ "${ARL_RESTORE_VIA_URL:-0}" = "1" ]; then
  psqlc() { psql "postgresql://${POSTGRES_USER}@${PG_HOST}:${PG_PORT}/$1" -tA -c "$2"; }
else
  psqlc() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$1" -tA -c "$2"; }
fi

echo "== 1/4 verify backup integrity =="
cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS --quiet || fail "backup checksum verification failed"
pass "backup checksums verified"
[ -s "$BACKUP_DIR/db.dump" ] || fail "db.dump missing or empty"

echo "== 2/4 prepare target database '${TARGET_DB}' =="
if ! psqlc postgres "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" | grep -q 1; then
  psqlc postgres "CREATE DATABASE \"${TARGET_DB}\" OWNER \"${POSTGRES_USER}\"" \
    || fail "could not create target database"
  pass "created database ${TARGET_DB}"
else
  echo "note: target database ${TARGET_DB} already exists"
fi
psqlc "$TARGET_DB" "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null \
  || fail "could not enable vector extension on target"

echo "== 3/4 restore =="
if [ "${ARL_RESTORE_VIA_URL:-0}" = "1" ]; then
  pg_restore -d "postgresql://${POSTGRES_USER}@${PG_HOST}:${PG_PORT}/${TARGET_DB}" \
    --clean --if-exists "$BACKUP_DIR/db.dump" \
    || fail "pg_restore failed"
else
  docker compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$TARGET_DB" \
    --clean --if-exists < "$BACKUP_DIR/db.dump" \
    || fail "pg_restore failed"
fi
pass "database restored into ${TARGET_DB}"

echo "== 4/4 verification counts (compare with backup source) =="
count() { psqlc "$TARGET_DB" "SELECT count(*) FROM $1;"; }
distinct_batches() { psqlc "$TARGET_DB" "SELECT count(DISTINCT batch_id) FROM eval_runs WHERE batch_id IS NOT NULL AND batch_id <> '';"; }
printf '%-22s %s\n' "tenants:" "$(count tenants)"
printf '%-22s %s\n' "agent_versions:" "$(count agent_versions)"
printf '%-22s %s\n' "eval_batches:" "$(distinct_batches)"
printf '%-22s %s\n' "eval_runs:" "$(count eval_runs)"
printf '%-22s %s\n' "trace_events:" "$(count trace_events)"
printf '%-22s %s\n' "approvals:" "$(count approvals)"
printf '%-22s %s\n' "release_decisions:" "$(count release_decisions)"
echo
echo "Spot-check: start the platform against ${TARGET_DB} (DATABASE_URL) and"
echo "fetch one decision and one eval run via the API to confirm readability."
echo "restore complete"
