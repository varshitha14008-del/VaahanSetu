-- =====================================================================
--  Migration 005: support multiple buses per scheduled trip
--  -------------------------------------------------------------------
--  Enables genuine multi-bus-per-trip allocation, persistence, and approval.
--
--  1. Creates child table allocation_assignment_buses to store all proposed
--     buses assigned to each trip in an allocation run.
--  2. Backfills existing allocation_assignments rows into the child table.
--  3. Replaces uq_trip_one_active_assignment with uq_trip_bus_active_assignment
--     on trip_assignments, allowing a trip to have multiple ACTIVE buses
--     while preventing the same bus from being assigned twice to the same trip.
--
--  Idempotent: uses IF NOT EXISTS and IF EXISTS.
--  Non-destructive: preserves existing tables, foreign keys, and assignment history.
-- =====================================================================

BEGIN;

-- 1. Create detail table for proposed bus assignments
CREATE TABLE IF NOT EXISTS allocation_assignment_buses (
    allocation_assignment_id BIGINT      NOT NULL REFERENCES allocation_assignments(allocation_assignment_id) ON DELETE CASCADE,
    bus_id                   BIGINT      NOT NULL REFERENCES buses(bus_id) ON DELETE RESTRICT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (allocation_assignment_id, bus_id)
);

CREATE INDEX IF NOT EXISTS idx_allocation_assignment_buses_bus
    ON allocation_assignment_buses (bus_id);

-- 2. Backfill existing historical allocation runs
INSERT INTO allocation_assignment_buses (allocation_assignment_id, bus_id)
SELECT allocation_assignment_id, proposed_bus_id
FROM allocation_assignments
WHERE proposed_bus_id IS NOT NULL
ON CONFLICT (allocation_assignment_id, bus_id) DO NOTHING;

-- 3. Replace single-active-bus constraint with per-bus-per-trip active uniqueness
DROP INDEX IF EXISTS uq_trip_one_active_assignment;

CREATE UNIQUE INDEX IF NOT EXISTS uq_trip_bus_active_assignment
    ON trip_assignments (trip_id, bus_id)
    WHERE status = 'ACTIVE';

COMMIT;
