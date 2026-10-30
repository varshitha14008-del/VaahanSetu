#!/usr/bin/env bash
# =====================================================================
#  VaahanSetu -- apply schema + seed to a cloud PostgreSQL instance
#  (Neon, Supabase, Aiven, RDS, or any other managed Postgres).
#
#  Usage:
#    ./apply_to_cloud.sh "postgres://user:pass@host:5432/db?sslmode=require"
#
#  Or via env var:
#    DATABASE_URL="postgres://..." ./apply_to_cloud.sh
#
#  This script is IDEMPOTENT: it drops the public schema and recreates
#  it before applying, so you can re-run it safely to reset the DB.
#
#  Requires: psql (PostgreSQL client) installed locally.
#    macOS:   brew install libpq
#    Ubuntu:  sudo apt install postgresql-client
#    Windows: install PostgreSQL from postgresql.org (includes psql)
# =====================================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { printf "${GREEN}✓${NC} %s\n"  "$1"; }
warn() { printf "${YELLOW}!${NC} %s\n" "$1"; }
err()  { printf "${RED}✗${NC} %s\n"    "$1"; }
step() { printf "\n${BLUE}▸${NC} %s\n" "$1"; }

# ---- Get connection string -----------------------------------------
CONN_STR="${1:-${DATABASE_URL:-}}"
if [ -z "$CONN_STR" ]; then
    err "No connection string provided."
    echo ""
    echo "Usage:"
    echo "  $0 \"postgres://user:pass@host:5432/db?sslmode=require\""
    echo ""
    echo "Or:"
    echo "  DATABASE_URL=\"postgres://...\" $0"
    echo ""
    echo "Get this string from your Neon dashboard:"
    echo "  Dashboard -> your project -> Connect -> Connection string"
    exit 1
fi

# ---- Sanity checks --------------------------------------------------
if ! command -v psql >/dev/null 2>&1; then
    err "psql is not installed."
    echo ""
    echo "Install the PostgreSQL client:"
    echo "  macOS (Homebrew):  brew install libpq && brew link --force libpq"
    echo "  Ubuntu/Debian:     sudo apt install postgresql-client"
    echo "  Fedora:            sudo dnf install postgresql"
    echo "  Windows:           https://www.postgresql.org/download/windows/"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_DIR="$(dirname "$SCRIPT_DIR")"
SCHEMA="$DB_DIR/vaahansetu_schema.sql"
SEED="$DB_DIR/vaahansetu_seed.sql"
TESTS="$DB_DIR/vaahansetu_constraint_tests.sql"

for f in "$SCHEMA" "$SEED"; do
    [ -f "$f" ] || { err "Missing required file: $f"; exit 1; }
done
ok "Found SQL files in $DB_DIR"

# ---- Confirm before destructive reset ------------------------------
# Extract a safe-to-show version of the connection (hide password)
SHOW_HOST="$(echo "$CONN_STR" | sed -E 's|^postgres(?:ql)?://[^@]*@||; s|\?.*$||')"
warn "About to DROP and recreate the 'public' schema on: $SHOW_HOST"
warn "ALL existing data in this database will be lost."
read -r -p "Type 'yes' to continue: " ANSWER
if [ "$ANSWER" != "yes" ]; then
    err "Aborted by user."
    exit 1
fi

# ---- Connect & verify -----------------------------------------------
step "Testing connection..."
if ! psql "$CONN_STR" -c "SELECT version();" >/dev/null 2>&1; then
    err "Cannot connect with the provided connection string."
    echo ""
    echo "Common causes:"
    echo "  - Wrong password (copy the full string from Neon again)"
    echo "  - Missing ?sslmode=require (Neon requires SSL)"
    echo "  - IP not allowed (Neon allows all IPs by default, so this is rare)"
    echo "  - Wrong database name in the URL (should be 'vaahansetu' if you followed the signup steps)"
    exit 1
fi
ok "Connected successfully"

# ---- Drop & recreate public schema (idempotent reset) ---------------
step "Resetting public schema..."
psql "$CONN_STR" -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO postgres;
GRANT ALL ON SCHEMA public TO public;
SQL
ok "Schema reset"

# ---- Apply DDL -------------------------------------------------------
step "Applying DDL (vaahansetu_schema.sql)..."
psql "$CONN_STR" -v ON_ERROR_STOP=1 < "$SCHEMA" 2>&1 | grep -vE "^CREATE|^COMMENT|^GRANT|^ALTER|^CREATE INDEX|^CREATE UNIQUE|^CREATE FUNCTION|^CREATE TRIGGER|^DROP|^SET$" || true
ok "DDL applied (8 enums, 15 tables, constraints, triggers, indexes)"

# ---- Apply seed data -------------------------------------------------
step "Applying seed data (vaahansetu_seed.sql)..."
psql "$CONN_STR" -v ON_ERROR_STOP=1 < "$SEED" 2>&1 | grep -vE "^INSERT|^SELECT|^ setval|^$" || true
ok "Seed data applied"

# ---- Verify ----------------------------------------------------------
step "Verifying..."
TABLE_COUNT=$(psql "$CONN_STR" -t -A -c "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public';")
ENUM_COUNT=$(psql "$CONN_STR" -t -A -c "SELECT COUNT(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public' AND t.typtype='e';")
BUS_COUNT=$(psql "$CONN_STR" -t -A -c "SELECT COUNT(*) FROM buses;")
JOURNEY_COUNT=$(psql "$CONN_STR" -t -A -c "SELECT COUNT(*) FROM passenger_journeys;")
ok "Tables: $TABLE_COUNT  |  Enums: $ENUM_COUNT  |  Buses: $BUS_COUNT  |  Journeys: $JOURNEY_COUNT"

if [ "$TABLE_COUNT" -ne 15 ] || [ "$ENUM_COUNT" -ne 8 ]; then
    err "Expected 15 tables and 8 enums. Got $TABLE_COUNT tables and $ENUM_COUNT enums."
    exit 1
fi

step "Done! Your VaahanSetu database is live on Neon."
echo ""
echo "  Connection string (keep this private, share only with teammates):"
echo "    $CONN_STR"
echo ""
echo "  For your Java app, convert to JDBC URL format:"
HOST_AND_DB="$(echo "$CONN_STR" | sed -E 's|^postgres(?:ql)?://[^@]*@||; s|\?.*$||')"
echo "    jdbc:postgresql://$HOST_AND_DB?sslmode=require"
echo "    (username and password go in the JDBC properties, not the URL)"
echo ""
echo "  Optional: run the negative tests to verify constraints fire:"
echo "    psql \"$CONN_STR\" -f $TESTS"
