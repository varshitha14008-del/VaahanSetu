-- =====================================================================
--  Migration 006: Trip-Specific Administrative Demand Adjustments
--  -------------------------------------------------------------------
--  Adds nullable trip_id column to segment_demand_adjustments to allow
--  administrative adjustments to target a specific scheduled trip while
--  preserving backwards-compatible route-wide semantics for historical
--  adjustments where trip_id IS NULL.
--
--  Idempotent: uses IF NOT EXISTS and ADD COLUMN IF NOT EXISTS.
--  Non-destructive: preserves existing adjustment records and NULL values.
-- =====================================================================

BEGIN;

ALTER TABLE segment_demand_adjustments
    ADD COLUMN IF NOT EXISTS trip_id BIGINT REFERENCES scheduled_trips(trip_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_seg_demand_adj_trip
    ON segment_demand_adjustments (trip_id);

CREATE INDEX IF NOT EXISTS idx_seg_demand_adj_session_route_trip
    ON segment_demand_adjustments (session_id, route_id, trip_id);

COMMENT ON COLUMN segment_demand_adjustments.trip_id IS 'Target scheduled trip for trip-specific operational adjustments. If NULL, applies route-wide (historical/simulation).';

COMMIT;
