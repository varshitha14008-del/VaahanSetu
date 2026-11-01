
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

public class DuplicateIdCheck {

    public static void main(String[] args) {

        Route route1 = new Route(
                1, "R1", "Central-Airport",
                List.of(1L, 2L));

        Route route2 = new Route(
                1, "R2", "EastGate-Lake",
                List.of(3L, 4L));

        Input input = new Input(
                LocalDateTime.of(2026, 11, 2, 9, 0),
                List.of(route1, route2),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        try {

            AllocationEngine.allocate(input);

            throw new AssertionError(
                    "FAIL: Duplicate route ID was accepted.");

        } catch (IllegalArgumentException e) {

            if (e.getMessage().contains("Duplicate route ID")) {
                System.out.println(
                        "PASS: Duplicate route ID rejected.");
            } else {
                throw new AssertionError(
                        "Unexpected error: " + e.getMessage());
            }
        }

        System.out.println("Duplicate ID test passed!");
    }
}
