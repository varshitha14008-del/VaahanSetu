# VaahanSetu — Database Migrations

This folder contains **incremental** schema changes that you apply to an
**existing** cloud database (e.g. your shared Neon instance).

> ⚠️ **Do NOT re-run `vaahansetu_schema.sql` on an existing DB.**
> That file drops & recreates everything — it's only for fresh installs.
> To change a live DB, write a migration here and apply it.

## How to use

### As a developer making a schema change

1. Create a new file in this folder named `NNN_short_description.sql`
   where `NNN` is the next sequence number (e.g. `002_…`, `003_…`).
2. Write idempotent SQL — use `ADD COLUMN IF NOT EXISTS`,
   `DROP COLUMN IF EXISTS`, `CREATE INDEX IF NOT EXISTS`, etc., so the
   migration is safe to re-run.
3. Test locally first if possible.
4. Commit and push the migration file to the repo.
5. **Apply the migration to the cloud DB** by pasting its contents into
   the Neon SQL Editor (or running `psql -f migrations/NNN_….sql`
   against the connection string).
6. Tell the team in your private channel that a migration was applied
   and which one — Java code that depends on the new schema should be
   updated in the same commit.

### As a teammate joining an existing project

If the cloud DB has been migrated since you last looked, you don't need
to do anything to your local environment — your Java app just connects
to the cloud DB and sees the new schema. **You only need to apply
migrations locally if you run a local PostgreSQL instance for
development.** For that case, apply all migration files in numeric order
to your local DB.

## Migration log

| # | File | Description | Date |
|---|------|-------------|------|
| 001 | `001_add_rest_slots_to_buses.sql` | Added `rest_start` and `rest_end` (TIME, NULL) to `buses` for daily rest-slot windows. Backfilled sample data. | 2026-10-30 |

## Conventions

* **Idempotent**: every migration must be safe to re-run. Use
  `IF EXISTS` / `IF NOT EXISTS` clauses everywhere.
* **Non-destructive**: never `DROP` a column or table without team
  discussion first. If a column must be removed, do it in two phases:
  (1) a migration that marks it deprecated, (2) a later migration that
  drops it after the Java code no longer references it.
* **One concern per file**: don't bundle unrelated changes.
* **Comment the why**: a migration is forever — explain why the change
  was made, not just what it does. Future you will thank present you.
* **Numbered sequentially**: `001_`, `002_`, `003_` — never re-use a
  number, never insert one in the middle. If two teammates create
  migrations simultaneously, rename one to keep the order unambiguous.
