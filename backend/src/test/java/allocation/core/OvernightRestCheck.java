
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class OvernightRestCheck {

    public static void main(String[] args) {

        Route route = new Route(
                1,
                "R1",
                "Central-Airport",
                List.of(1L, 2L));

        // Bus has a fixed overnight rest window: 22:00 to 06:00.
        Bus bus = new Bus(
                101,
                "BUS-101",
                50,
                "ACTIVE",
                "Central Depot",
                0,
                LocalTime.of(22, 0),
                LocalTime.of(6, 0),
                null,
                null,
                true);

        LocalDateTime date = LocalDateTime.of(2026, 11, 2, 0, 0);

        // Trip 1: inside the overnight rest window.
        Trip nightTrip = new Trip(
                1001,
                1,
                date.withHour(23),
                date.withHour(23).plusMinutes(30),
                null,
                0);

        // Trip 2: starts exactly when the rest window ends.
        Trip morningTrip = new Trip(
                1002,
                1,
                date.withHour(6),
                date.withHour(6).plusMinutes(30),
                null,
                0);

        Input input = new Input(
                date,
                List.of(route),
                List.of(bus),
                List.of(nightTrip, morningTrip),
                List.of(),
                List.of());

        Result result = AllocationEngine.allocate(input);

        boolean nightBlocked = false;
        boolean morningAllowed = false;

        for (TripOutcome outcome : result.outcomes()) {

            if (outcome.tripId() == 1001) {
                nightBlocked = outcome.proposedBusId() == null;
            }

            if (outcome.tripId() == 1002) {
                morningAllowed = outcome.proposedBusId() != null;
            }
        }

        if (nightBlocked) {
            System.out.println("PASS: Trip at 23:00 blocked during overnight rest.");
        } else {
            System.out.println("FAIL: Trip at 23:00 should have been blocked.");
        }

        if (morningAllowed) {
            System.out.println("PASS: Trip at 06:00 allowed at rest-window end.");
        } else {
            System.out.println("FAIL: Trip at 06:00 should have been allowed.");
        }

        if (nightBlocked && morningAllowed) {
            System.out.println("\nOvernight rest test passed!");
        } else {
            throw new AssertionError("Overnight rest test failed.");
        }
    }
}
