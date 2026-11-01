
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

public class DuplicateBusCheck {

    public static void main(String[] args) {

        Bus bus1 = new Bus(
                101, "BUS-101", 50, "ACTIVE", 0);

        Bus bus2 = new Bus(
                101, "BUS-102", 40, "ACTIVE", 0);

        Input input = new Input(
                LocalDateTime.of(2026, 11, 2, 9, 0),
                List.of(),
                List.of(bus1, bus2),
                List.of(),
                List.of(),
                List.of());

        try {

            AllocationEngine.allocate(input);

            throw new AssertionError(
                    "FAIL: Duplicate bus ID was accepted.");

        } catch (IllegalArgumentException e) {

            if (e.getMessage().contains("Duplicate bus ID")) {
                System.out.println(
                        "PASS: Duplicate bus ID rejected.");
            } else {
                throw new AssertionError(
                        "Unexpected error: " + e.getMessage());
            }
        }

        System.out.println("Duplicate bus test passed!");
    }
}
