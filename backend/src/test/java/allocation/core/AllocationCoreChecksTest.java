package allocation.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class AllocationCoreChecksTest {

    @Test
    @DisplayName("Verify AllocationEngineCheck passes all assertions")
    void testAllocationEngineCheck() {
        assertDoesNotThrow(() -> AllocationEngineCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify InputFingerprintCheck passes all assertions")
    void testInputFingerprintCheck() {
        assertDoesNotThrow(() -> InputFingerprintCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify OvernightRestCheck passes all assertions")
    void testOvernightRestCheck() {
        assertDoesNotThrow(() -> OvernightRestCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify RestOverlapCheck passes all assertions")
    void testRestOverlapCheck() {
        assertDoesNotThrow(() -> RestOverlapCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify MultiDayRestCheck passes all assertions")
    void testMultiDayRestCheck() {
        assertDoesNotThrow(() -> MultiDayRestCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify InvalidBusCapacityCheck passes all assertions")
    void testInvalidBusCapacityCheck() {
        assertDoesNotThrow(() -> InvalidBusCapacityCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify InvalidRestDurationCheck passes all assertions")
    void testInvalidRestDurationCheck() {
        assertDoesNotThrow(() -> InvalidRestDurationCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify InvalidRouteCheck passes all assertions")
    void testInvalidRouteCheck() {
        assertDoesNotThrow(() -> InvalidRouteCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify DuplicateBusCheck passes all assertions")
    void testDuplicateBusCheck() {
        assertDoesNotThrow(() -> DuplicateBusCheck.main(new String[0]));
    }

    @Test
    @DisplayName("Verify DuplicateIdCheck passes all assertions")
    void testDuplicateIdCheck() {
        assertDoesNotThrow(() -> DuplicateIdCheck.main(new String[0]));
    }
}
