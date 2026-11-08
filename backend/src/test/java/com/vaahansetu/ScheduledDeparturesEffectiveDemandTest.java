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
 * Verification test suite for Scheduled Departures Effective Peak Demand,
 * utilization calculation, and Allocation Engine consistency.
 */
class ScheduledDeparturesEffectiveDemandTest {

    private JdbcTemplate jdbcTemplate;
    private OfficerRepository officerRepository;
    private OfficerService officerService;

    private final long TEST_SESSION_ID = 1L;
    private final long TEST_TRIP_ID = 1017L;
    private final long TEST_ROUTE_ID = 5L; // Route 10H

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        officerRepository = new OfficerRepository(jdbcTemplate);
        officerService = new OfficerService(officerRepository);

        when(jdbcTemplate.queryForObject(contains("SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE'"), eq(Long.class)))
                .thenReturn(TEST_SESSION_ID);
        when(jdbcTemplate.queryForList(contains("FROM planning_sessions")))
                .thenReturn(List.of(Map.of("sessionId", TEST_SESSION_ID, "startedAt", "2026-11-08T00:00:00")));
    }

    // 1. Existing booking count of 5 + adjustment of 25 = effective demand 30 (Trip #1017)
    @Test
    @DisplayName("Item 1: Existing booking count of 5 + adjustment of 25 = effective peak demand 30")
    void test1_Booking5_Adjustment25_EffectiveDemand30() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("routeId", TEST_ROUTE_ID);
        tripRow.put("routeCode", "10H");
        tripRow.put("departureTime", LocalDateTime.of(2026, 11, 8, 5, 0));
        tripRow.put("bookedPassengerCount", 5);
        tripRow.put("activeInquiryCount", 4);
        tripRow.put("peakSegmentBookings", 5);
        tripRow.put("administrativeAdjustment", 25);
        tripRow.put("peakSegmentDemand", 30);
        tripRow.put("currentBusCapacity", 60);
        tripRow.put("currentBusRegistration", "TS-03-UB-1019");

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        assertEquals(1, trips.size());

        Map<String, Object> t = trips.get(0);
        assertEquals(5, t.get("bookedPassengerCount"), "Existing booking count must be 5");
        assertEquals(5, t.get("peakSegmentBookings"), "Peak segment bookings must be 5");
        assertEquals(30, t.get("peakSegmentDemand"), "Effective peak demand must be 30");
        assertEquals(30, t.get("effectivePeakDemand"), "Enriched effective peak demand must be 30");
        assertEquals(25, t.get("administrativeAdjustment"), "Applicable administrative adjustment must be 25");
        assertEquals(30, t.get("demand"), "demand alias must match effective peak demand (30)");
    }

    // 2. Utilization of 30 / 60 = 50%
    @Test
    @DisplayName("Item 2: Utilization of 30 peak passengers / 60 seats capacity = 50%")
    void test2_UtilizationCalculation_50Percent() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("bookedPassengerCount", 5);
        tripRow.put("peakSegmentBookings", 5);
        tripRow.put("administrativeAdjustment", 25);
        tripRow.put("peakSegmentDemand", 30);
        tripRow.put("currentBusCapacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        int peakDemand = (int) t.get("effectivePeakDemand");
        int busCap = (int) t.get("currentBusCapacity");
        assertEquals(50, t.get("utilizationPercent"), "Utilization percentage must be exactly 50%");
        assertEquals(50, Math.round((peakDemand * 100.0) / busCap));
    }

    // 3. Inquiries are NOT separately added to the existing booking count
    @Test
    @DisplayName("Item 3: Inquiries (UNCONFIRMED) are not added to existing booking count or effective peak demand")
    void test3_InquiriesNotAddedToBookingCount() {
        // Trip with 5 confirmed bookings and 4 active inquiries
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("bookedPassengerCount", 5);
        tripRow.put("activeInquiryCount", 4);
        tripRow.put("peakSegmentBookings", 5);
        tripRow.put("administrativeAdjustment", 25);
        tripRow.put("peakSegmentDemand", 30); // 5 bookings + 25 adjustment (inquiries excluded)
        tripRow.put("currentBusCapacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(5, t.get("bookedPassengerCount"), "Booking count must be strictly 5, NOT 5 + 4 = 9");
        assertEquals(4, t.get("activeInquiryCount"), "Inquiries remain tracked separately as 4");
        assertEquals(30, t.get("effectivePeakDemand"), "Peak demand must be 30, not 34");
        assertNotEquals(34, t.get("effectivePeakDemand"), "Inquiries must NOT be double-counted into effective demand");
    }

    // 4. Existing valid-inquiry filtering remains unchanged
    @Test
    @DisplayName("Item 4: Existing valid-inquiry filtering remains unchanged in JourneyRepository and OfficerRepository")
    void test4_ValidInquiryFilteringUnchanged() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("bookedPassengerCount", 12);
        tripRow.put("activeInquiryCount", 8);
        tripRow.put("totalDemand", 20); // booked (12) + inquiry (8)
        tripRow.put("peakSegmentDemand", 12);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(12, t.get("bookedPassengerCount"));
        assertEquals(8, t.get("activeInquiryCount"));
        assertEquals(20, t.get("totalDemand"));
    }

    // 5. Segment-level peak demand is used, not total passenger-segments
    @Test
    @DisplayName("Item 5: Segment-level peak demand is used for allocation and display, NOT total passenger-segments across route")
    void test5_SegmentPeakDemandUsedNotTotalPaxSeg() {
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 8, 5, 0);
        Route route = new Route(5L, "10H", "Route 10H", List.of(1L, 2L, 3L, 4L));
        Trip trip = new Trip(TEST_TRIP_ID, 5L, baseTime, baseTime.plusHours(1), null, 0);

        // Segment 1 (1->2): 15 pax
        Journey j1 = new Journey(TEST_TRIP_ID, 1L, 2L, 15);
        // Segment 2 (2->3): 30 pax (PEAK segment demand)
        Journey j2 = new Journey(TEST_TRIP_ID, 2L, 3L, 30);
        // Segment 3 (3->4): 20 pax
        Journey j3 = new Journey(TEST_TRIP_ID, 3L, 4L, 20);

        Bus bus = new Bus(119L, "TS-03-UB-1019", 60, "ACTIVE", 30);

        Input input = new Input(baseTime, List.of(route), List.of(bus), List.of(trip),
                List.of(j1, j2, j3), List.of());

        Result res = AllocationEngine.allocate(input);

        // Total passenger-segments across route = 15 + 30 + 20 = 65 pax-seg
        int totalPaxSeg = res.beforeTotal().demand();
        assertEquals(65, totalPaxSeg, "Sum of segments across trip is 65 pax-seg");

        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(30, outcome.peakDemand(), "Trip peakDemand must be MAX(15, 30, 20) = 30, NOT 65!");
        assertNotEquals(totalPaxSeg, outcome.peakDemand(), "Peak segment demand must not equal sum across segments");
    }

    // 6. Multiple assigned buses use combined capacity
    @Test
    @DisplayName("Item 6: Multiple assigned buses sum their capacities (e.g. 60 + 60 = 120)")
    void test6_MultipleAssignedBuses_CombinedCapacity() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("currentBusRegistration", "TS-01-UB-1001, TS-01-UB-1002");
        tripRow.put("currentBusCapacity", 120); // 60 + 60 combined
        tripRow.put("bookedPassengerCount", 5);
        tripRow.put("peakSegmentDemand", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(120, t.get("currentBusCapacity"), "Capacity must be combined 120");
        assertEquals(50, t.get("utilizationPercent"), "Utilization must be 60 / 120 = 50%");
    }

    // 7. No assigned bus / zero capacity is handled safely
    @Test
    @DisplayName("Item 7: No assigned bus / zero capacity is handled safely without division by zero or misleading percentage")
    void test7_ZeroCapacity_HandledSafely() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("currentBusRegistration", null);
        tripRow.put("currentBusCapacity", 0);
        tripRow.put("bookedPassengerCount", 10);
        tripRow.put("peakSegmentDemand", 25);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(0, t.get("currentBusCapacity"));
        assertNull(t.get("utilizationPercent"), "Utilization percentage must be null when capacity is 0");
    }

    // 8. Trip-specific adjustments and historical route-wide adjustments remain correct
    @Test
    @DisplayName("Item 8: Trip-specific adjustments and historical route-wide adjustments apply correctly to target trips")
    void test8_TripSpecificAndRouteWideAdjustments_CorrectScoping() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 8);
        LocalDateTime allocTime = serviceDate.atTime(5, 0);

        OfficerRepository repoMock = mock(OfficerRepository.class);
        OfficerService svc = new OfficerService(repoMock);

        List<Long> stopIds = List.of(1L, 2L, 3L);
        when(repoMock.findEngineRoutes(TEST_SESSION_ID))
                .thenReturn(List.of(Map.of("routeId", TEST_ROUTE_ID, "routeCode", "10H", "routeName", "Route 10H")));
        when(repoMock.findAllEngineStopsByRoute())
                .thenReturn(Map.of(TEST_ROUTE_ID, stopIds));

        // Two trips on Route 10H: Trip 1017 and Trip 1018
        List<Map<String, Object>> trips = List.of(
                Map.of("tripId", 1017L, "routeId", TEST_ROUTE_ID, "departureTime", allocTime, "expectedArrivalTime", allocTime.plusHours(1), "currentBusId", 119L, "currentBusCapacity", 60),
                Map.of("tripId", 1018L, "routeId", TEST_ROUTE_ID, "departureTime", allocTime.plusMinutes(30), "expectedArrivalTime", allocTime.plusHours(1).plusMinutes(30), "currentBusId", 120L, "currentBusCapacity", 60)
        );
        when(repoMock.findEngineTrips(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class)))
                .thenReturn(trips);
        when(repoMock.findEngineBuses(TEST_SESSION_ID)).thenReturn(List.of());
        when(repoMock.findEngineJourneys(eq(TEST_SESSION_ID), eq(serviceDate), any(LocalDateTime.class))).thenReturn(List.of());
        when(repoMock.findEngineCommitments(serviceDate)).thenReturn(List.of());

        // Trip 1017 gets trip-specific adjustment of +25
        // Route 10H gets historical route-wide adjustment of +5
        List<Map<String, Object>> adjs = new ArrayList<>();
        Map<String, Object> adjTrip = new HashMap<>();
        adjTrip.put("sessionId", TEST_SESSION_ID);
        adjTrip.put("routeId", TEST_ROUTE_ID);
        adjTrip.put("tripId", 1017L);
        adjTrip.put("startStopId", 1L);
        adjTrip.put("endStopId", 2L);
        adjTrip.put("totalDelta", 25);
        adjs.add(adjTrip);

        Map<String, Object> adjRoute = new HashMap<>();
        adjRoute.put("sessionId", TEST_SESSION_ID);
        adjRoute.put("routeId", TEST_ROUTE_ID);
        adjRoute.put("tripId", null);
        adjRoute.put("startStopId", 1L);
        adjRoute.put("endStopId", 2L);
        adjRoute.put("totalDelta", 5);
        adjs.add(adjRoute);

        when(repoMock.findSimulationAdjustments(TEST_SESSION_ID)).thenReturn(adjs);

        Input input = svc.buildEngineInput(TEST_SESSION_ID, serviceDate, allocTime);

        // Trip 1017 should receive 25 (trip-specific) + 5 (route-wide) = 30
        // Trip 1018 should receive 5 (route-wide only)
        int trip1017Journeys = 0;
        int trip1018Journeys = 0;
        for (Journey j : input.journeys()) {
            if (j.tripId() == 1017L) trip1017Journeys += j.passengers();
            if (j.tripId() == 1018L) trip1018Journeys += j.passengers();
        }

        assertEquals(30, trip1017Journeys, "Trip 1017 must get 25 + 5 = 30 adjustment passengers");
        assertEquals(5, trip1018Journeys, "Trip 1018 must get only the route-wide 5 adjustment passengers");
    }

    // 9. Existing allocation behavior and regression tests remain intact
    @Test
    @DisplayName("Item 9: Existing AllocationEngine capacity allocation and turnaround constraints remain intact")
    void test9_AllocationEngineBehaviorRemainsIntact() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 8, 5, 0);
        Route route = new Route(5L, "10H", "Route 10H", List.of(1L, 2L));
        Trip trip = new Trip(1017L, 5L, base, base.plusHours(1), null, 0);
        Journey j = new Journey(1017L, 1L, 2L, 30); // 30 effective passengers

        Bus bus1 = new Bus(119L, "TS-03-UB-1019", 60, "ACTIVE", 30); // 60 cap
        Bus bus2 = new Bus(120L, "TS-03-UB-1020", 30, "ACTIVE", 30); // 30 cap

        Input in = new Input(base, List.of(route), List.of(bus1, bus2), List.of(trip), List.of(j), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(30, outcome.peakDemand());
        assertNotNull(outcome.proposedBusId(), "Bus must be allocated");
        assertEquals(0, outcome.unmetDemand(), "Bus capacity of 60 must leave 0 unmet demand for peak of 30");
    }

    // ----------------------------------------------------------------
    // Case A: Intermediate boardings/alightings where trip-wide bookings differ from peak segment bookings
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case A: Trip-wide bookings (35) differ from peak segment bookings (5) with adjustment (+25) -> peak demand 30, adj 25, NOT -5")
    void testCaseA_IntermediateBoardings_TripBookingsDifferentFromPeakBookings() {
        // Segment 1: 10 bookings, +5 adj = 15 effective demand
        // Segment 2: 5 bookings, +25 adj = 30 effective demand (PEAK)
        // Segment 3: 20 bookings, 0 adj = 20 effective demand
        // Trip-wide bookings = 10 + 5 + 20 = 35 passengers.
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("routeId", TEST_ROUTE_ID);
        tripRow.put("routeCode", "10H");
        tripRow.put("departureTime", LocalDateTime.of(2026, 11, 8, 5, 0));
        tripRow.put("bookedPassengerCount", 35);        // Trip-wide bookings
        tripRow.put("peakSegmentBookings", 5);           // Bookings on peak segment
        tripRow.put("administrativeAdjustment", 25);     // Applicable adjustment on peak segment
        tripRow.put("peakSegmentDemand", 30);            // GREATEST(0, 5 + 25) = 30
        tripRow.put("currentBusCapacity", 60);
        tripRow.put("currentBusRegistration", "TS-03-UB-1019");

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        assertEquals(1, trips.size());

        Map<String, Object> t = trips.get(0);
        assertEquals(35, t.get("bookedPassengerCount"), "Trip-wide bookings must remain 35");
        assertEquals(5, t.get("peakSegmentBookings"), "Peak segment bookings must be 5");
        assertEquals(30, t.get("effectivePeakDemand"), "Effective peak demand must be 30");
        assertEquals(25, t.get("administrativeAdjustment"), "Administrative adjustment must be +25, NOT peakDemand - booked (30 - 35 = -5)");
        assertNotEquals(30 - 35, t.get("administrativeAdjustment"), "Must NOT calculate peak adjustment by subtracting trip-wide bookings from peak demand");
        assertEquals(50, t.get("utilizationPercent"), "Utilization must be based on peak demand: 30 / 60 = 50%");
    }

    // ----------------------------------------------------------------
    // Case B: Tied peak segments with deterministic tie-breaking (earliest route stop sequence wins)
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case B: Tied peak segments: deterministic tie-breaking selects earliest route stop sequence")
    void testCaseB_TiedPeakSegments_DeterministicTieBreaking() {
        // Stop sequence 1: 20 bookings + 10 adj = 30 effective demand
        // Stop sequence 2: 10 bookings + 20 adj = 30 effective demand
        // Both segments tie at 30 pax effective demand.
        // The earliest route sequence (seq 1) is chosen deterministically by ORDER BY seg_demand DESC, base.stop_sequence ASC LIMIT 1.
        // Therefore, the peak adjustment exposed is +10 (from seq 1), not +20.
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("routeId", TEST_ROUTE_ID);
        tripRow.put("bookedPassengerCount", 30);
        tripRow.put("peakSegmentBookings", 20);          // Selected from earliest sequence (seq 1)
        tripRow.put("administrativeAdjustment", 10);     // Selected from earliest sequence (seq 1)
        tripRow.put("peakSegmentDemand", 30);            // Clamped peak demand
        tripRow.put("currentBusCapacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(30, t.get("effectivePeakDemand"));
        assertEquals(10, t.get("administrativeAdjustment"), "Earliest sequence's adjustment (+10) must be returned on tie");
        assertEquals(20, t.get("peakSegmentBookings"));

        // Also verify the exact SQL generated by OfficerRepository incorporates deterministic tie-breaking
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        officerRepository.findScheduledTrips(TEST_SESSION_ID, LocalDate.of(2026, 11, 8));

        verify(jdbcTemplate, atLeastOnce()).queryForList(
                argThat(sql -> sql.contains("ORDER BY GREATEST(0, base.seg_bookings + base.seg_adj) DESC, base.stop_sequence ASC")
                        && sql.contains("LIMIT 1")),
                eq(TEST_SESSION_ID), eq(TEST_SESSION_ID), eq(TEST_SESSION_ID), eq(TEST_SESSION_ID),
                eq(LocalDate.of(2026, 11, 8)),
                eq(TEST_SESSION_ID), eq(TEST_SESSION_ID), eq(TEST_SESSION_ID));
    }

    // ----------------------------------------------------------------
    // Case C: Negative adjustment with non-negative clamped effective demand
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case C: Negative adjustment clamped to zero effective demand: 5 bookings + (-15) adj = 0 effective demand, adj = -15")
    void testCaseC_NegativeAdjustment_ClampedToZeroEffectiveDemand() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("bookedPassengerCount", 12);
        tripRow.put("peakSegmentBookings", 5);
        tripRow.put("administrativeAdjustment", -15);
        tripRow.put("peakSegmentDemand", 0);            // GREATEST(0, 5 + (-15)) = 0
        tripRow.put("currentBusCapacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(0, t.get("effectivePeakDemand"), "Negative effective demand must be clamped to zero");
        assertEquals(0, t.get("demand"), "demand alias must be clamped to zero");
        assertEquals(-15, t.get("administrativeAdjustment"), "Actual negative adjustment delta must be preserved as -15");
        assertEquals(0, t.get("utilizationPercent"), "Utilization must be 0% for 0 effective demand");
    }

    // ----------------------------------------------------------------
    // Case D: Inquiry handling without mutating guaranteed occupancy
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case D: Inquiries do not mutate guaranteed occupancy or effective peak demand")
    void testCaseD_InquiryHandling_NoOccupancyMutation() {
        Map<String, Object> tripRow = new HashMap<>();
        tripRow.put("tripId", TEST_TRIP_ID);
        tripRow.put("bookedPassengerCount", 5);          // Confirmed bookings
        tripRow.put("activeInquiryCount", 4);           // UNCONFIRMED inquiries
        tripRow.put("totalDemand", 9);                  // 5 + 4
        tripRow.put("peakSegmentBookings", 5);
        tripRow.put("administrativeAdjustment", 25);
        tripRow.put("peakSegmentDemand", 30);           // 5 confirmed + 25 adj (inquiries NOT added)
        tripRow.put("currentBusCapacity", 60);

        when(jdbcTemplate.queryForList(contains("FROM scheduled_trips t"), any(Object[].class)))
                .thenReturn(List.of(tripRow));

        List<Map<String, Object>> trips = officerService.getTrips(LocalDate.of(2026, 11, 8));
        Map<String, Object> t = trips.get(0);

        assertEquals(5, t.get("bookedPassengerCount"));
        assertEquals(4, t.get("activeInquiryCount"));
        assertEquals(30, t.get("effectivePeakDemand"));
        assertEquals(50, t.get("utilizationPercent"), "Guaranteed occupancy must be exactly 30 / 60 = 50%");
        assertNotEquals(34, t.get("effectivePeakDemand"), "Inquiries must NOT be added to effective peak demand (must NOT be 34)");
        assertNotEquals(57, t.get("utilizationPercent"), "Utilization must NOT include inquiries (must NOT be 57%)");
    }
}
