package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import com.vaahansetu.allocation.AllocationModels.TripOutcome;
import com.vaahansetu.repository.JourneyRepository;
import com.vaahansetu.repository.OfficerRepository;

class MultiBusPersistenceAndApprovalTest {

    private JdbcTemplate jdbcTemplate;
    private OfficerRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new OfficerRepository(jdbcTemplate);

        // Default: run status is PROPOSED
        when(jdbcTemplate.queryForList(contains("SELECT status FROM allocation_runs"), any(Object.class)))
                .thenReturn(List.of(Map.of("status", "PROPOSED")));

        // Default: proposed buses exist and are ACTIVE with 30-min turnaround rest
        when(jdbcTemplate.queryForList(contains("FROM buses WHERE bus_id IN"), any(Object[].class)))
                .thenAnswer(inv -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Object[] rawArgs = inv.getArguments();
                    for (int i = 1; i < rawArgs.length; i++) {
                        Object item = rawArgs[i];
                        if (item instanceof Object[] arr) {
                            for (Object bId : arr) {
                                rows.add(createActiveBusRow(bId));
                            }
                        } else {
                            rows.add(createActiveBusRow(item));
                        }
                    }
                    return rows;
                });

        // Default: no external conflicting active commitments
        when(jdbcTemplate.queryForList(contains("WHERE ta.status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of());
    }

    private static Map<String, Object> createActiveBusRow(Object bId) {
        return Map.of(
                "busId", bId,
                "registrationNumber", "TS-01-UB-" + bId,
                "capacity", 50,
                "status", "ACTIVE",
                "restDurationMinutes", 30
        );
    }

    @Test
    @DisplayName("Item 1: Saving two proposed buses for one trip persists in child table")
    void test1_SavingTwoProposedBusesForOneTrip() {
        long runId = 100L;
        TripOutcome outcome = new TripOutcome(1L, 10L, null, 101L, "REASSIGNED", "2 buses assigned", List.of(101L, 102L), 100, 0);

        // Mock assignment ID lookup
        when(jdbcTemplate.queryForList(contains("SELECT allocation_assignment_id, trip_id"), eq(runId)))
                .thenReturn(List.of(Map.of("allocation_assignment_id", 501L, "trip_id", 1L)));

        repository.insertAllocationAssignmentsBatch(runId, List.of(outcome), false);

        // Verify parent table batchUpdate was called
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(contains("INSERT INTO allocation_assignments"), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));

        // Verify child table batchUpdate was called with both buses for assignment 501
        ArgumentCaptor<List<Object[]>> batchArgsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                contains("INSERT INTO allocation_assignment_buses"),
                batchArgsCaptor.capture(),
                anyInt(),
                any(ParameterizedPreparedStatementSetter.class)
        );

        List<Object[]> insertedRows = batchArgsCaptor.getValue();
        assertEquals(2, insertedRows.size(), "Both proposed buses must be inserted into child table");
        assertEquals(501L, insertedRows.get(0)[0]);
        assertEquals(101L, insertedRows.get(0)[1]);
        assertEquals(501L, insertedRows.get(1)[0]);
        assertEquals(102L, insertedRows.get(1)[1]);
    }

    @Test
    @DisplayName("Item 2: Saving three proposed buses across two trips")
    void test2_SavingThreeProposedBusesAcrossTwoTrips() {
        long runId = 200L;
        // Trip 1 has 2 buses (101, 102); Trip 2 has 1 bus (103)
        TripOutcome o1 = new TripOutcome(1L, 10L, null, 101L, "REASSIGNED", "2 buses", List.of(101L, 102L), 100, 0);
        TripOutcome o2 = new TripOutcome(2L, 10L, null, 103L, "REASSIGNED", "1 bus", List.of(103L), 30, 0);

        when(jdbcTemplate.queryForList(contains("SELECT allocation_assignment_id, trip_id"), eq(runId)))
                .thenReturn(List.of(
                        Map.of("allocation_assignment_id", 601L, "trip_id", 1L),
                        Map.of("allocation_assignment_id", 602L, "trip_id", 2L)
                ));

        repository.insertAllocationAssignmentsBatch(runId, List.of(o1, o2), false);

        ArgumentCaptor<List<Object[]>> batchArgsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                contains("INSERT INTO allocation_assignment_buses"),
                batchArgsCaptor.capture(),
                anyInt(),
                any(ParameterizedPreparedStatementSetter.class)
        );

        List<Object[]> insertedRows = batchArgsCaptor.getValue();
        assertEquals(3, insertedRows.size(), "All 3 proposed buses across both trips must be saved");
        assertEquals(601L, insertedRows.get(0)[0]);
        assertEquals(101L, insertedRows.get(0)[1]);
        assertEquals(601L, insertedRows.get(1)[0]);
        assertEquals(102L, insertedRows.get(1)[1]);
        assertEquals(602L, insertedRows.get(2)[0]);
        assertEquals(103L, insertedRows.get(2)[1]);
    }

    @Test
    @DisplayName("Item 3 & 4: Retrieving proposed buses after reloading returns combined capacity, registrations, and occupancy")
    void test3_4_RetrievingAllProposedBusesAfterReload() {
        long runId = 300L;

        // Parent allocation assignment row
        Map<String, Object> parentRow = new HashMap<>();
        parentRow.put("allocationAssignmentId", 701L);
        parentRow.put("tripId", 1L);
        parentRow.put("routeCode", "8A");
        parentRow.put("departureTime", "2026-11-08 05:30:00");
        parentRow.put("arrivalTime", "2026-11-08 07:10:00");
        parentRow.put("peakDemand", 100);
        parentRow.put("previousBusId", null);
        parentRow.put("proposedBusId", 101L);
        parentRow.put("assignmentType", "REASSIGNED");
        parentRow.put("reason", "Assigned 2 buses");

        when(jdbcTemplate.queryForList(contains("FROM allocation_assignments aa"), eq(runId)))
                .thenReturn(List.of(parentRow));

        // Child detail rows representing 2 buses (each 60 seats)
        Map<String, Object> bus1 = Map.of("assignmentId", 701L, "busId", 101L, "registrationNumber", "TS-01-UB-1001", "capacity", 60);
        Map<String, Object> bus2 = Map.of("assignmentId", 701L, "busId", 102L, "registrationNumber", "TS-01-UB-1002", "capacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM allocation_assignment_buses aab"), eq(runId)))
                .thenReturn(List.of(bus1, bus2));

        List<Map<String, Object>> assignments = repository.findAllocationAssignments(runId);

        assertEquals(1, assignments.size());
        Map<String, Object> a = assignments.get(0);

        // Verify multi-bus fields are populated directly from DB query
        assertEquals(List.of(101L, 102L), a.get("proposedBusIds"));
        assertEquals("TS-01-UB-1001, TS-01-UB-1002", a.get("proposedBusRegistration"));
        assertEquals(120, a.get("proposedCapacity"), "Combined capacity must be 60 + 60 = 120");
        assertEquals(83, a.get("occupancy"), "Occupancy must be 100 / 120 = 83%");
        assertEquals("2026-11-08 07:10:00", a.get("arrivalTime"), "Arrival time must be preserved");
    }

    @Test
    @DisplayName("Item 5 & 6: Approving a trip with multiple proposed buses activates ALL buses in trip_assignments")
    void test5_6_ApprovingTripWithMultipleBusesActivatesAll() {
        long runId = 400L;
        long adminId = 999L;

        // Run has 1 reassigned assignment
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 801L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 05:30:00",
                        "expectedArrivalTime", "2026-11-08 07:00:00"
                )));

        // Child table returns 2 buses (101, 102)
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(801L)))
                .thenReturn(List.of(101L, 102L));

        repository.approveAllocationRun(runId, adminId);

        // Verify run marked APPROVED
        verify(jdbcTemplate).update(contains("UPDATE allocation_runs SET status = 'APPROVED'"), eq(adminId), eq(runId));

        // Verify previous active assignments replaced
        verify(jdbcTemplate).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), eq(1L));

        // Verify separate ACTIVE insert for Bus 101
        verify(jdbcTemplate).update(contains("INSERT INTO trip_assignments"), eq(1L), eq(101L), eq(adminId));

        // Verify separate ACTIVE insert for Bus 102
        verify(jdbcTemplate).update(contains("INSERT INTO trip_assignments"), eq(1L), eq(102L), eq(adminId));
    }

    @Test
    @DisplayName("Item 7: Previous assignments receive REPLACED status")
    void test7_PreviousAssignmentsReceiveReplacedStatus() {
        long runId = 450L;
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 850L,
                        "tripId", 5L,
                        "primaryBusId", 105L,
                        "departureTime", "2026-11-08 06:00:00",
                        "expectedArrivalTime", "2026-11-08 07:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(850L)))
                .thenReturn(List.of(105L));

        repository.approveAllocationRun(runId, 1L);

        verify(jdbcTemplate).update(
                eq("UPDATE trip_assignments SET status = 'REPLACED'::assignment_status WHERE trip_id = ? AND status = 'ACTIVE'::assignment_status"),
                eq(5L)
        );
    }

    @Test
    @DisplayName("Item 8: Duplicate approval requests are safe and idempotent")
    void test8_DuplicateApprovalSafeAgainstDuplicateKey() {
        long runId = 500L;
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 901L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 08:00:00",
                        "expectedArrivalTime", "2026-11-08 09:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(901L)))
                .thenReturn(List.of(101L));

        repository.approveAllocationRun(runId, 1L);

        // Verify ON CONFLICT DO NOTHING clause is included in trip_assignments insert
        verify(jdbcTemplate).update(
                contains("ON CONFLICT (trip_id, bus_id) WHERE status = 'ACTIVE' DO NOTHING"),
                eq(1L), eq(101L), eq(1L)
        );
    }

    @Test
    @DisplayName("Item 9: Historical single-bus allocation runs still load and approve with fallback")
    void test9_HistoricalSingleBusRunsLoadAndApproveViaFallback() {
        long historicalRunId = 600L;

        // Parent table has row with proposed_bus_id = 105, but child table is empty
        Map<String, Object> parentRow = new HashMap<>();
        parentRow.put("allocationAssignmentId", 990L);
        parentRow.put("tripId", 5L);
        parentRow.put("routeCode", "8A");
        parentRow.put("departureTime", "2026-11-08 06:00:00");
        parentRow.put("arrivalTime", "2026-11-08 07:30:00");
        parentRow.put("peakDemand", 40);
        parentRow.put("previousBusId", null);
        parentRow.put("proposedBusId", 105L);
        parentRow.put("proposedBusRegistration", "TS-01-UB-1005");
        parentRow.put("proposedCapacity", 60);
        parentRow.put("assignmentType", "REASSIGNED");
        parentRow.put("reason", "Assigned 1 bus");

        when(jdbcTemplate.queryForList(contains("FROM allocation_assignments aa"), eq(historicalRunId)))
                .thenReturn(List.of(parentRow));
        when(jdbcTemplate.queryForList(contains("FROM allocation_assignment_buses aab"), eq(historicalRunId)))
                .thenReturn(List.of()); // Empty child table!

        List<Map<String, Object>> assignments = repository.findAllocationAssignments(historicalRunId);

        assertEquals(1, assignments.size());
        assertEquals(List.of(105L), assignments.get(0).get("proposedBusIds"));
        assertEquals("TS-01-UB-1005", assignments.get(0).get("proposedBusRegistration"));
        assertEquals(60, assignments.get(0).get("proposedCapacity"));

        // Now test approval fallback
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(historicalRunId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 990L,
                        "tripId", 5L,
                        "primaryBusId", 105L,
                        "departureTime", "2026-11-08 06:00:00",
                        "expectedArrivalTime", "2026-11-08 07:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(990L)))
                .thenReturn(List.of()); // Empty child table!

        repository.approveAllocationRun(historicalRunId, 1L);

        // Verify primaryBusId fallback was activated
        verify(jdbcTemplate).update(contains("INSERT INTO trip_assignments"), eq(5L), eq(105L), eq(1L));
    }

    @Test
    @DisplayName("Item 10: Unassigned trips remain unassigned during approval")
    void test10_UnassignedTripsRemainUnassigned() {
        long runId = 700L;

        // Allocation has an UNASSIGNED trip (primaryBusId is null and assignmentType is UNASSIGNED)
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of()); // REASSIGNED query returns empty

        repository.approveAllocationRun(runId, 1L);

        // No trip_assignments should be inserted or replaced
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trip_assignments"), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), any(Object.class));
    }

    // =========================================================================
    // FOCUSED REGRESSION TESTS (BLOCKERS 1 to 4)
    // =========================================================================

    @Test
    @DisplayName("Requirement 1: findEngineTrips returns exactly one trip with combined capacity when two ACTIVE buses are assigned")
    void testReq1_findEngineTripsReturnsOneTripForMultipleActiveBuses() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 8);
        LocalDateTime fromTime = serviceDate.atStartOfDay();
        long sessionId = 10L;

        // Mock DB query result where LATERAL join aggregated 2 active buses (Bus 101: 50, Bus 102: 50 -> 100 capacity)
        when(jdbcTemplate.queryForList(
                contains("LEFT JOIN LATERAL"),
                eq(serviceDate), eq(fromTime), eq(sessionId), eq(sessionId)))
                .thenReturn(List.of(Map.of(
                        "tripId", 1L,
                        "routeId", 10L,
                        "departureTime", LocalDateTime.of(2026, 11, 8, 8, 0),
                        "expectedArrivalTime", LocalDateTime.of(2026, 11, 8, 9, 30),
                        "currentBusId", 101L,
                        "currentBusCapacity", 100
                )));

        List<Map<String, Object>> trips = repository.findEngineTrips(sessionId, serviceDate, fromTime);

        assertEquals(1, trips.size(), "findEngineTrips must return exactly 1 trip row, never duplicated");
        assertEquals(1L, trips.get(0).get("tripId"));
        assertEquals(100, trips.get(0).get("currentBusCapacity"), "Current capacity must be the combined sum (50 + 50 = 100)");
    }

    @Test
    @DisplayName("Requirement 2: findDeparturesForRoute returns exactly one departure with comma-separated registrations and combined capacity")
    void testReq2_findDeparturesForRouteReturnsAggregatedBuses() {
        JourneyRepository journeyRepo = new JourneyRepository(jdbcTemplate);
        LocalDate serviceDate = LocalDate.of(2026, 11, 8);
        long routeId = 5L;

        Map<String, Object> departureRow = new HashMap<>();
        departureRow.put("tripId", 101L);
        departureRow.put("routeId", routeId);
        departureRow.put("serviceDate", serviceDate);
        departureRow.put("departureTimeFormatted", "08:30");
        departureRow.put("arrivalTimeFormatted", "10:00");
        departureRow.put("departureTime", LocalDateTime.of(2026, 11, 8, 8, 30));
        departureRow.put("expectedArrivalTime", LocalDateTime.of(2026, 11, 8, 10, 0));
        departureRow.put("status", "SCHEDULED");
        departureRow.put("busId", 101L);
        departureRow.put("registrationNumber", "TS-01-UB-1001, TS-01-UB-1002");
        departureRow.put("capacity", 110);

        // Mock DB return for JOIN LATERAL aggregation
        when(jdbcTemplate.queryForList(
                contains("JOIN LATERAL"),
                eq(routeId), eq(serviceDate), eq(routeId)))
                .thenReturn(List.of(departureRow));

        List<Map<String, Object>> departures = journeyRepo.findDeparturesForRoute(routeId, serviceDate);

        assertEquals(1, departures.size(), "findDeparturesForRoute must return exactly 1 departure card per scheduled trip");
        assertEquals("TS-01-UB-1001, TS-01-UB-1002", departures.get(0).get("registrationNumber"));
        assertEquals(110, departures.get(0).get("capacity"), "Must report combined capacity across both active buses");
    }

    @Test
    @DisplayName("Requirement 3: findSegmentDemandForTrip returns one row per segment when multiple buses are active")
    void testReq3_findSegmentDemandForTripDoesNotDuplicateSegments() {
        long tripId = 200L;
        long sessionId = 1L;

        // Mock 3 distinct route segments returned by query
        when(jdbcTemplate.queryForList(contains("segment_demand_adjustments"), eq(tripId), eq(sessionId), eq(sessionId), eq(sessionId), eq(sessionId), eq(tripId)))
                .thenReturn(List.of(
                        Map.of("segmentIndex", 1, "startStopId", 1L, "endStopId", 2L, "effectiveDemand", 45, "currentBus", "TS-01-UB-1001, TS-01-UB-1002", "currentCapacity", 100),
                        Map.of("segmentIndex", 2, "startStopId", 2L, "endStopId", 3L, "effectiveDemand", 55, "currentBus", "TS-01-UB-1001, TS-01-UB-1002", "currentCapacity", 100),
                        Map.of("segmentIndex", 3, "startStopId", 3L, "endStopId", 4L, "effectiveDemand", 30, "currentBus", "TS-01-UB-1001, TS-01-UB-1002", "currentCapacity", 100)
                ));

        List<Map<String, Object>> segments = repository.findSegmentDemandForTrip(tripId, sessionId);

        assertEquals(3, segments.size(), "Route with 3 segments must return exactly 3 segment rows, never multiplied");
        assertEquals("TS-01-UB-1001, TS-01-UB-1002", segments.get(0).get("currentBus"));
        assertEquals(100, segments.get(0).get("currentCapacity"));
    }

    @Test
    @DisplayName("Requirement 4: Approval rejects a proposed bus that has become unavailable (e.g. MAINTENANCE)")
    void testReq4_ApprovalRejectsUnavailableBus() {
        long runId = 800L;

        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 801L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 08:00:00",
                        "expectedArrivalTime", "2026-11-08 09:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(801L)))
                .thenReturn(List.of(101L));

        // Stub bus query returning status = MAINTENANCE
        when(jdbcTemplate.queryForList(contains("FROM buses WHERE bus_id IN"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "busId", 101L,
                        "registrationNumber", "TS-01-UB-1001",
                        "capacity", 50,
                        "status", "MAINTENANCE",
                        "restDurationMinutes", 30
                )));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                repository.approveAllocationRun(runId, 1L));

        assertTrue(ex.getMessage().contains("MAINTENANCE"), "Exception must explain that bus is MAINTENANCE");
        assertTrue(ex.getMessage().contains("TS-01-UB-1001"), "Exception must name the conflicting bus");

        // Verify NO updates were executed
        verify(jdbcTemplate, never()).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), any(Object.class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trip_assignments"), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE allocation_runs SET status = 'APPROVED'"), any(), any());
    }

    @Test
    @DisplayName("Requirement 5: Approval rejects overlapping trip commitments on other routes")
    void testReq5_ApprovalRejectsOverlappingTripCommitments() {
        long runId = 810L;

        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 811L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 10:00:00",
                        "expectedArrivalTime", "2026-11-08 11:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(811L)))
                .thenReturn(List.of(101L));

        // Active commitment on Trip 99 (Route 12B) overlapping 10:30 to 12:00
        when(jdbcTemplate.queryForList(contains("WHERE ta.status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "busId", 101L,
                        "tripId", 99L,
                        "routeCode", "12B",
                        "departureTime", "2026-11-08 10:30:00",
                        "expectedArrivalTime", "2026-11-08 12:00:00"
                )));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                repository.approveAllocationRun(runId, 1L));

        assertTrue(ex.getMessage().contains("conflicts with existing active assignment"), "Exception must state commitment conflict");
        assertTrue(ex.getMessage().contains("Trip 99"), "Exception must name conflicting trip");

        // Verify rollback state
        verify(jdbcTemplate, never()).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), any(Object.class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trip_assignments"), any(), any(), any());
    }

    @Test
    @DisplayName("Requirement 6: Approval respects turnaround/rest constraints (30 min buffer)")
    void testReq6_ApprovalRespectsTurnaroundRestBuffer() {
        long runId = 820L;

        // Proposed trip: 10:00 to 11:00
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 821L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 10:00:00",
                        "expectedArrivalTime", "2026-11-08 11:00:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(821L)))
                .thenReturn(List.of(101L));

        // Active commitment: 11:15 to 12:15.
        // Even though 11:15 is after 11:00, 11:15 < 11:00 + 30 min (11:30), so turnaround rest is violated!
        when(jdbcTemplate.queryForList(contains("WHERE ta.status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "busId", 101L,
                        "tripId", 99L,
                        "routeCode", "7C",
                        "departureTime", "2026-11-08 11:15:00",
                        "expectedArrivalTime", "2026-11-08 12:15:00"
                )));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                repository.approveAllocationRun(runId, 1L));

        assertTrue(ex.getMessage().contains("turnaround rest buffer"), "Exception must mention turnaround rest buffer");
    }

    @Test
    @DisplayName("Requirement 7: A failed approval leaves previous assignments unchanged")
    void testReq7_FailedApprovalLeavesPreviousAssignmentsUnchanged() {
        long runId = 830L;

        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 831L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 10:00:00",
                        "expectedArrivalTime", "2026-11-08 11:00:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(831L)))
                .thenReturn(List.of(101L));

        // Bus lookup returns empty (bus was deleted)
        when(jdbcTemplate.queryForList(contains("FROM buses WHERE bus_id IN"), any(Object[].class)))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> repository.approveAllocationRun(runId, 1L));

        // Verify zero modifications occurred
        verify(jdbcTemplate, never()).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), any(Object.class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trip_assignments"), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE allocation_runs SET status = 'APPROVED'"), any(), any());
    }

    @Test
    @DisplayName("Requirement 8: Duplicate approval is idempotent and does not create duplicate active assignments")
    void testReq8_DuplicateApprovalIdempotency() {
        long runId = 840L;

        // Run status is already APPROVED
        when(jdbcTemplate.queryForList(contains("SELECT status FROM allocation_runs"), eq(runId)))
                .thenReturn(List.of(Map.of("status", "APPROVED")));

        // Calling approval again must safely do nothing
        assertDoesNotThrow(() -> repository.approveAllocationRun(runId, 1L));

        verify(jdbcTemplate, never()).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), any(Object.class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trip_assignments"), any(), any(), any());
    }

    @Test
    @DisplayName("Requirement 9: Concurrency safety - row locks buses FOR UPDATE in deterministic order to prevent overlapping assignment races")
    void testReq9_DeterministicRowLockingPreventsConcurrentOverlaps() {
        long runId = 850L;

        // Proposal has Bus 105 and Bus 102 (unsorted)
        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 851L,
                        "tripId", 1L,
                        "primaryBusId", 105L,
                        "departureTime", "2026-11-08 08:00:00",
                        "expectedArrivalTime", "2026-11-08 09:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(851L)))
                .thenReturn(List.of(105L, 102L));

        repository.approveAllocationRun(runId, 1L);

        // Verify that SELECT ... FROM buses FOR UPDATE is called with deterministic sorted order [102, 105]
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqlCaptor.capture(), argsCaptor.capture());

        boolean foundForUpdate = false;
        for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
            String sql = sqlCaptor.getAllValues().get(i);
            if (sql.contains("FROM buses WHERE bus_id IN") && sql.contains("FOR UPDATE")) {
                foundForUpdate = true;
                Object[] args = argsCaptor.getAllValues().get(i);
                assertEquals(102L, args[0], "First bus in lock list must be sorted (102)");
                assertEquals(105L, args[1], "Second bus in lock list must be sorted (105)");
            }
        }
        assertTrue(foundForUpdate, "Must use SELECT ... FOR UPDATE to serialize concurrent approval transactions");
    }

    @Test
    @DisplayName("Requirement 10: Existing single-bus behavior remains completely unchanged and valid")
    void testReq10_ExistingSingleBusBehaviorUnchanged() {
        long runId = 860L;

        when(jdbcTemplate.queryForList(contains("SELECT aa.allocation_assignment_id AS \"assignmentId\""), eq(runId)))
                .thenReturn(List.of(Map.of(
                        "assignmentId", 861L,
                        "tripId", 1L,
                        "primaryBusId", 101L,
                        "departureTime", "2026-11-08 08:00:00",
                        "expectedArrivalTime", "2026-11-08 09:30:00"
                )));
        when(jdbcTemplate.queryForList(contains("SELECT bus_id FROM allocation_assignment_buses"), eq(Long.class), eq(861L)))
                .thenReturn(List.of(101L));

        repository.approveAllocationRun(runId, 1L);

        verify(jdbcTemplate).update(contains("UPDATE trip_assignments SET status = 'REPLACED'"), eq(1L));
        verify(jdbcTemplate).update(contains("INSERT INTO trip_assignments"), eq(1L), eq(101L), eq(1L));
        verify(jdbcTemplate).update(contains("UPDATE allocation_runs SET status = 'APPROVED'"), eq(1L), eq(runId));
    }
}
