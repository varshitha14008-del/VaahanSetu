
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

public class InvalidRouteCheck {

    public static void main(String[] args) {

        LocalDateTime departure = LocalDateTime.of(2026, 11, 2, 9, 0);

        Trip trip = new Trip(
                1005,
                99, // Route 99 does not exist in the input.
                departure,
                departure.plusHours(1),
                null,
                0);

        Input input = new Input(
                departure,
                List.of(),
                List.of(),
                List.of(trip),
                List.of(),
                List.of());

        try {

            AllocationEngine.allocate(input);

            throw new AssertionError(
                    "FAIL: Missing route was accepted.");

        } catch (IllegalArgumentException e) {

            if (e.getMessage().contains("missing or inactive route")) {
                System.out.println(
                        "PASS: Missing route rejected with a clear error.");
            } else {
                throw new AssertionError(
                        "Unexpected validation error: " + e.getMessage());
            }
        }

        System.out.println("Invalid route test passed!");
    }
}
