package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.List;

public class InvalidRestDurationCheck {
    public static void main(String[] args) {
        Bus bus = new Bus(101, "BUS-101", 50, "ACTIVE", -1);

        Input input = new Input(
                LocalDateTime.of(2026, 11, 2, 9, 0),
                List.of(),
                List.of(bus),
                List.of(),
                List.of(),
                List.of());

        try {
            AllocationEngine.allocate(input);
            throw new AssertionError("FAIL: Negative rest duration was accepted.");
        } catch (IllegalArgumentException e) {
            System.out.println("PASS: Negative rest duration rejected.");
            System.out.println("Reason: " + e.getMessage());
        }

        System.out.println("Invalid rest duration test passed!");
    }
}