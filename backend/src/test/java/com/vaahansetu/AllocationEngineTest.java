package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import allocation.core.AllocationEngine;
import allocation.core.AllocationModels.*;

public class AllocationEngineTest {

    private LocalDateTime t(String hhmm) {
        return LocalDateTime.parse("2026-10-26T" + hhmm);
    }

    @Test
    @DisplayName("Verify segment-wise passenger demand calculation")
    void testSegmentDemandCalculation() {
        Route r1 = new Route(1, "R1", "Central-Airport", List.of(1L, 2L, 3L, 4L));
        Input in = new Input(
                t("07:00"),
                List.of(r1),
                List.of(new Bus(101, "B1", 50, "ACTIVE", 0)),
                List.of(new Trip(1001, 1, t("08:00"), t("09:30"), null, 0)),
                List.of(new Journey(1001, 1, 3, 2), new Journey(1001, 2, 4, 1)),
                List.of());

        Result res = AllocationEngine.allocate(in);
        List<SegmentRow> seg = res.afterSegments();

        assertEquals(3, seg.size(), "Should produce 3 consecutive segments for a 4-stop route");
        assertEquals(2, seg.get(0).demand(), "Segment A-B demand");
        assertEquals(3, seg.get(1).demand(), "Segment B-C demand");
        assertEquals(1, seg.get(2).demand(), "Segment C-D demand");
        assertEquals(6, res.beforeTotal().unmet(), "Baseline unmet with no bus");
        assertEquals(0, res.afterTotal().unmet(), "All demand served after allocation");
    }

    @Test
    @DisplayName("Verify larger bus prioritized for heavier demand trip")
    void testCapacityPrioritization() {
        Route r1 = new Route(1, "R1", "Central-Airport", List.of(1L, 2L, 3L, 4L));
        Route r2 = new Route(2, "R2", "EastGate-Lake", List.of(5L, 6L, 7L));

        Input in = new Input(
                t("07:00"),
                List.of(r1, r2),
                List.of(new Bus(1, "SMALL", 30, "ACTIVE", 0), new Bus(2, "BIG", 50, "ACTIVE", 0)),
                List.of(new Trip(10, 1, t("08:00"), t("09:30"), 1L, 30),
                        new Trip(20, 2, t("08:00"), t("09:00"), 2L, 50)),
                List.of(new Journey(10, 1, 4, 45), new Journey(20, 5, 7, 20)),
                List.of());

        Result res = AllocationEngine.allocate(in);
        assertEquals(2L, res.outcomes().get(0).proposedBusId(), "Big trip must receive the 50-seater");
        assertEquals(1L, res.outcomes().get(1).proposedBusId(), "Small trip must receive the 30-seater");
        assertEquals(0, res.afterTotal().unmet(), "Unmet falls to 0 after swap");
        assertEquals("REASSIGNED", res.outcomes().get(0).type());
    }

    @Test
    @DisplayName("Verify minimum post-trip rest constraint")
    void testPostTripRestConstraint() {
        Route r1 = new Route(1, "R1", "Central-Airport", List.of(1L, 2L, 3L, 4L));
        Commitment busy = new Commitment(1, 99, "R9", t("08:00"), t("10:00"));
        Bus rester = new Bus(1, "REST", 50, "ACTIVE", 60);

        // Trip starting at 10:30 (before 60 min rest finishes at 11:00) must be blocked
        Input inEarly = new Input(
                t("07:00"),
                List.of(r1),
                List.of(rester),
                List.of(new Trip(30, 1, t("10:30"), t("12:00"), null, 0)),
                List.of(new Journey(30, 1, 2, 5)),
                List.of(busy));

        Result resEarly = AllocationEngine.allocate(inEarly);
        assertEquals("UNASSIGNED", resEarly.outcomes().get(0).type(), "Trip at 10:30 should be blocked by rest");

        // Trip starting at 11:00 (rest finished) must be allowed
        Input inLate = new Input(
                t("07:00"),
                List.of(r1),
                List.of(rester),
                List.of(new Trip(30, 1, t("11:00"), t("12:30"), null, 0)),
                List.of(new Journey(30, 1, 2, 5)),
                List.of(busy));

        Result resLate = AllocationEngine.allocate(inLate);
        assertEquals("REASSIGNED", resLate.outcomes().get(0).type(), "Trip at 11:00 should be allowed");
    }

    @Test
    @DisplayName("Verify fixed daily rest window constraint including overnight window")
    void testFixedDailyRestConstraint() {
        Route r1 = new Route(1, "R1", "Central-Airport", List.of(1L, 2L, 3L, 4L));

        // Fixed daytime rest 10:00 to 11:00
        Bus fixedRest = new Bus(7, "FIXED", 40, "ACTIVE", "Depot", 0,
                LocalTime.parse("10:00"), LocalTime.parse("11:00"), null, null, true);

        Input blockedInput = new Input(
                t("07:00"),
                List.of(r1),
                List.of(fixedRest),
                List.of(new Trip(61, 1, t("10:30"), t("12:00"), null, 0)),
                List.of(new Journey(61, 1, 2, 5)),
                List.of());

        Result blockedRes = AllocationEngine.allocate(blockedInput);
        assertEquals("UNASSIGNED", blockedRes.outcomes().get(0).type());

        // Overnight rest 22:00 to 06:00
        Bus overnightRest = new Bus(8, "NIGHT", 40, "ACTIVE", "Depot", 0,
                LocalTime.parse("22:00"), LocalTime.parse("06:00"), null, null, true);

        Input nightBlockedInput = new Input(
                t("07:00"),
                List.of(r1),
                List.of(overnightRest),
                List.of(new Trip(71, 1, t("04:00"), t("05:30"), null, 0)),
                List.of(new Journey(71, 1, 2, 5)),
                List.of());

        Result nightBlockedRes = AllocationEngine.allocate(nightBlockedInput);
        assertEquals("UNASSIGNED", nightBlockedRes.outcomes().get(0).type(), "Overnight rest must block 04:00 trip");
    }
}
