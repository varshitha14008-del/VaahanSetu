package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.vaahansetu.allocation.AllocationEngine;
import com.vaahansetu.allocation.AllocationModels.*;
import com.vaahansetu.controller.AdminController;
import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.OfficerService;

class AutomaticBusEligibilityTest {

    private final LocalDateTime base = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Test
    @DisplayName("Case 1: Selected routes have assigned active buses that are retained or upgraded")
    void test1_SelectedRoutesHaveAssignedActiveBuses() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base, base.plusHours(1), 1L, 40);
        Journey j1 = new Journey(10L, 101L, 102L, 25);
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 40, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(1L, outcome.proposedBusId(), "Assigned active bus should be retained");
        assertEquals("UNCHANGED", outcome.type());
    }

    @Test
    @DisplayName("Case 2: Selected routes have no assigned buses, but other free active buses exist and are allocated")
    void test2_SelectedRoutesHaveNoAssignedBuses_FreeActiveBusesAllocated() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        Trip t1 = new Trip(20L, 1L, base, base.plusHours(1), null, 0); // No assigned bus initially
        Journey j1 = new Journey(20L, 101L, 102L, 35);
        Bus freeBus = new Bus(5L, "TS-01-UB-1005", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(freeBus), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(5L, outcome.proposedBusId(), "Free active bus should automatically be allocated");
        assertEquals("REASSIGNED", outcome.type());
    }

    @Test
    @DisplayName("Case 3: A bus is under maintenance and must be excluded from allocation")
    void test3_MaintenanceBusExcluded() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        Trip t1 = new Trip(30L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(30L, 101L, 102L, 20);
        Bus maintenanceBus = new Bus(2L, "TS-01-UB-MAINT", 60, "MAINTENANCE", 30);

        Input in = new Input(base, List.of(r1), List.of(maintenanceBus), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertNull(outcome.proposedBusId(), "Bus under maintenance must not be allocated");
        assertEquals("UNASSIGNED", outcome.type());
    }

    @Test
    @DisplayName("Case 4: A bus has an overlapping trip and must be excluded")
    void test4_OverlappingTripBusExcluded() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        Trip t1 = new Trip(41L, 1L, base, base.plusHours(1), null, 0); // 10:00 - 11:00
        Trip t2 = new Trip(42L, 1L, base.plusMinutes(30), base.plusMinutes(90), null, 0); // 10:30 - 11:30 (overlaps)
        Journey j1 = new Journey(41L, 101L, 102L, 30);
        Journey j2 = new Journey(42L, 101L, 102L, 30);
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(2, res.outcomes().size());
        long allocatedCount = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, allocatedCount, "Only one trip can be allocated to the single bus due to overlap");
        assertTrue(res.outcomes().stream().anyMatch(o -> "UNASSIGNED".equals(o.type())), "Second overlapping trip must remain UNASSIGNED");
    }

    @Test
    @DisplayName("Case 5: A bus has not completed its required rest duration and must be excluded")
    void test5_BusNotCompletedRequiredRestDurationExcluded() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        // Trip 1: 10:00 - 11:00
        Trip t1 = new Trip(51L, 1L, base, base.plusHours(1), null, 0);
        // Trip 2: 11:15 - 12:00 (starts only 15 min after Trip 1 ends, violating 30 min rest)
        Trip t2 = new Trip(52L, 1L, base.plusHours(1).plusMinutes(15), base.plusHours(2), null, 0);
        Journey j1 = new Journey(51L, 101L, 102L, 20);
        Journey j2 = new Journey(52L, 101L, 102L, 20);
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 50, "ACTIVE", 30); // 30 min rest requirement

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(2, res.outcomes().size());
        long allocatedCount = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, allocatedCount, "Single bus cannot serve both trips when required rest turnaround is not met");
    }

    @Test
    @DisplayName("Case 6: An active bus assigned to another route is free at trip time and can be allocated to a selected route")
    void test6_ActiveBusAssignedToAnotherRouteFreeAtTripTimeAllocated() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        // Selected route trip: 10:30 - 11:30
        Trip t1 = new Trip(60L, 1L, base.plusMinutes(30), base.plusMinutes(90), null, 0);
        Journey j1 = new Journey(60L, 101L, 102L, 35);

        // Bus 10 is active, normally assigned to unselected Route 99, with a commitment from 08:00 to 09:00
        Bus b10 = new Bus(10L, "TS-02-UB-1010", 50, "ACTIVE", 30);
        Commitment c1 = new Commitment(10L, 999L, "99-OTHER", base.minusHours(2), base.minusHours(1));

        Input in = new Input(base, List.of(r1), List.of(b10), List.of(t1), List.of(j1), List.of(c1));
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertEquals(10L, outcome.proposedBusId(), "Active bus from another route free after rest should be allocated to selected route");
    }

    @Test
    @DisplayName("Case 7: Multiple buses are needed for one route with simultaneous/close trips")
    void test7_MultipleBusesNeededForOneRoute() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        // Two trips on the same route at the same time: 10:00 - 11:00
        Trip t1 = new Trip(71L, 1L, base, base.plusHours(1), null, 0);
        Trip t2 = new Trip(72L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(71L, 101L, 102L, 40);
        Journey j2 = new Journey(72L, 101L, 102L, 40);

        Bus b1 = new Bus(1L, "TS-01-UB-1001", 50, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "TS-01-UB-1002", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(2, res.outcomes().size());
        TripOutcome o1 = res.outcomes().get(0);
        TripOutcome o2 = res.outcomes().get(1);
        assertNotNull(o1.proposedBusId());
        assertNotNull(o2.proposedBusId());
        assertNotEquals(o1.proposedBusId(), o2.proposedBusId(), "Two different buses must be allocated to concurrent trips on the same route");
    }

    @Test
    @DisplayName("Case 8: Unselected route commitments remain unchanged and block conflicting allocations")
    void test8_UnselectedRouteCommitmentsRemainUnchanged() {
        Route r1 = new Route(1L, "8A", "Secunderabad - Chandrayangutta", List.of(101L, 102L));
        // Selected route trip: 10:00 - 11:00
        Trip t1 = new Trip(80L, 1L, base, base.plusHours(1), null, 0);
        Journey j1 = new Journey(80L, 101L, 102L, 30);

        // Only one active bus exists, but it has an active commitment on unselected route from 10:00 to 11:00
        Bus b1 = new Bus(1L, "TS-01-UB-1001", 50, "ACTIVE", 30);
        Commitment unselectedRouteCommitment = new Commitment(1L, 888L, "UNSELECTED-ROUTE", base, base.plusHours(1));

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of(unselectedRouteCommitment));
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.outcomes().size());
        TripOutcome outcome = res.outcomes().get(0);
        assertNull(outcome.proposedBusId(), "Committed bus on unselected route cannot be allocated to selected route during conflict");
        assertEquals("UNASSIGNED", outcome.type());
    }

    @Test
    @DisplayName("Case 9: No buses are manually selected in the frontend or required in the allocation request payload")
    void test9_NoBusesRequiredInAllocationPayload() {
        OfficerService mockOfficerService = mock(OfficerService.class);
        AuthService mockAuthService = mock(AuthService.class);

        when(mockAuthService.validate("valid-token")).thenReturn(Map.of("role", "ADMIN", "username", "admin"));

        AdminController controller = new AdminController(mockOfficerService, mockAuthService);

        // Payload contains ONLY routeIds; busIds is completely omitted
        Map<String, Object> payloadWithoutBuses = Map.of(
                "routeIds", List.of(1L, 2L)
        );

        ResponseEntity<?> response = controller.saveSelection("Bearer valid-token", 1L, payloadWithoutBuses);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockOfficerService, times(1)).saveSelection(eq(1L), eq(List.of(1L, 2L)), eq(List.of()));
    }
}
