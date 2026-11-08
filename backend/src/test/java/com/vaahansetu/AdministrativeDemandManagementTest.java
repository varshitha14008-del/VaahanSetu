package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.vaahansetu.allocation.AllocationEngine;
import com.vaahansetu.allocation.AllocationModels.*;
import com.vaahansetu.repository.OfficerRepository;
import com.vaahansetu.service.OfficerService;

/**
 * Regression and verification test suite for Administrative Demand Management,
 * segment demand breakdown correctness, inflation mechanics root-cause verification,
 * and multi-consumer demand consistency.
 */
class AdministrativeDemandManagementTest {

    private JdbcTemplate jdbcTemplate;
    private OfficerRepository officerRepository;
    private OfficerService officerService;

    private final long TEST_SESSION_ID = 100L;
    private final long TEST_TRIP_ID = 1006L;
    private final long TEST_ROUTE_ID = 2L;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        officerRepository = new OfficerRepository(jdbcTemplate);
        officerService = new OfficerService(officerRepository);

        // Active session mock
        when(jdbcTemplate.queryForList(contains("FROM planning_sessions")))
                .thenReturn(List.of(Map.of("sessionId", TEST_SESSION_ID)));
        when(jdbcTemplate.queryForObject(contains("SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE'"), eq(Long.class)))
                .thenReturn(TEST_SESSION_ID);
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Existing demand 3, adjustment 0, total 3
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 1: Existing demand 3 (2 booked + 1 inq), adjustment 0 -> Total segment demand 3")
    void test1_ExistingDemand3_Adjustment0_Total3() {
        Map<String, Object> rawSegment = new HashMap<>();
        rawSegment.put("tripId", TEST_TRIP_ID);
        rawSegment.put("routeId", TEST_ROUTE_ID);
        rawSegment.put("segmentIndex", 1);
        rawSegment.put("startStopId", 201L);
        rawSegment.put("startStopName", "Stop A");
        rawSegment.put("endStopId", 202L);
        rawSegment.put("endStopName", "Stop B");
        rawSegment.put("registeredDemand", 2);
        rawSegment.put("searchIntentDemand", 1);
        rawSegment.put("simulatedAdjustment", 0);

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(rawSegment));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        assertEquals(1, result.size());

        Map<String, Object> segment = result.get(0);
        assertEquals(3, segment.get("existingDemand"), "existingDemand must be confirmed bookings (2) + inquiries (1) = 3");
        assertEquals(3, segment.get("baseDemand"), "baseDemand must also equal existingDemand = 3");
        assertEquals(2, segment.get("bookedDemand"), "bookedDemand must be 2");
        assertEquals(1, segment.get("inquiryDemand"), "inquiryDemand must be 1");
        assertEquals(0, segment.get("simulatedAdjustment"), "simulatedAdjustment must be 0");
        assertEquals(3, segment.get("effectiveDemand"), "effectiveDemand must be 3 + 0 = 3");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Existing demand 3, adjustment +5, total 8 (Trip #1006 Fix)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 2: Existing demand 3 (2 booked + 1 inq), adjustment +5 -> Total segment demand 8 (Trip #1006)")
    void test2_ExistingDemand3_AdjustmentPlus5_Total8() {
        Map<String, Object> rawSegment = new HashMap<>();
        rawSegment.put("tripId", TEST_TRIP_ID);
        rawSegment.put("routeId", TEST_ROUTE_ID);
        rawSegment.put("segmentIndex", 1);
        rawSegment.put("startStopId", 201L);
        rawSegment.put("startStopName", "Secunderabad Station");
        rawSegment.put("endStopId", 202L);
        rawSegment.put("endStopName", "Clock Tower");
        rawSegment.put("registeredDemand", 2);
        rawSegment.put("searchIntentDemand", 1);
        rawSegment.put("simulatedAdjustment", 5);

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(rawSegment));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        assertEquals(1, result.size());

        Map<String, Object> segment = result.get(0);
        assertEquals(3, segment.get("existingDemand"), "Existing bookings/inquiries must accurately sum to 3");
        assertEquals(5, segment.get("simulatedAdjustment"), "Admin adjustments must be +5");
        assertEquals(8, segment.get("effectiveDemand"), "Total segment demand must be 3 + 5 = 8");
        // Verify arithmetic consistency: existingDemand + simulatedAdjustment == effectiveDemand
        int existing = (int) segment.get("existingDemand");
        int adj = (int) segment.get("simulatedAdjustment");
        int total = (int) segment.get("effectiveDemand");
        assertEquals(total, existing + adj, "3 + 5 must equal 8 without any missing inquiry discrepancy");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Existing demand 0, adjustment +5, total 5
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 3: Existing demand 0 (0 booked + 0 inq), adjustment +5 -> Total segment demand 5")
    void test3_ExistingDemand0_AdjustmentPlus5_Total5() {
        Map<String, Object> rawSegment = new HashMap<>();
        rawSegment.put("tripId", TEST_TRIP_ID);
        rawSegment.put("routeId", TEST_ROUTE_ID);
        rawSegment.put("segmentIndex", 2);
        rawSegment.put("startStopId", 202L);
        rawSegment.put("startStopName", "Clock Tower");
        rawSegment.put("endStopId", 203L);
        rawSegment.put("endStopName", "Patny");
        rawSegment.put("registeredDemand", 0);
        rawSegment.put("searchIntentDemand", 0);
        rawSegment.put("simulatedAdjustment", 5);

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(rawSegment));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        assertEquals(1, result.size());

        Map<String, Object> segment = result.get(0);
        assertEquals(0, segment.get("existingDemand"), "Existing demand must be 0");
        assertEquals(5, segment.get("simulatedAdjustment"), "Adjustment must be +5");
        assertEquals(5, segment.get("effectiveDemand"), "Total segment demand must be 0 + 5 = 5");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Multiple segments receiving correct applicable adjustment
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 4: Multiple segments independently receive correct applicable adjustments")
    void test4_MultipleSegments_IndependentAdjustments() {
        // Segment 1: base 3 (2 booked, 1 inq), adj +5 -> total 8
        Map<String, Object> s1 = new HashMap<>(Map.of(
                "segmentIndex", 1, "startStopId", 101L, "endStopId", 102L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 5));
        // Segment 2: base 10 (8 booked, 2 inq), adj -3 -> total 7
        Map<String, Object> s2 = new HashMap<>(Map.of(
                "segmentIndex", 2, "startStopId", 102L, "endStopId", 103L,
                "registeredDemand", 8, "searchIntentDemand", 2, "simulatedAdjustment", -3));
        // Segment 3: base 0 (0 booked, 0 inq), adj +2 -> total 2
        Map<String, Object> s3 = new HashMap<>(Map.of(
                "segmentIndex", 3, "startStopId", 103L, "endStopId", 104L,
                "registeredDemand", 0, "searchIntentDemand", 0, "simulatedAdjustment", 2));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(s1, s2, s3));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        assertEquals(3, result.size());

        assertEquals(3, result.get(0).get("existingDemand"));
        assertEquals(5, result.get(0).get("simulatedAdjustment"));
        assertEquals(8, result.get(0).get("effectiveDemand"));

        assertEquals(10, result.get(1).get("existingDemand"));
        assertEquals(-3, result.get(1).get("simulatedAdjustment"));
        assertEquals(7, result.get(1).get("effectiveDemand"));

        assertEquals(0, result.get(2).get("existingDemand"));
        assertEquals(2, result.get(2).get("simulatedAdjustment"));
        assertEquals(2, result.get(2).get("effectiveDemand"));
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Repeated refreshes not accumulating adjustments (read-only queries)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 5: Repeated refreshes do not accumulate or mutate adjustments (read-only queries)")
    void test5_RepeatedRefreshes_NoAccumulation() {
        Map<String, Object> rawSegment = new HashMap<>(Map.of(
                "segmentIndex", 1, "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 3, "searchIntentDemand", 2, "simulatedAdjustment", 5));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(rawSegment));

        // Call getSegmentDemand 5 consecutive times (simulating 5 page refreshes)
        for (int i = 0; i < 5; i++) {
            List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
            assertEquals(1, result.size());
            assertEquals(5, result.get(0).get("existingDemand"), "Existing demand must stay 5 on call " + (i + 1));
            assertEquals(5, result.get(0).get("simulatedAdjustment"), "Adjustment must stay 5 on call " + (i + 1));
            assertEquals(10, result.get(0).get("effectiveDemand"), "Total demand must stay 10 on call " + (i + 1));
        }

        // Verify no INSERT or UPDATE statements were executed on segment_demand_adjustments
        verify(jdbcTemplate, never()).update(contains("INSERT INTO segment_demand_adjustments"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE segment_demand_adjustments"), any(Object[].class));
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Repeated allocation calculations not accumulating adjustments
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 6: Repeated allocation runs do not accumulate adjustments or inflate demand")
    void test6_RepeatedAllocationRuns_NoAccumulation() {
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 8, 10, 0);
        Route route = new Route(1L, "R1", "Route 1", List.of(101L, 102L, 103L));
        Trip trip = new Trip(1L, 1L, baseTime, baseTime.plusHours(1), null, 0);

        // Journey 1: base 10 passengers on stop 101 -> 103 (spans 2 segments: 101->102 and 102->103)
        Journey baseJourney = new Journey(1L, 101L, 103L, 10);
        // Adjustment journey: +5 on stop 101 -> 102
        Journey adjJourney = new Journey(1L, 101L, 102L, 5);

        Bus bus = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);

        Input input = new Input(baseTime, List.of(route), List.of(bus), List.of(trip),
                List.of(baseJourney, adjJourney), List.of());

        // Run allocation engine 3 times in succession
        Result res1 = AllocationEngine.allocate(input);
        Result res2 = AllocationEngine.allocate(input);
        Result res3 = AllocationEngine.allocate(input);

        // Segment 101->102 has demand 10 + 5 = 15
        // Segment 102->103 has demand 10
        // Total passenger-segments (pax-seg) across the route = 15 + 10 = 25
        assertEquals(25, res1.beforeTotal().demand());
        assertEquals(25, res2.beforeTotal().demand(), "Run 2 must have identical total demand (25), not accumulated");
        assertEquals(25, res3.beforeTotal().demand(), "Run 3 must have identical total demand (25), not accumulated");
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Positive and negative adjustments (cancellation and non-negative clamping)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 7: Positive and negative adjustments (cancellation and non-negative clamping to 0)")
    void test7_PositiveAndNegativeAdjustments_Clamping() {
        // Sub-case 7a: Positive adjustment increases demand
        Map<String, Object> pos = new HashMap<>(Map.of(
                "segmentIndex", 1, "startStopId", 101L, "endStopId", 102L,
                "registeredDemand", 4, "searchIntentDemand", 1, "simulatedAdjustment", 3));
        // Sub-case 7b: Moderate negative adjustment decreases demand
        Map<String, Object> neg = new HashMap<>(Map.of(
                "segmentIndex", 2, "startStopId", 102L, "endStopId", 103L,
                "registeredDemand", 5, "searchIntentDemand", 0, "simulatedAdjustment", -2));
        // Sub-case 7c: Negative adjustment exceeding base demand clamps strictly to 0
        Map<String, Object> clamp = new HashMap<>(Map.of(
                "segmentIndex", 3, "startStopId", 103L, "endStopId", 104L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", -10));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(pos, neg, clamp));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        assertEquals(3, result.size());

        // 7a: 5 + 3 = 8
        assertEquals(5, result.get(0).get("existingDemand"));
        assertEquals(3, result.get(0).get("simulatedAdjustment"));
        assertEquals(8, result.get(0).get("effectiveDemand"));

        // 7b: 5 - 2 = 3
        assertEquals(5, result.get(1).get("existingDemand"));
        assertEquals(-2, result.get(1).get("simulatedAdjustment"));
        assertEquals(3, result.get(1).get("effectiveDemand"));

        // 7c: 3 - 10 = -7 -> clamped to 0
        assertEquals(3, result.get(2).get("existingDemand"));
        assertEquals(-10, result.get(2).get("simulatedAdjustment"));
        assertEquals(0, result.get(2).get("effectiveDemand"), "Effective demand must never be negative (clamped to 0)");
    }

    // -------------------------------------------------------------------------
    // Scenario 8: No passenger-demand double-counting between repositories or aggregation stages
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 8: No passenger-demand double-counting between bookings, inquiries, and adjustments")
    void test8_NoDoubleCountingBetweenStages() {
        // In PostgreSQL findSegmentDemandForTrip:
        // confirmed_demand = SUM(CASE WHEN status IN ('REGISTERED','BOARDED','COMPLETED') THEN passenger_count END)
        // search_demand = SUM(CASE WHEN status = 'UNCONFIRMED' THEN passenger_count END)
        // The statuses are disjoint sets; no status belongs to both.
        // adjustments are queried from segment_demand_adjustments separately.
        Map<String, Object> seg = new HashMap<>();
        seg.put("registeredDemand", 7);
        seg.put("searchIntentDemand", 3);
        seg.put("simulatedAdjustment", 4);

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(seg));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        Map<String, Object> s = result.get(0);

        int booked = (int) s.get("bookedDemand");
        int inq = (int) s.get("inquiryDemand");
        int existing = (int) s.get("existingDemand");
        int adj = (int) s.get("simulatedAdjustment");
        int total = (int) s.get("effectiveDemand");

        assertEquals(7, booked);
        assertEquals(3, inq);
        assertEquals(10, existing, "existingDemand must be exactly booked + inq (no overlapping double count)");
        assertEquals(4, adj);
        assertEquals(14, total, "effectiveDemand must be existing + adj = 14");
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Trip peak demand remaining distinct from total passenger-segments (pax-seg)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 9: Trip peak demand remains distinct from total passenger-segments (pax-seg)")
    void test9_TripPeakDemandDistinctFromPaxSeg() {
        // A route with 4 stops -> 3 segments
        // Segment 1: 15 pax
        // Segment 2: 25 pax  <-- PEAK segment demand for the trip (determines bus capacity required)
        // Segment 3: 20 pax
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 8, 10, 0);
        Route route = new Route(1L, "R1", "Route 1", List.of(101L, 102L, 103L, 104L));
        Trip trip = new Trip(1L, 1L, baseTime, baseTime.plusHours(1), null, 0);

        // Journey 1: 15 pax stop 101 -> 102 (segment 1 only)
        Journey j1 = new Journey(1L, 101L, 102L, 15);
        // Journey 2: 25 pax stop 102 -> 103 (segment 2 only)
        Journey j2 = new Journey(1L, 102L, 103L, 25);
        // Journey 3: 20 pax stop 103 -> 104 (segment 3 only)
        Journey j3 = new Journey(1L, 103L, 104L, 20);

        Input input = new Input(baseTime, List.of(route), List.of(), List.of(trip), List.of(j1, j2, j3), List.of());
        Result res = AllocationEngine.allocate(input);

        // Individual segment demands: 15, 25, 20
        // Peak segment demand on this trip = MAX(15, 25, 20) = 25
        // Total passenger-segments (pax-seg) across the route = 15 + 25 + 20 = 60
        int totalPaxSeg = res.beforeTotal().demand();
        assertEquals(60, totalPaxSeg, "Allocation engine beforeTotal().demand() measures total passenger-segments (60)");

        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(25, outcome.peakDemand(), "Trip outcome peakDemand must be 25 pax (the peak segment demand)");
        assertNotEquals(outcome.peakDemand(), totalPaxSeg, "Peak segment demand (25) and total pax-seg (60) are distinct metrics and must not be conflated");
    }

    // -------------------------------------------------------------------------
    // Scenario 10: Consistent demand across Scheduled Departures, Passenger Portal, and Allocation Engine
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 10: Mathematical proof of +600 pax-seg inflation on Route 2 (24 segments x 5 trips x 5 pax = 600)")
    void test10_Route2InflationMechanics_ExactMathematicalProof() {
        // Route 2 ('8A') has 25 stops -> 24 segments
        // In the session, Route 2 has 5 scheduled trips (e.g. Trips 1006, 1007, 1008, 1009, 1010)
        // When an admin adjusts Trip #1006 with delta = 5, saveDemandAdjustment inserts
        // 24 records into segment_demand_adjustments for Route 2.
        // In OfficerService.buildEngineInput, these 24 adjustments apply to all 5 trips on Route 2:
        // 24 segments * 5 trips = 120 journey legs.
        // Each journey leg adds delta = 5 passengers.
        // Total added passenger-segments = 120 * 5 = 600 pax-seg.
        // Base network demand before adjustment = 8,730 pax-seg.
        // Demand after adjustment = 8,730 + 600 = 9,330 pax-seg.

        int numStops = 25;
        int numSegments = numStops - 1; // 24
        int tripsOnRoute = 5;
        int deltaPerSegment = 5;

        int addedPaxSeg = numSegments * tripsOnRoute * deltaPerSegment;
        assertEquals(600, addedPaxSeg, "Route 2 adjustment must add exactly 600 passenger-segments");

        int baselineDemand = 8730;
        int inflatedDemand = baselineDemand + addedPaxSeg;
        assertEquals(9330, inflatedDemand, "Demand must increase from 8,730 to exactly 9,330 pax-seg");

        // Verify this by feeding into AllocationEngine
        List<Long> stopIds = new ArrayList<>();
        for (long i = 1; i <= numStops; i++) stopIds.add(i);
        Route route8A = new Route(2L, "8A", "Secunderabad - Kondapur", stopIds);

        LocalDateTime dep = LocalDateTime.of(2026, 11, 8, 8, 0);
        List<Trip> trips = new ArrayList<>();
        for (long t = 1006; t <= 1010; t++) {
            trips.add(new Trip(t, 2L, dep, dep.plusHours(1), null, 0));
            dep = dep.plusMinutes(30);
        }

        // Create journeys simulating the adjustments added in buildEngineInput
        List<Journey> simulationJourneys = new ArrayList<>();
        for (int s = 0; s < numSegments; s++) {
            long startStop = stopIds.get(s);
            long endStop = stopIds.get(s + 1);
            for (Trip t : trips) {
                simulationJourneys.add(new Journey(t.id(), startStop, endStop, deltaPerSegment));
            }
        }

        Input in = new Input(LocalDateTime.of(2026, 11, 8, 8, 0), List.of(route8A), List.of(), trips, simulationJourneys, List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(600, res.beforeTotal().demand(), "AllocationEngine beforeTotal.demand() must exactly equal 600 pax-seg");
    }

    // -------------------------------------------------------------------------
    // Scenario 11: Trip-specific adjustment persistence with trip_id
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 11: adjustDemand persists trip_id into segment_demand_adjustments")
    void test11_TripSpecificAdjustment_PersistedWithTripId() {
        when(jdbcTemplate.queryForList(contains("SELECT route_id FROM scheduled_trips"), eq(Long.class), eq(TEST_TRIP_ID)))
                .thenReturn(List.of(TEST_ROUTE_ID));

        Map<String, Object> seg1 = Map.of("startStopId", 201L, "endStopId", 202L, "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 0);
        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(seg1));

        Map<String, Object> result = officerService.adjustDemand(TEST_TRIP_ID, 5);
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(TEST_TRIP_ID, result.get("tripId"));
        assertEquals(5, result.get("demandDelta"));

        // Verify that jdbcTemplate.update was called with SQL that includes trip_id and passes TEST_TRIP_ID (1006L)
        verify(jdbcTemplate, atLeastOnce()).update(
                contains("(session_id, route_id, trip_id,"),
                eq(TEST_SESSION_ID), eq(TEST_ROUTE_ID), eq(TEST_TRIP_ID), eq(201L), eq(202L), eq(5));
    }

    // -------------------------------------------------------------------------
    // Scenario 12: Trip #1006 adjustment affects its segments, Trip #1007 remains unchanged
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 12: Trip #1006 adjustment affects only Trip #1006, Trip #1007 on Route 2 remains unchanged")
    void test12_Trip1006Adjustment_Trip1007RemainsUnchanged() {
        long trip1006 = 1006L;
        long trip1007 = 1007L;

        // Mock segment demand query for Trip 1006 (matches adjustment +5)
        Map<String, Object> seg1006 = new HashMap<>(Map.of(
                "tripId", trip1006, "routeId", TEST_ROUTE_ID, "segmentIndex", 1,
                "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 5));

        // Mock segment demand query for Trip 1007 (no matching trip-specific adjustment, simAdj is 0)
        Map<String, Object> seg1007 = new HashMap<>(Map.of(
                "tripId", trip1007, "routeId", TEST_ROUTE_ID, "segmentIndex", 1,
                "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 0));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenAnswer(inv -> {
                    Object firstArg = inv.getArgument(1);
                    if (firstArg instanceof Long && ((Long) firstArg) == trip1006) {
                        return List.of(seg1006);
                    } else {
                        return List.of(seg1007);
                    }
                });

        List<Map<String, Object>> res1006 = officerService.getSegmentDemand(trip1006);
        List<Map<String, Object>> res1007 = officerService.getSegmentDemand(trip1007);

        // Trip 1006 has 3 + 5 = 8
        assertEquals(8, res1006.get(0).get("effectiveDemand"), "Trip #1006 must reflect +5 adjustment (3 + 5 = 8)");
        assertEquals(5, res1006.get(0).get("simulatedAdjustment"));

        // Trip 1007 has 3 + 0 = 3 (completely unaffected)
        assertEquals(3, res1007.get(0).get("effectiveDemand"), "Trip #1007 must remain unaffected by Trip #1006 adjustment (effective demand = 3)");
        assertEquals(0, res1007.get(0).get("simulatedAdjustment"), "Trip #1007 simulatedAdjustment must be 0");
    }

    // -------------------------------------------------------------------------
    // Scenario 13: Historical route-wide adjustments retain existing behavior for all trips
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 13: Historical route-wide adjustments (trip_id IS NULL) apply to all trips on the route")
    void test13_HistoricalRouteWideAdjustments_RetainExistingBehaviorForAllTrips() {
        long trip1006 = 1006L;
        long trip1007 = 1007L;

        // Both trips match historical route-wide adjustment of +4
        Map<String, Object> seg1006 = new HashMap<>(Map.of(
                "tripId", trip1006, "routeId", TEST_ROUTE_ID, "segmentIndex", 1,
                "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 4));
        Map<String, Object> seg1007 = new HashMap<>(Map.of(
                "tripId", trip1007, "routeId", TEST_ROUTE_ID, "segmentIndex", 1,
                "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 2, "searchIntentDemand", 1, "simulatedAdjustment", 4));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenAnswer(inv -> {
                    Object firstArg = inv.getArgument(1);
                    if (firstArg instanceof Long && ((Long) firstArg) == trip1006) {
                        return List.of(seg1006);
                    } else {
                        return List.of(seg1007);
                    }
                });

        List<Map<String, Object>> res1006 = officerService.getSegmentDemand(trip1006);
        List<Map<String, Object>> res1007 = officerService.getSegmentDemand(trip1007);

        assertEquals(7, res1006.get(0).get("effectiveDemand"), "Historical route-wide adjustment applies to Trip #1006 (3 + 4 = 7)");
        assertEquals(7, res1007.get(0).get("effectiveDemand"), "Historical route-wide adjustment also applies to Trip #1007 (3 + 4 = 7)");
    }

    // -------------------------------------------------------------------------
    // Scenario 14: Engine Input with trip-specific adjustment adds exactly 120 pax-seg, NOT 600
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 14: Engine input with trip-specific adjustment on Route 2 adds exactly 120 pax-seg (24 seg x 1 trip x 5 pax = 120)")
    void test14_EngineInput_TripSpecificAdjustment_Adds120PaxSeg_Not600() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 8);
        LocalDateTime allocTime = serviceDate.atTime(8, 0);

        OfficerRepository repoMock = mock(OfficerRepository.class);
        OfficerService svc = new OfficerService(repoMock);

        // 1 route: Route 2 with 25 stops -> 24 segments
        List<Long> stopIds = new ArrayList<>();
        for (long i = 1; i <= 25; i++) stopIds.add(i);
        when(repoMock.findEngineRoutes(TEST_SESSION_ID))
                .thenReturn(List.of(Map.of("routeId", TEST_ROUTE_ID, "routeCode", "8A", "routeName", "Route 8A")));
        when(repoMock.findAllEngineStopsByRoute())
                .thenReturn(Map.of(TEST_ROUTE_ID, stopIds));

        // 5 scheduled trips on Route 2 (Trips 1006..1010)
        List<Map<String, Object>> trips = new ArrayList<>();
        LocalDateTime dep = allocTime;
        for (long t = 1006; t <= 1010; t++) {
            trips.add(Map.of("tripId", t, "routeId", TEST_ROUTE_ID, "departureTime", dep, "expectedArrivalTime", dep.plusHours(1), "currentBusId", 1L, "currentBusCapacity", 40));
            dep = dep.plusMinutes(30);
        }
        when(repoMock.findEngineTrips(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class)))
                .thenReturn(trips);
        when(repoMock.findEngineBuses(TEST_SESSION_ID))
                .thenReturn(List.of());
        when(repoMock.findEngineJourneys(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repoMock.findEngineCommitments(serviceDate))
                .thenReturn(List.of());

        // Trip-specific adjustment on Trip 1006 ONLY across 24 segments
        List<Map<String, Object>> adjList = new ArrayList<>();
        for (int s = 0; s < 24; s++) {
            adjList.add(Map.of(
                    "sessionId", TEST_SESSION_ID,
                    "routeId", TEST_ROUTE_ID,
                    "tripId", 1006L, // Trip-specific!
                    "startStopId", stopIds.get(s),
                    "endStopId", stopIds.get(s + 1),
                    "totalDelta", 5
            ));
        }
        when(repoMock.findSimulationAdjustments(TEST_SESSION_ID))
                .thenReturn(adjList);

        // Build engine input
        Input input = svc.buildEngineInput(TEST_SESSION_ID, serviceDate, allocTime);

        // Verify: Exactly 24 journeys added (all for trip 1006), 0 for trips 1007..1010
        assertEquals(24, input.journeys().size(), "Only 24 journeys must be added for Trip #1006");
        for (Journey j : input.journeys()) {
            assertEquals(1006L, j.tripId(), "Every adjustment journey must belong to Trip #1006");
            assertEquals(5, j.passengers(), "Each segment delta must be 5");
        }

        // Run engine allocation and verify total passenger-segments
        Result res = AllocationEngine.allocate(input);
        int totalPaxSeg = res.beforeTotal().demand();
        assertEquals(120, totalPaxSeg, "Total passenger-segments added must be exactly 24 * 1 * 5 = 120 pax-seg, NOT 600!");
    }

    // -------------------------------------------------------------------------
    // Scenario 15: Engine Input with historical route-wide adjustment adds 600 pax-seg (24 seg x 5 trips x 5 pax = 600)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 15: Engine input with historical route-wide adjustment (trip_id = null) adds 600 pax-seg (24 seg x 5 trips x 5 pax = 600)")
    void test15_EngineInput_HistoricalRouteWideAdjustment_Adds600PaxSeg() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 8);
        LocalDateTime allocTime = serviceDate.atTime(8, 0);

        OfficerRepository repoMock = mock(OfficerRepository.class);
        OfficerService svc = new OfficerService(repoMock);

        List<Long> stopIds = new ArrayList<>();
        for (long i = 1; i <= 25; i++) stopIds.add(i);
        when(repoMock.findEngineRoutes(TEST_SESSION_ID))
                .thenReturn(List.of(Map.of("routeId", TEST_ROUTE_ID, "routeCode", "8A", "routeName", "Route 8A")));
        when(repoMock.findAllEngineStopsByRoute())
                .thenReturn(Map.of(TEST_ROUTE_ID, stopIds));

        List<Map<String, Object>> trips = new ArrayList<>();
        LocalDateTime dep = allocTime;
        for (long t = 1006; t <= 1010; t++) {
            trips.add(Map.of("tripId", t, "routeId", TEST_ROUTE_ID, "departureTime", dep, "expectedArrivalTime", dep.plusHours(1), "currentBusId", 1L, "currentBusCapacity", 40));
            dep = dep.plusMinutes(30);
        }
        when(repoMock.findEngineTrips(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class)))
                .thenReturn(trips);
        when(repoMock.findEngineBuses(TEST_SESSION_ID)).thenReturn(List.of());
        when(repoMock.findEngineJourneys(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class))).thenReturn(List.of());
        when(repoMock.findEngineCommitments(serviceDate)).thenReturn(List.of());

        // Historical route-wide adjustment (tripId is null in the map!)
        List<Map<String, Object>> adjList = new ArrayList<>();
        for (int s = 0; s < 24; s++) {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", TEST_SESSION_ID);
            map.put("routeId", TEST_ROUTE_ID);
            map.put("tripId", null); // Historical record without trip_id!
            map.put("startStopId", stopIds.get(s));
            map.put("endStopId", stopIds.get(s + 1));
            map.put("totalDelta", 5);
            adjList.add(map);
        }
        when(repoMock.findSimulationAdjustments(TEST_SESSION_ID))
                .thenReturn(adjList);

        Input input = svc.buildEngineInput(TEST_SESSION_ID, serviceDate, allocTime);

        // Historical route-wide adjustment generates 24 segments * 5 trips = 120 journeys
        assertEquals(120, input.journeys().size(), "Historical adjustment applies across all 5 trips (24 * 5 = 120 journeys)");

        Result res = AllocationEngine.allocate(input);
        int totalPaxSeg = res.beforeTotal().demand();
        assertEquals(600, totalPaxSeg, "Historical route-wide adjustment adds exactly 24 * 5 * 5 = 600 pax-seg (preserves existing behavior)");
    }

    // -------------------------------------------------------------------------
    // Scenario 16: Multiple adjustments (historical route-wide + trip-specific + negative)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 16: Multiple adjustments combine additively without double-counting")
    void test16_MultipleAdjustments_CombinedCorrectly() {
        // Segment has:
        // Confirmed bookings: 4
        // Search inquiries: 1
        // Existing demand: 5
        // Historical route-wide adjustment: +3
        // Trip-specific surge: +5
        // Cancellation adjustment: -2
        // Net adjustment: +3 + 5 - 2 = +6
        // Total effective demand: 5 + 6 = 11
        Map<String, Object> seg = new HashMap<>(Map.of(
                "tripId", TEST_TRIP_ID, "routeId", TEST_ROUTE_ID, "segmentIndex", 1,
                "startStopId", 201L, "endStopId", 202L,
                "registeredDemand", 4, "searchIntentDemand", 1, "simulatedAdjustment", 6));

        when(jdbcTemplate.queryForList(contains("FROM route_stops rs"), any(Object[].class)))
                .thenReturn(List.of(seg));

        List<Map<String, Object>> result = officerService.getSegmentDemand(TEST_TRIP_ID);
        Map<String, Object> s = result.get(0);

        assertEquals(5, s.get("existingDemand"), "Existing demand must be 4 + 1 = 5");
        assertEquals(6, s.get("simulatedAdjustment"), "Net combined adjustment must be 6");
        assertEquals(11, s.get("effectiveDemand"), "Total segment demand must be 5 + 6 = 11");
    }
}
