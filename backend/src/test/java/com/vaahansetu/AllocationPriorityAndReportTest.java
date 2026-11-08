package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vaahansetu.allocation.AllocationEngine;
import com.vaahansetu.allocation.AllocationModels.*;

class AllocationPriorityAndReportTest {

    private final LocalDateTime base = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Test
    @DisplayName("Scenario 1: Two overlapping trips with 2 buses - Trip A receives both buses to maximize accommodated demand")
    void test1_TwoOverlappingTrips_TwoBuses_TripAReceivesBoth() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        // Simultaneous trips
        Trip tripA = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Trip tripB = new Trip(2L, 1L, base, base.plusHours(1), null, 0);

        // Trip A has 100 passengers (needs 2 buses of 60 seats); Trip B has 30 passengers (needs 1 bus)
        Journey jA = new Journey(1L, 101L, 102L, 100);
        Journey jB = new Journey(2L, 101L, 102L, 30);

        // 2 buses available with 60 seats each
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2), List.of(tripA, tripB), List.of(jA, jB), List.of());
        Result res = AllocationEngine.allocate(in);

        // Allocating both buses to Trip A accommodates 100 passengers (0 unmet).
        // Allocating 1 bus to Trip A (60) and 1 to Trip B (30) accommodates 90 (40 unmet on Trip A).
        // Greedy marginal gain prioritizes Trip A for both buses.
        TripOutcome outcomeA = res.outcomes().stream().filter(o -> o.tripId() == 1L).findFirst().orElseThrow();
        TripOutcome outcomeB = res.outcomes().stream().filter(o -> o.tripId() == 2L).findFirst().orElseThrow();

        assertEquals(2, outcomeA.proposedBusIds().size(), "Trip A must be allocated 2 buses");
        assertTrue(outcomeA.proposedBusIds().contains(1L));
        assertTrue(outcomeA.proposedBusIds().contains(2L));
        assertEquals("UNASSIGNED", outcomeB.type(), "Trip B must remain UNASSIGNED when both buses accommodate more on Trip A");

        assertEquals(100, res.afterTotal().accommodated(), "100 passengers must be accommodated");
        assertEquals(30, res.afterTotal().unmet(), "Only Trip B's 30 passengers remain unmet");
    }

    @Test
    @DisplayName("Scenario 2: Two overlapping trips with 3 buses - Trip A receives 2 buses and Trip B receives 1 bus")
    void test2_TwoOverlappingTrips_ThreeBuses_TripAGetsTwoTripBGetsOne() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip tripA = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Trip tripB = new Trip(2L, 1L, base, base.plusHours(1), null, 0);

        Journey jA = new Journey(1L, 101L, 102L, 100);
        Journey jB = new Journey(2L, 101L, 102L, 30);

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 60, "ACTIVE", 30);
        Bus b3 = new Bus(3L, "TS-01-UB-1003", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2, b3), List.of(tripA, tripB), List.of(jA, jB), List.of());
        Result res = AllocationEngine.allocate(in);

        TripOutcome outcomeA = res.outcomes().stream().filter(o -> o.tripId() == 1L).findFirst().orElseThrow();
        TripOutcome outcomeB = res.outcomes().stream().filter(o -> o.tripId() == 2L).findFirst().orElseThrow();

        assertEquals(2, outcomeA.proposedBusIds().size(), "Trip A must receive 2 buses (120 seats for 100 pax)");
        assertEquals(1, outcomeB.proposedBusIds().size(), "Trip B must receive 1 bus (60 seats for 30 pax)");
        assertEquals(0, res.afterTotal().unmet(), "All 130 passengers across both trips must be accommodated");
        assertEquals(130, res.afterTotal().accommodated());
    }

    @Test
    @DisplayName("Scenario 3: One bus per trip is sufficient - no unnecessary buses are allocated")
    void test3_OneBusPerTripSufficient_NoUnnecessaryBusesAllocated() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip tripA = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Trip tripB = new Trip(2L, 1L, base, base.plusHours(1), null, 0);

        // Trip A has 45 pax, Trip B has 20 pax (bus cap 60)
        Journey jA = new Journey(1L, 101L, 102L, 45);
        Journey jB = new Journey(2L, 101L, 102L, 20);

        // 4 active buses in the fleet
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 60, "ACTIVE", 30);
        Bus b3 = new Bus(3L, "TS-01-UB-1003", 60, "ACTIVE", 30);
        Bus b4 = new Bus(4L, "TS-01-UB-1004", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2, b3, b4), List.of(tripA, tripB), List.of(jA, jB), List.of());
        Result res = AllocationEngine.allocate(in);

        TripOutcome outcomeA = res.outcomes().stream().filter(o -> o.tripId() == 1L).findFirst().orElseThrow();
        TripOutcome outcomeB = res.outcomes().stream().filter(o -> o.tripId() == 2L).findFirst().orElseThrow();

        assertEquals(1, outcomeA.proposedBusIds().size(), "Trip A needs only 1 bus for 45 pax");
        assertEquals(1, outcomeB.proposedBusIds().size(), "Trip B needs only 1 bus for 20 pax");
        assertEquals(2, res.busesAssigned(), "Exactly 2 buses assigned across the two trips");
        assertEquals(2, res.busesLeftUnassigned(), "Remaining 2 buses must stay unassigned/idle");
        assertEquals(0, res.afterTotal().unmet());
    }

    @Test
    @DisplayName("Scenario 4: Demand exceeds fleet capacity - unmet demand reported accurately in summary and segment metrics")
    void test4_DemandExceedsFleetCapacity_UnmetDemandReportedAccurately() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L, 103L));

        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        // Demand across two segments: 101->102 (150 pax), 102->103 (150 pax)
        Journey j1 = new Journey(1L, 101L, 103L, 150);

        // Only 2 buses available of capacity 60 each (total capacity 120)
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        // Total demand = 150 + 150 = 300 passenger-segments.
        // Total capacity = 120 per segment = 240 passenger-segments.
        // Accommodated = 120 + 120 = 240.
        // Unmet = 30 + 30 = 60 passenger-segments.
        assertEquals(300, res.afterTotal().demand());
        assertEquals(240, res.afterTotal().accommodated());
        assertEquals(60, res.afterTotal().unmet(), "Summary unmet demand must accurately report 60 unmet passenger-segments");

        TripOutcome outcome = res.outcomes().get(0);
        assertTrue(outcome.reason().contains("60 unmet passenger-segments"), "Explanation text must match the unmet demand");
    }

    @Test
    @DisplayName("Scenario 5: Bus capacity respected across individual route segments and off-peak trips do not erase peak unmet")
    void test5_SegmentCapacityRespected_OffPeakTripsDoNotErasePeakUnmet() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        // Peak trip at 08:00 has 100 passengers
        Trip peakTrip = new Trip(1L, 1L, base, base.plusHours(1), 1L, 60);
        Journey jPeak = new Journey(1L, 101L, 102L, 100);

        // Off-peak trip at 14:00 has 10 passengers
        Trip offPeakTrip = new Trip(2L, 1L, base.plusHours(4), base.plusHours(5), 2L, 60);
        Journey jOffPeak = new Journey(2L, 101L, 102L, 10);

        // Only 1 bus available (cap 60); it serves peak trip (10:00-11:00), rests, then serves off-peak trip (14:00-15:00)
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(peakTrip, offPeakTrip), List.of(jPeak, jOffPeak), List.of());
        Result res = AllocationEngine.allocate(in);

        // Segment 101->102 total demand: 100 + 10 = 110 pax-segments.
        // Capacity: 60 + 60 = 120.
        // Peak trip accommodated: 60, unmet: 40.
        // Off-peak trip accommodated: 10, unmet: 0, unused: 50.
        // Total accommodated: 70.
        // Total unmet: 40! (Old bug previously calculated max(0, 110 - 120) = 0).
        assertEquals(110, res.afterTotal().demand());
        assertEquals(70, res.afterTotal().accommodated());
        assertEquals(40, res.afterTotal().unmet(), "Peak trip unmet demand (40) must NOT be erased by off-peak unused capacity");
    }

    @Test
    @DisplayName("Scenario 6: Bus already busy on overlapping trip cannot be double-assigned")
    void test6_BusAlreadyBusyCannotBeDoubleAssigned() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Trip t2 = new Trip(2L, 1L, base.plusMinutes(30), base.plusMinutes(90), null, 0); // Overlaps with t1

        Journey j1 = new Journey(1L, 101L, 102L, 40);
        Journey j2 = new Journey(2L, 101L, 102L, 40);

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        long assignedCount = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, assignedCount, "Only 1 trip can receive the bus; the overlapping trip must remain unassigned");
        assertTrue(res.outcomes().stream().anyMatch(o -> "UNASSIGNED".equals(o.type())));
    }

    @Test
    @DisplayName("Scenario 7: Turnaround / rest duration respected between consecutive trips")
    void test7_TurnaroundRestDurationRespected() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0); // 10:00 - 11:00
        Trip t2 = new Trip(2L, 1L, base.plusHours(1).plusMinutes(15), base.plusHours(2), null, 0); // 11:15 - 12:00 (only 15 min rest)

        Journey j1 = new Journey(1L, 101L, 102L, 30);
        Journey j2 = new Journey(2L, 101L, 102L, 30);

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30); // 30 min required rest

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        long assignedCount = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, assignedCount, "Single bus cannot serve both trips when turnaround buffer is violated");
    }

    @Test
    @DisplayName("Scenario 8: Commitments on unselected routes remain untouched")
    void test8_UnselectedRouteCommitmentsRemainUntouched() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(1L, 101L, 102L, 35);

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Commitment commitment = new Commitment(1L, 999L, "OTHER-ROUTE", base, base.plusHours(1));

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of(commitment));
        Result res = AllocationEngine.allocate(in);

        TripOutcome outcome = res.outcomes().get(0);
        assertNull(outcome.proposedBusId(), "Bus with commitment cannot be assigned to selected route");
        assertEquals("UNASSIGNED", outcome.type());
    }

    @Test
    @DisplayName("Scenario 9: Maintenance and inactive buses excluded from eligibility")
    void test9_MaintenanceAndInactiveBusesExcluded() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));

        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(1L, 101L, 102L, 30);

        Bus bMaint = new Bus(1L, "TS-01-UB-MAINT", 60, "MAINTENANCE", 30);
        Bus bInact = new Bus(2L, "TS-01-UB-INACT", 60, "INACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(bMaint, bInact), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(0, res.busesConsidered(), "Zero active buses considered");
        TripOutcome outcome = res.outcomes().get(0);
        assertNull(outcome.proposedBusId());
        assertEquals("UNASSIGNED", outcome.type());
    }

    @Test
    @DisplayName("Scenario 10: Allocation report values match actual allocation result with accurate units")
    void test10_AllocationReportValuesMatchAllocationResult() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L, 103L));

        Trip t1 = new Trip(10L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(10L, 101L, 102L, 45);
        Journey j2 = new Journey(10L, 102L, 103L, 55); // Peak demand is 55 on segment 2

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 60, "ACTIVE", 30);
        Bus b3 = new Bus(3L, "TS-01-UB-1003", 60, "MAINTENANCE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2, b3), List.of(t1), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(2, res.busesConsidered(), "Eligible active buses must be 2");
        assertEquals(1, res.busesAssigned(), "1 bus assigned");
        assertEquals(1, res.busesLeftUnassigned(), "1 active bus unassigned/idle");

        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(55, outcome.peakDemand(), "TripOutcome peak demand must be 55");
        assertEquals(0, outcome.unmetDemand(), "Unmet demand must be 0");
        assertTrue(outcome.reason().contains("55"), "Explanation must mention peak demand 55");
        assertTrue(outcome.reason().contains("60 seats"), "Explanation must mention 60 seats");

        // Segment reconciliation
        int totalSegDemand = 45 + 55; // 100
        assertEquals(totalSegDemand, res.afterTotal().demand());
        assertEquals(100, res.afterTotal().accommodated());
        assertEquals(0, res.afterTotal().unmet());
    }

    @Test
    @DisplayName("Scenario 11: Route length does not distort allocation priority - higher passenger load prioritized over more segments")
    void test11_RouteLengthDoesNotDistortPriority() {
        // Route 1 is long (10 segments)
        Route rLong = new Route(1L, "R-LONG", "Long Route", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
        // Route 2 is short (2 segments)
        Route rShort = new Route(2L, "R-SHORT", "Short Route", List.of(201L, 202L, 203L));

        // Simultaneous trips
        Trip tLong = new Trip(10L, 1L, base, base.plusHours(1), null, 0);
        Trip tShort = new Trip(20L, 2L, base, base.plusHours(1), null, 0);

        // Long trip has only 15 passengers on each of its 10 segments (total pax-seg = 150, but peak is 15)
        Journey jLong = new Journey(10L, 1L, 11L, 15);
        // Short trip has 50 passengers on each of its 2 segments (total pax-seg = 100, but peak is 50)
        Journey jShort = new Journey(20L, 201L, 203L, 50);

        // Only 1 bus available (cap 60)
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(rLong, rShort), List.of(b1), List.of(tLong, tShort), List.of(jLong, jShort), List.of());
        Result res = AllocationEngine.allocate(in);

        TripOutcome oShort = res.outcomes().stream().filter(o -> o.tripId() == 20L).findFirst().orElseThrow();
        TripOutcome oLong = res.outcomes().stream().filter(o -> o.tripId() == 10L).findFirst().orElseThrow();

        // Short trip has 50 peak passengers vs Long trip's 15 peak passengers.
        // The bus MUST be allocated to the Short trip to accommodate 50 real passengers!
        assertEquals(1L, oShort.proposedBusId(), "Short trip with 50 passengers must receive the bus over long trip with only 15 passengers");
        assertEquals("UNASSIGNED", oLong.type(), "Long trip with only 15 passengers must remain unassigned");
        assertEquals(100, res.afterTotal().accommodated(), "Short trip's 50 passengers across 2 segments accommodated = 100 pax-seg");
    }
}
