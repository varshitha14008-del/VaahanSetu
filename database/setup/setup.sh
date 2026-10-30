#!/usr/bin/env bash
# =====================================================================
#  VaahanSetu -- one-command database bootstrap (macOS / Linux / WSL)
#
#  What it does:
#    1. Detects whether Docker or native psql is available.
#    2. Spins up PostgreSQL (in a container if Docker, else uses native).
#    3. Creates the `vaahansetu` database.
#    4. Applies vaahansetu_schema.sql  (DDL: enums, tables, constraints, triggers)
#    5. Applies vaahansetu_seed.sql    (small sample data)
#    6. Prints the exact command to connect.
#
#  Re-runnable: drops & recreates the database each time.
# =====================================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { printf "${GREEN}✓${NC} %s\n"  "$1"; }
warn() { printf "${YELLOW}!${NC} %s\n" "$1"; }
err()  { printf "${RED}✗${NC} %s\n"    "$1"; }
step() { printf "\n${BLUE}▸${NC} %s\n" "$1"; }

# Resolve paths relative to this script (so it works from any CWD)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_DIR="$(dirname "$SCRIPT_DIR")"      # database/ folder
SCHEMA="$DB_DIR/vaahansetu_schema.sql"
SEED="$DB_DIR/vaahansetu_seed.sql"
TESTS="$DB_DIR/vaahansetu_constraint_tests.sql"

DB_NAME="vaahansetu"
PGUSER="postgres"
PGPORT="5432"
CONTAINER="vaahansetu-db"

# Verify SQL files exist
for f in "$SCHEMA" "$SEED"; do
    [ -f "$f" ] || { err "Missing required file: $f"; exit 1; }
done
ok "Found SQL files in $DB_DIR"

# Helper: detect Docker *and* that the daemon is actually running
have_docker() {
    command -v docker >/dev/null 2>&1 || return 1
    docker info >/dev/null 2>&1     || return 1
    return 0
}

# =====================================================================
# Option A: Docker (preferred -- zero install pollution)
# =====================================================================
if have_docker; then
    step "Docker detected. Spinning up a fresh PostgreSQL 16 container..."

    # Clean up any stale container from a previous run
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

    docker run -d --name "$CONTAINER" \
        -e POSTGRES_PASSWORD=postgres \
        -p "$PGPORT":5432 \
        postgres:16 >/dev/null
    ok "Container started: name=$CONTAINER, port=$PGPORT, user=$PGUSER, password=postgres"

    step "Waiting for PostgreSQL to accept connections..."
    for i in $(seq 1 30); do
        if docker exec "$CONTAINER" pg_isready -U "$PGUSER" >/dev/null 2>&1; then
            ok "PostgreSQL is ready (after ${i}s)"
            break
        fi
        sleep 1
        if [ "$i" -eq 30 ]; then
            err "PostgreSQL did not become ready in 30s. Check: docker logs $CONTAINER"
            exit 1
        fi
    done

    step "Applying schema (vaahansetu_schema.sql)..."
    docker exec -i "$CONTAINER" psql -U "$PGUSER" -v ON_ERROR_STOP=1 < "$SCHEMA" >/dev/null
    ok "Schema applied (8 enums, 15 tables, constraints, triggers, indexes)"

    step "Applying seed data (vaahansetu_seed.sql)..."
    docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$SEED" >/dev/null
    ok "Seed data applied"

    step "Done! Your VaahanSetu database is running."
    echo ""
    echo "  Connect via psql:"
    echo "    docker exec -it $CONTAINER psql -U $PGUSER -d $DB_NAME"
    echo ""
    echo "  Or from any client (DBeaver, pgAdmin, your Java app):"
    echo "    jdbc:postgresql://localhost:$PGPORT/$DB_NAME"
    echo "    user: $PGUSER   password: postgres"
    echo ""
    echo "  Application role (already created by the schema):"
    echo "    user: vaahansetu_app   password: vaahansetu_dev_pwd"
    echo ""
    echo "  Lifecycle:"
    echo "    Stop:     docker stop $CONTAINER"
    echo "    Restart:  docker start $CONTAINER"
    echo "    Destroy:  docker rm -f $CONTAINER   (data is lost)"
    echo ""
    echo "  Verify with the negative tests:"
    echo "    docker exec -i $CONTAINER psql -U $PGUSER -d $DB_NAME < $TESTS"
    exit 0
fi

# =====================================================================
# Option B: native psql
# =====================================================================
if command -v psql >/dev/null 2>&1; then
    step "Native psql detected: $(psql --version)"

    # macOS Homebrew often uses the OS user as the postgres role
    if [ "$(uname)" = "Darwin" ]; then
        NATIVE_USER="${PGUSER:-$(whoami)}"
    else
        NATIVE_USER="$PGUSER"
    fi

    echo "  Trying to connect as user '$NATIVE_USER' on port $PGPORT..."
    echo "  (On macOS Homebrew there is usually no password. On Linux you may be"
    echo "   prompted for the postgres superuser password you set at install time.)"
    echo ""

    if ! psql -U "$NATIVE_USER" -p "$PGPORT" -d postgres -c "SELECT 1" >/dev/null 2>&1; then
        err "Cannot connect to PostgreSQL on localhost:$PGPORT as '$NATIVE_USER'."
        echo ""
        echo "  Make sure the service is running:"
        echo "    macOS (Homebrew):  brew services start postgresql"
        echo "    Linux (systemd):   sudo systemctl start postgresql"
        echo "    Linux (no systemd): sudo service postgresql start"
        echo ""
        echo "  Or if you set a non-default port, edit PGPORT at the top of this script."
        exit 1
    fi
    ok "Connected to PostgreSQL"

    step "Dropping existing database (if any) and creating a fresh one..."
    psql -U "$NATIVE_USER" -p "$PGPORT" -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;" >/dev/null
    psql -U "$NATIVE_USER" -p "$PGPORT" -d postgres -c "CREATE DATABASE $DB_NAME;" >/dev/null
    ok "Database '$DB_NAME' created"

    step "Applying schema (vaahansetu_schema.sql)..."
    psql -U "$NATIVE_USER" -p "$PGPORT" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$SCHEMA" >/dev/null
    ok "Schema applied"

    step "Applying seed data (vaahansetu_seed.sql)..."
    psql -U "$NATIVE_USER" -p "$PGPORT" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$SEED" >/dev/null
    ok "Seed data applied"

    step "Done! Your VaahanSetu database is ready."
    echo ""
    echo "  Connect via psql:"
    echo "    psql -U $NATIVE_USER -d $DB_NAME"
    echo ""
    echo "  Or from any client (DBeaver, pgAdmin, your Java app):"
    echo "    jdbc:postgresql://localhost:$PGPORT/$DB_NAME"
    echo "    user: $NATIVE_USER   (no password on macOS Homebrew; install-time password on Linux)"
    echo ""
    echo "  Application role (created by the schema; use this from Java in production):"
    echo "    user: vaahansetu_app   password: vaahansetu_dev_pwd"
    echo ""
    echo "  Verify with the negative tests:"
    echo "    psql -U $NATIVE_USER -d $DB_NAME < $TESTS"
    exit 0
fi

# =====================================================================
# Neither Docker nor psql
# =====================================================================
err "Neither Docker nor psql was found on this machine."
echo ""
echo "Install ONE of the following, then re-run this script:"
echo ""
echo "  Option 1 (recommended, zero pollution):"
echo "    Install Docker Desktop:"
echo "      macOS:   https://www.docker.com/products/docker-desktop/"
echo "      Windows: https://www.docker.com/products/docker-desktop/"
echo "      Linux:   https://docs.docker.com/engine/install/"
echo ""
echo "  Option 2 (native PostgreSQL):"
echo "    macOS (Homebrew):  brew install postgresql@16 && brew services start postgresql@16"
echo "    Ubuntu/Debian:     sudo apt update && sudo apt install -y postgresql postgresql-contrib && sudo systemctl start postgresql"
echo "    Fedora:            sudo dnf install -y postgresql-server postgresql-contrib && sudo systemctl start postgresql"
echo "    Windows:           https://www.postgresql.org/download/windows/  (run installer, set superuser password)"
echo ""
echo "Then run this script again:"
echo "    bash database/setup/setup.sh"
exit 1
