-- =====================================================================
--  Migration 001: add rest_start and rest_end to buses
--  -------------------------------------------------------------------
--  Adds two TIME columns to capture the daily rest-slot window for
--  each bus. NULL means "no fixed daily rest slot" (the existing
--  rest_duration_minutes column still applies as the minimum rest
--  interval after a trip).
--
--  Both columns are TIME (no date component) because rest slots are
--  recurring daily windows, not one-off events.
--
--  Overnight windows ARE allowed: rest_end may be earlier than
--  rest_start (e.g. rest_start = '23:00:00', rest_end = '02:00:00'
--  means rest from 11 PM to 2 AM next day). The Java service layer
--  must handle this wraparound when checking bus availability.
--
--  Idempotent: re-running is safe (uses IF NOT EXISTS).
--  Non-destructive: does NOT drop or alter existing data.
-- =====================================================================

ALTER TABLE buses
    ADD COLUMN IF NOT EXISTS rest_start TIME NULL,
    ADD COLUMN IF NOT EXISTS rest_end   TIME NULL;

COMMENT ON COLUMN buses.rest_start IS 'Daily rest-slot start (clock time, NULL = no fixed slot). May be later than rest_end for overnight rests.';
COMMENT ON COLUMN buses.rest_end   IS 'Daily rest-slot end (clock time, NULL = no fixed slot). May be earlier than rest_start for overnight rests.';

-- Optional: backfill sample data for the existing 4 buses so the new
-- columns aren't all NULL. Adjust or remove these to taste.
UPDATE buses SET rest_start = '13:00:00', rest_end = '13:30:00' WHERE bus_id = 101;
UPDATE buses SET rest_start = '13:00:00', rest_end = '13:30:00' WHERE bus_id = 102;
UPDATE buses SET rest_start = NULL,        rest_end = NULL        WHERE bus_id = 103;  -- in maintenance, no slot
UPDATE buses SET rest_start = '14:00:00', rest_end = '14:30:00' WHERE bus_id = 104;
