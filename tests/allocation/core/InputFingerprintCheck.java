package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class InputFingerprintCheck {

        static AllocationRepository repo = new AllocationRepository();

        static LocalDateTime time = LocalDateTime.of(2026, 11, 2, 9, 0);

        static Route route = new Route(
                        1, "R1", "Central-Airport", List.of(1L, 2L, 3L));

        static Input createInput(
                        int capacity,
                        int passengers,
                        LocalDateTime departure,
                        LocalTime restStart) {
                Bus bus = new Bus(
                                101, "BUS-101", capacity, "ACTIVE",
                                null, 60, restStart, LocalTime.of(6, 0),
                                null, null, true);

                Trip trip = new Trip(
                                1001, 1, departure,
                                departure.plusMinutes(90), null, 0);

                Journey journey = new Journey(
                                1001, 1, 3, passengers);

                return new Input(
                                time,
                                List.of(route),
                                List.of(bus),
                                List.of(trip),
                                List.of(journey),
                                List.of());
        }

        static Input createTimedInput(
                        LocalDateTime allocationTime,
                        LocalTime restStart,
                        LocalTime restEnd,
                        int restMinutes,
                        List<Commitment> commitments) {

                Bus bus = new Bus(
                                101, "BUS-101", 50, "ACTIVE",
                                null, restMinutes, restStart, restEnd,
                                null, null, true);

                Trip trip = new Trip(
                                1001, 1,
                                time,
                                time.plusMinutes(90),
                                null, 0);

                Journey journey = new Journey(1001, 1, 3, 10);

                return new Input(
                                allocationTime,
                                List.of(route),
                                List.of(bus),
                                List.of(trip),
                                List.of(journey),
                                commitments);
        }

        static void check(String description, Input first, Input second) {
                String hash1 = repo.inputFingerprint(first);
                String hash2 = repo.inputFingerprint(second);

                if (hash1.equals(hash2)) {
                        throw new AssertionError("FAIL: " + description);
                }

                System.out.println("PASS: " + description);
        }

        public static void main(String[] args) {

                Input original = createInput(
                                50, 10, time, LocalTime.of(22, 0));

                String originalHash = repo.inputFingerprint(original);

                // Same input should produce the same fingerprint.
                if (!originalHash.equals(repo.inputFingerprint(original))) {
                        throw new AssertionError("FAIL: Identical inputs produced different fingerprints.");
                }

                System.out.println("PASS: Identical inputs produce identical fingerprints.");

                // Passenger count change.
                check(
                                "Passenger count change detected",
                                original,
                                createInput(50, 15, time, LocalTime.of(22, 0)));

                // Bus capacity change.
                check(
                                "Bus capacity change detected",
                                original,
                                createInput(40, 10, time, LocalTime.of(22, 0)));

                // Trip departure change.
                check(
                                "Trip departure change detected",
                                original,
                                createInput(
                                                50, 10, time.plusHours(1), LocalTime.of(22, 0)));

                // Bus rest-window change.
                check(
                                "Rest-window change detected",
                                original,
                                createInput(50, 10, time, LocalTime.of(21, 0)));

                // Different allocation timestamps should not change the fingerprint.
                Input laterTime = new Input(
                                time.plusSeconds(5),
                                original.routes(),
                                original.buses(),
                                original.trips(),
                                original.journeys(),
                                original.commitments());

                if (!originalHash.equals(repo.inputFingerprint(laterTime))) {
                        throw new AssertionError(
                                        "FAIL: Fingerprint changed only because allocation time changed.");
                }

                System.out.println("PASS: Allocation timestamp change ignored.");

                // Fixed overnight rest: entering rest at 22:00.
                check(
                                "Fixed rest begins at 22:00",
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 21, 59),
                                                LocalTime.of(22, 0), LocalTime.of(6, 0),
                                                60, List.of()),
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 22, 0),
                                                LocalTime.of(22, 0), LocalTime.of(6, 0),
                                                60, List.of()));

                // Fixed overnight rest: leaving rest at 06:00.
                check(
                                "Fixed rest ends at 06:00",
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 3, 5, 59),
                                                LocalTime.of(22, 0), LocalTime.of(6, 0),
                                                60, List.of()),
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 3, 6, 0),
                                                LocalTime.of(22, 0), LocalTime.of(6, 0),
                                                60, List.of()));

                // Trip starts at 10:00 and ends at 11:00.
                Commitment commitment = new Commitment(
                                101, 2001, "R1",
                                LocalDateTime.of(2026, 11, 2, 10, 0),
                                LocalDateTime.of(2026, 11, 2, 11, 0));

                // Bus enters the trip at 10:00.
                check(
                                "Trip begins at 10:00",
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 9, 59),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)),
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 10, 0),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)));

                // Bus remains on the trip until 11:00.
                check(
                                "Trip ends and post-trip rest begins at 11:00",
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 10, 59),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)),
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 11, 0),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)));

                // Post-trip rest lasts until 12:00.
                check(
                                "Post-trip rest ends at 12:00",
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 11, 59),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)),
                                createTimedInput(
                                                LocalDateTime.of(2026, 11, 2, 12, 0),
                                                LocalTime.of(0, 0), LocalTime.of(0, 0),
                                                60, List.of(commitment)));
                System.out.println("\nAll fingerprint checks passed!");
        }
}