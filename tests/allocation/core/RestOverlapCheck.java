
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class RestOverlapCheck {

        public static void main(String[] args) {

                Route route = new Route(
                                1, "R1", "Central-Airport",
                                List.of(1L, 2L));

                Bus bus = new Bus(
                                101, "BUS-101", 50, "ACTIVE",
                                "Central Depot", 0,
                                LocalTime.of(22, 0),
                                LocalTime.of(6, 0),
                                null, null, true);

                LocalDateTime departure = LocalDateTime.of(2026, 11, 2, 21, 30);

                Trip trip = new Trip(
                                1003, 1,
                                departure,
                                departure.plusHours(1),
                                null, 0);

                Input input = new Input(
                                departure,
                                List.of(route),
                                List.of(bus),
                                List.of(trip),
                                List.of(),
                                List.of());

                Result result = AllocationEngine.allocate(input);

                TripOutcome outcome = result.outcomes().get(0);

                if (outcome.proposedBusId() == null) {
                        System.out.println(
                                        "PASS: Trip from 21:30 to 22:30 blocked.");
                } else {
                        throw new AssertionError(
                                        "FAIL: Trip overlaps fixed rest but was assigned.");
                }

                System.out.println("Rest overlap test passed!");
        }
}
