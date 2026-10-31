package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

/** Database-free checks of the allocation logic. Run with: java AllocationEngineCheck (exit code 1 on failure). */
public class AllocationEngineCheck {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static LocalDateTime t(String hhmm) { return LocalDateTime.parse("2026-10-26T" + hhmm); }

    public static void main(String[] a) {
        Route r1 = new Route(1, "R1", "Central-Airport", List.of(1L, 2L, 3L, 4L));   // A B C D
        Route r2 = new Route(2, "R2", "EastGate-Lake", List.of(5L, 6L, 7L));

        // --- 1. Segment demand: A->C (2 people) covers A-B and B-C only; B->D (1) covers B-C, C-D.
        Input in1 = new Input(t("07:00"), List.of(r1),
                List.of(new Bus(101, "B1", 50, "ACTIVE", 0)),
                List.of(new Trip(1001, 1, t("08:00"), t("09:30"), null, 0)),
                List.of(new Journey(1001, 1, 3, 2), new Journey(1001, 2, 4, 1)), List.of());
        Result res1 = AllocationEngine.allocate(in1);
        List<SegmentRow> seg = res1.afterSegments();
        check("segment demand A-B=2, B-C=3, C-D=1",
                seg.get(0).demand() == 2 && seg.get(1).demand() == 3 && seg.get(2).demand() == 1);
        check("baseline without a bus: capacity 0, unmet = total demand", res1.beforeTotal().unmet() == 6);
        check("after allocation: all served", res1.afterTotal().unmet() == 0 && res1.afterTotal().accommodated() == 6);

        // --- 2. Two overlapping trips, buses of 30 and 50 seats: big trip must get the 50-seater
        //        even though the baseline had them the wrong way round.
        Input in2 = new Input(t("07:00"), List.of(r1, r2),
                List.of(new Bus(1, "SMALL", 30, "ACTIVE", 0), new Bus(2, "BIG", 50, "ACTIVE", 0)),
                List.of(new Trip(10, 1, t("08:00"), t("09:30"), 1L, 30),
                        new Trip(20, 2, t("08:00"), t("09:00"), 2L, 50)),
                List.of(new Journey(10, 1, 4, 45), new Journey(20, 5, 7, 20)), List.of());
        Result res2 = AllocationEngine.allocate(in2);
        check("big trip gets 50-seater, small trip the 30-seater",
                res2.outcomes().get(0).proposedBusId() == 2L && res2.outcomes().get(1).proposedBusId() == 1L);
        check("unmet falls from 45 to 0 (baseline: 15 per segment x3)",
                res2.beforeTotal().unmet() == 45 && res2.afterTotal().unmet() == 0);
        check("outcome types are REASSIGNED", res2.outcomes().get(0).type().equals("REASSIGNED"));

        // --- 3. Rest period: bus finished a trip at 10:00 and rests 60 min.
        Commitment busy = new Commitment(1, 99, "R9", t("08:00"), t("10:00"));
        Bus rester = new Bus(1, "REST", 50, "ACTIVE", 60);
        Result early = AllocationEngine.allocate(new Input(t("07:00"), List.of(r1), List.of(rester),
                List.of(new Trip(30, 1, t("10:30"), t("12:00"), null, 0)),
                List.of(new Journey(30, 1, 2, 5)), List.of(busy)));
        check("trip at 10:30 blocked (bus rests until 11:00)", early.outcomes().get(0).type().equals("UNASSIGNED"));
        Result late = AllocationEngine.allocate(new Input(t("07:00"), List.of(r1), List.of(rester),
                List.of(new Trip(30, 1, t("11:00"), t("12:30"), null, 0)),
                List.of(new Journey(30, 1, 2, 5)), List.of(busy)));
        check("trip at 11:00 allowed", late.outcomes().get(0).type().equals("REASSIGNED"));

        // --- 4. Availability report at 10:01 (rest 10:00-11:00 => unavailable)
        Result rep = AllocationEngine.allocate(new Input(t("10:01"), List.of(r1), List.of(rester),
                List.of(), List.of(), List.of(busy)));
        check("bus unavailable at 10:01 with reason", !rep.buses().get(0).availableAtTime()
                && rep.buses().get(0).unavailableReason().startsWith("Resting"));

        // --- 5. Same bus can serve two non-overlapping trips; maintenance bus is never used.
        Input in5 = new Input(t("07:00"), List.of(r1),
                List.of(new Bus(1, "OK", 50, "ACTIVE", 10), new Bus(2, "MNT", 80, "MAINTENANCE", 0)),
                List.of(new Trip(41, 1, t("08:00"), t("09:30"), null, 0), new Trip(42, 1, t("14:00"), t("15:30"), null, 0)),
                List.of(new Journey(41, 1, 4, 10), new Journey(42, 1, 4, 10)), List.of());
        Result res5 = AllocationEngine.allocate(in5);
        check("one bus covers both trips, maintenance bus unused",
                res5.outcomes().stream().allMatch(o -> o.proposedBusId() != null && o.proposedBusId() == 1L)
                        && res5.busesAssigned() == 1 && res5.busesConsidered() == 1);


        // --- 6. Fixed daily rest window (separate from post-trip rest duration).
        Bus fixedRest = new Bus(7, "FIXED", 40, "ACTIVE", "Depot", 0,
                java.time.LocalTime.parse("10:00"), java.time.LocalTime.parse("11:00"), null, null, true);
        Result fixedBlocked = AllocationEngine.allocate(new Input(t("07:00"), List.of(r1), List.of(fixedRest),
                List.of(new Trip(61, 1, t("10:30"), t("12:00"), null, 0)),
                List.of(new Journey(61, 1, 2, 5)), List.of()));
        check("fixed daily rest window blocks an overlapping trip",
                fixedBlocked.outcomes().get(0).type().equals("UNASSIGNED"));
        Result fixedAllowed = AllocationEngine.allocate(new Input(t("07:00"), List.of(r1), List.of(fixedRest),
                List.of(new Trip(62, 1, t("11:00"), t("12:00"), null, 0)),
                List.of(new Journey(62, 1, 2, 5)), List.of()));
        check("trip starting at fixed rest end is allowed",
                fixedAllowed.outcomes().get(0).type().equals("REASSIGNED"));

        System.out.println(failures == 0 ? "\nAll checks passed." : "\n" + failures + " check(s) FAILED.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
