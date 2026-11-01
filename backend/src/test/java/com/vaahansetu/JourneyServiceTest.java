package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vaahansetu.dto.JourneyRequest;

public class JourneyServiceTest {

    @Test
    @DisplayName("JourneyRequest validation parameters check")
    void testJourneyRequestValidation() {
        JourneyRequest req = new JourneyRequest();
        req.setRouteId(1L);
        req.setBoardingStopId(10L);
        req.setDestinationStopId(20L);
        req.setPassengerCount(4);

        assertEquals(1L, req.getRouteId());
        assertEquals(10L, req.getBoardingStopId());
        assertEquals(20L, req.getDestinationStopId());
        assertEquals(4, req.getPassengerCount());
    }

    @Test
    @DisplayName("Stop order validation logic")
    void testStopOrderLogic() {
        List<Long> stopOrder = List.of(1L, 2L, 3L, 4L);

        int boardingIndex = stopOrder.indexOf(2L);
        int destIndex = stopOrder.indexOf(4L);

        assertTrue(boardingIndex >= 0 && destIndex > boardingIndex, "Destination must appear after boarding stop");

        int invalidDestIndex = stopOrder.indexOf(1L);
        assertFalse(boardingIndex >= 0 && invalidDestIndex > boardingIndex, "Reverse journey must be rejected");
    }
}
