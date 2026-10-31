-- =====================================================================
--  VaahanSetu  --  constraint / trigger negative tests
--  Each block is wrapped in DO/EXCEPTION so we can report results
--  in a single run instead of aborting on the first failure.
-- =====================================================================
\set ON_ERROR_STOP on

CREATE TEMP TABLE test_results(test_name TEXT, expected TEXT, actual TEXT);

-- Helper: run a SQL fragment, capture whether it raised.
CREATE OR REPLACE FUNCTION pg_temp.try_block(sql_text TEXT) RETURNS TEXT AS $$
BEGIN
    EXECUTE sql_text;
    RETURN 'OK (no error)';
EXCEPTION WHEN OTHERS THEN
    RETURN SQLERRM;
END;
$$ LANGUAGE plpgsql;

-- ---------- Test 1: cannot have two ACTIVE assignments for the same trip
INSERT INTO pg_temp.test_results
SELECT 'two ACTIVE assignments for same trip',
       'should fail (partial unique)',
       pg_temp.try_block($sql$
           INSERT INTO trip_assignments (trip_id, bus_id, assigned_by, status)
           VALUES (1001, 102, 1, 'ACTIVE');
       $sql$);

-- ---------- Test 2: cannot have two ACTIVE planning sessions
INSERT INTO pg_temp.test_results
SELECT 'two ACTIVE planning sessions',
       'should fail (partial unique)',
       pg_temp.try_block($sql$
           INSERT INTO planning_sessions (session_name, status)
           VALUES ('second active session', 'ACTIVE');
       $sql$);

-- ---------- Test 3: passenger journey boarding after destination
INSERT INTO pg_temp.test_results
SELECT 'boarding-after-destination journey',
       'should fail (trigger trg_pj_check_stop_order)',
       pg_temp.try_block($sql$
           INSERT INTO passenger_journeys
               (session_id, trip_id, boarding_stop_id, destination_stop_id, passenger_count, journey_status)
           VALUES (1, 1001, 4, 1, 1, 'REGISTERED');  -- boarding=D(4), destination=A(1) on route 1
       $sql$);

-- ---------- Test 4: passenger journey boarding == destination
INSERT INTO pg_temp.test_results
SELECT 'boarding == destination journey',
       'should fail (trigger trg_pj_check_stop_order)',
       pg_temp.try_block($sql$
           INSERT INTO passenger_journeys
               (session_id, trip_id, boarding_stop_id, destination_stop_id, passenger_count, journey_status)
           VALUES (1, 1001, 2, 2, 1, 'REGISTERED');
       $sql$);

-- ---------- Test 5: passenger journey using a stop that is NOT on the trip route
INSERT INTO pg_temp.test_results
SELECT 'boarding stop not on trip route',
       'should fail (trigger trg_pj_check_stop_order)',
       pg_temp.try_block($sql$
           INSERT INTO passenger_journeys
               (session_id, trip_id, boarding_stop_id, destination_stop_id, passenger_count, journey_status)
           VALUES (1, 1001, 5, 3, 1, 'REGISTERED');  -- stop 5 is on route 2, not route 1
       $sql$);

-- ---------- Test 6: bus capacity <= 0
INSERT INTO pg_temp.test_results
SELECT 'bus capacity = 0',
       'should fail (CHECK capacity > 0)',
       pg_temp.try_block($sql$
           INSERT INTO buses (registration_number, capacity, status)
           VALUES ('KA-00-ZZ-0000', 0, 'ACTIVE');
       $sql$);

-- ---------- Test 7: duplicate username
INSERT INTO pg_temp.test_results
SELECT 'duplicate username',
       'should fail (UNIQUE users.username)',
       pg_temp.try_block($sql$
           INSERT INTO users (full_name, username, password_hash, role)
           VALUES ('Another Admin', 'admin', 'x', 'ADMIN');
       $sql$);

-- ---------- Test 8: duplicate route code
INSERT INTO pg_temp.test_results
SELECT 'duplicate route_code',
       'should fail (UNIQUE routes.route_code)',
       pg_temp.try_block($sql$
           INSERT INTO routes (route_code, route_name, origin_name, destination_name, estimated_duration_minutes)
           VALUES ('R1', 'dup', 'a', 'b', 10);
       $sql$);

-- ---------- Test 9: negative rest_duration_minutes
INSERT INTO pg_temp.test_results
SELECT 'negative rest_duration_minutes',
       'should fail (CHECK rest_duration_minutes >= 0)',
       pg_temp.try_block($sql$
           UPDATE buses SET rest_duration_minutes = -5 WHERE bus_id = 101;
       $sql$);

-- ---------- Test 9A: rest window must provide both start and end
INSERT INTO pg_temp.test_results
SELECT 'incomplete fixed rest window',
       'should fail (CHECK both rest_start and rest_end together)',
       pg_temp.try_block($sql$
           INSERT INTO buses (registration_number, capacity, status, rest_start)
           VALUES ('KA-00-ZZ-0001', 40, 'ACTIVE', TIME '10:00');
       $sql$);

-- ---------- Test 9B: valid fixed rest window is accepted
INSERT INTO pg_temp.test_results
SELECT 'valid fixed rest window',
       'should succeed',
       pg_temp.try_block($sql$
           INSERT INTO buses
               (registration_number, capacity, status, rest_start, rest_end)
           VALUES ('KA-00-ZZ-0002', 40, 'ACTIVE', TIME '10:00', TIME '11:00');
       $sql$);

-- ---------- Test 10: try to delete a route_stop leaving route with < 2 stops
--         Route 2 currently has 3 stops (X,Y,Z). Delete two of them — the
--         second delete must be blocked.
INSERT INTO pg_temp.test_results
SELECT 'delete route_stops below minimum 2',
       'should fail on second delete (trigger trg_route_stops_min_two)',
       pg_temp.try_block($sql$
           DELETE FROM route_stops WHERE route_id = 2 AND stop_id = 7;  -- leaves 2 stops, OK
           DELETE FROM route_stops WHERE route_id = 2 AND stop_id = 6;  -- would leave 1 stop, FAIL
       $sql$);

-- ---------- Test 11: negative demand in allocation_segment_results
INSERT INTO pg_temp.test_results
SELECT 'negative segment demand',
       'should fail (CHECK demand >= 0)',
       pg_temp.try_block($sql$
           INSERT INTO allocation_segment_results
               (allocation_run_id, route_id, segment_start_stop_id, segment_end_stop_id,
                demand, allocated_capacity, accommodated, unmet_demand, unused_capacity)
           VALUES (2, 1, 1, 2, -1, 45, 0, 1, 45);
       $sql$);

-- ---------- Test 12: invalid enum value (assignment_status 'PAUSED')
INSERT INTO pg_temp.test_results
SELECT 'invalid enum value',
       'should fail (assignment_status has no PAUSED)',
       pg_temp.try_block($sql$
           UPDATE trip_assignments SET status = 'PAUSED' WHERE assignment_id = 1;
       $sql$);

-- ---------- Test 13: duplicate (allocation_run_id, trip_id) in allocation_assignments
INSERT INTO pg_temp.test_results
SELECT 'duplicate allocation outcome per trip per run',
       'should fail (UNIQUE allocation_run_id, trip_id)',
       pg_temp.try_block($sql$
           INSERT INTO allocation_assignments
               (allocation_run_id, trip_id, previous_bus_id, proposed_bus_id, assignment_type)
           VALUES (1, 1001, 101, 101, 'UNCHANGED');
       $sql$);

-- ---------- Test 14: FK violation — trip_assignment referencing nonexistent bus
INSERT INTO pg_temp.test_results
SELECT 'FK violation: bus_id=99999',
       'should fail (FK trip_assignments_bus_id_fkey)',
       pg_temp.try_block($sql$
           INSERT INTO trip_assignments (trip_id, bus_id, assigned_by, status)
           VALUES (1001, 99999, 1, 'REPLACED');  -- REPLACED so partial-unique does not fire
       $sql$);

-- ---------- Test 15: confirmation timestamps reversed
INSERT INTO pg_temp.test_results
SELECT 'alighting before boarding',
       'should fail (trigger trg_pj_confirmations)',
       pg_temp.try_block($sql$
           UPDATE passenger_journeys
              SET boarding_confirmed_at = TIMESTAMPTZ '2026-10-26 09:00:00+05:30',
                  alighting_confirmed_at = TIMESTAMPTZ '2026-10-26 08:00:00+05:30'
            WHERE journey_id = 2;
       $sql$);

SELECT * FROM pg_temp.test_results ORDER BY test_name;
