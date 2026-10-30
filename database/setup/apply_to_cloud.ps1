# =====================================================================
#  VaahanSetu -- apply schema + seed to a cloud PostgreSQL instance
#  (Neon, Supabase, Aiven, RDS, or any other managed Postgres).
#  PowerShell version for Windows.
#
#  Usage:
#    .\apply_to_cloud.ps1 "postgres://user:pass@host:5432/db?sslmode=require"
#
#  Or via env var:
#    $env:DATABASE_URL="postgres://..."; .\apply_to_cloud.ps1
#
#  Requires: psql (PostgreSQL client) installed.
#    Install from https://www.postgresql.org/download/windows/
#    (the installer includes psql in PATH)
# =====================================================================
param(
    [Parameter(Position=0)]
    [string]$ConnectionString = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrEmpty($ConnectionString)) {
    $ConnectionString = $env:DATABASE_URL
}

if ([string]::IsNullOrEmpty($ConnectionString)) {
    Write-Host "No connection string provided." -ForegroundColor Red
    Write-Host ""
    Write-Host "Usage:"
    Write-Host '  .\apply_to_cloud.ps1 "postgres://user:pass@host:5432/db?sslmode=require"'
    Write-Host ""
    Write-Host "Or:"
    Write-Host '  $env:DATABASE_URL="postgres://..."; .\apply_to_cloud.ps1'
    Write-Host ""
    Write-Host "Get this string from your Neon dashboard:"
    Write-Host "  Dashboard -> your project -> Connect -> Connection string"
    exit 1
}

# Check psql is installed
$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
    Write-Host "psql is not installed or not in PATH." -ForegroundColor Red
    Write-Host ""
    Write-Host "Install PostgreSQL from https://www.postgresql.org/download/windows/"
    Write-Host "The installer adds psql to PATH. Restart your terminal after install."
    exit 1
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DbDir = Split-Path -Parent $ScriptDir
$Schema = Join-Path $DbDir "vaahansetu_schema.sql"
$Seed = Join-Path $DbDir "vaahansetu_seed.sql"
$Tests = Join-Path $DbDir "vaahansetu_constraint_tests.sql"

foreach ($f in @($Schema, $Seed)) {
    if (-not (Test-Path $f)) {
        Write-Host "Missing required file: $f" -ForegroundColor Red
        exit 1
    }
}
Write-Host "Found SQL files in $DbDir" -ForegroundColor Green

# Show a redacted host for confirmation
if ($ConnectionString -match '://[^@]*@([^?]+)') {
    $ShowHost = $Matches[1]
} else {
    $ShowHost = "(could not parse host)"
}
Write-Host "! About to DROP and recreate the 'public' schema on: $ShowHost" -ForegroundColor Yellow
Write-Host "! ALL existing data in this database will be lost." -ForegroundColor Yellow
$answer = Read-Host "Type 'yes' to continue"
if ($answer -ne "yes") {
    Write-Host "Aborted by user." -ForegroundColor Red
    exit 1
}

# Test connection
Write-Host ""
Write-Host "Testing connection..." -ForegroundColor Cyan
$env:PGPASSWORD = ""
try {
    $versionResult = & psql $ConnectionString -t -A -c "SELECT version();" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw $versionResult
    }
    Write-Host "Connected successfully" -ForegroundColor Green
} catch {
    Write-Host "Cannot connect with the provided connection string." -ForegroundColor Red
    Write-Host ""
    Write-Host "Common causes:"
    Write-Host "  - Wrong password (copy the full string from Neon again)"
    Write-Host "  - Missing ?sslmode=require (Neon requires SSL)"
    Write-Host "  - Wrong database name in the URL (should be 'vaahansetu')"
    exit 1
}

# Reset schema
Write-Host ""
Write-Host "Resetting public schema..." -ForegroundColor Cyan
$resetSql = @"
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO postgres;
GRANT ALL ON SCHEMA public TO public;
"@
$resetSql | & psql $ConnectionString -v ON_ERROR_STOP=1 2>&1 | Out-Null
Write-Host "Schema reset" -ForegroundColor Green

# Apply DDL
Write-Host ""
Write-Host "Applying DDL (vaahansetu_schema.sql)..." -ForegroundColor Cyan
& psql $ConnectionString -v ON_ERROR_STOP=1 -f $Schema 2>&1 | Out-Null
Write-Host "DDL applied (8 enums, 15 tables, constraints, triggers, indexes)" -ForegroundColor Green

# Apply seed
Write-Host ""
Write-Host "Applying seed data (vaahansetu_seed.sql)..." -ForegroundColor Cyan
& psql $ConnectionString -v ON_ERROR_STOP=1 -f $Seed 2>&1 | Out-Null
Write-Host "Seed data applied" -ForegroundColor Green

# Verify
Write-Host ""
Write-Host "Verifying..." -ForegroundColor Cyan
$tableCount = (& psql $ConnectionString -t -A -c "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public';").Trim()
$enumCount = (& psql $ConnectionString -t -A -c "SELECT COUNT(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public' AND t.typtype='e';").Trim()
$busCount = (& psql $ConnectionString -t -A -c "SELECT COUNT(*) FROM buses;").Trim()
$journeyCount = (& psql $ConnectionString -t -A -c "SELECT COUNT(*) FROM passenger_journeys;").Trim()
Write-Host "Tables: $tableCount  |  Enums: $enumCount  |  Buses: $busCount  |  Journeys: $journeyCount" -ForegroundColor Green

if ([int]$tableCount -ne 15 -or [int]$enumCount -ne 8) {
    Write-Host "Expected 15 tables and 8 enums. Got $tableCount tables and $enumCount enums." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Done! Your VaahanSetu database is live on Neon." -ForegroundColor Cyan
Write-Host ""
Write-Host "Connection string (keep private, share only with teammates):"
Write-Host "  $ConnectionString"
Write-Host ""
Write-Host "For your Java app, convert to JDBC URL format:"
if ($ConnectionString -match '://[^@]*@([^?]+)') {
    $hostAndDb = $Matches[1]
    Write-Host "  jdbc:postgresql://$hostAndDb`?sslmode=require"
    Write-Host "  (username and password go in the JDBC properties, not the URL)"
}
Write-Host ""
Write-Host "Optional: run the negative tests to verify constraints fire:"
Write-Host "  psql `"$ConnectionString`" -f $Tests"
