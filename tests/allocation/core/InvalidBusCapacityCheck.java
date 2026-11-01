package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

public class InvalidBusCapacityCheck {
    public static void main(String[] args) {
        Bus bus = new Bus(101, "BUS-101", -5, "ACTIVE", 0);

        Input input = new Input(
                LocalDateTime.of(2026, 11, 2, 9, 0),
                List.of(),
                List.of(bus),
                List.of(),
                List.of(),
                List.of());

        try {
            AllocationEngine.allocate(input);
            throw new AssertionError("FAIL: Negative bus capacity was accepted.");
        } catch (IllegalArgumentException e) {
            System.out.println("PASS: Negative bus capacity rejected.");
            System.out.println("Reason: " + e.getMessage());
        }

        System.out.println("Invalid bus capacity test passed!");
    }
}