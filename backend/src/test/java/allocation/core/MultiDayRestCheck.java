
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class MultiDayRestCheck {

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

                LocalDateTime departure = LocalDateTime.of(2026, 11, 2, 21, 0);

                LocalDateTime arrival = LocalDateTime.of(2026, 11, 3, 7, 0);

                Trip trip = new Trip(
                                1004, 1, departure, arrival, null, 0);

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
                                        "PASS: Multi-day trip blocked due to overnight rest.");
                } else {
                        throw new AssertionError(
                                        "FAIL: Multi-day trip incorrectly assigned.");
                }

                System.out.println("Multi-day rest test passed!");
        }
}
