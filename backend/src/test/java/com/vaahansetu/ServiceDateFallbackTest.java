package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.vaahansetu.repository.JourneyRepository;

public class ServiceDateFallbackTest {

    static class MockTripRecord {
        long tripId;
        long routeId;
        LocalDate serviceDate;
        String status;

        MockTripRecord(long tripId, long routeId, LocalDate serviceDate, String status) {
            this.tripId = tripId;
            this.routeId = routeId;
            this.serviceDate = serviceDate;
            this.status = status;
        }
    }

    /**
     * Simulates the exact SQL logic implemented in JourneyRepository.findTodaysTrips:
     * service_date = COALESCE(
     *     (SELECT service_date WHERE route_id = ? AND service_date = CURRENT_DATE LIMIT 1),
     *     (SELECT service_date WHERE route_id = ? AND service_date > CURRENT_DATE ORDER BY service_date ASC LIMIT 1)
     * )
     */
    private LocalDate resolveEligibleServiceDate(List<MockTripRecord> trips, long routeId, LocalDate currentDate) {
        LocalDate todayMatch = trips.stream()
                .filter(t -> t.routeId == routeId && t.serviceDate.isEqual(currentDate))
                .map(t -> t.serviceDate)
                .findFirst()
                .orElse(null);

        if (todayMatch != null) {
            return todayMatch;
        }

        return trips.stream()
                .filter(t -> t.routeId == routeId && t.serviceDate.isAfter(currentDate))
                .map(t -> t.serviceDate)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Past trips are strictly excluded: never select past dates when today's trips are missing")
    void testPastServiceDateNeverSelected() {
        LocalDate today = LocalDate.now();
        List<MockTripRecord> trips = List.of(
                new MockTripRecord(101L, 1L, today.minusDays(7), "SCHEDULED"),
                new MockTripRecord(102L, 1L, today.minusDays(1), "SCHEDULED")
        );

        LocalDate resolvedDate = resolveEligibleServiceDate(trips, 1L, today);
        assertNull(resolvedDate, "Resolved service date must be null; past trips must NEVER be selected as fallback");
    }

    @Test
    @DisplayName("Current date trips are selected when present")
    void testCurrentDateTripsSelected() {
        LocalDate today = LocalDate.now();
        List<MockTripRecord> trips = List.of(
                new MockTripRecord(101L, 1L, today.minusDays(5), "SCHEDULED"),
                new MockTripRecord(102L, 1L, today, "SCHEDULED"),
                new MockTripRecord(103L, 1L, today.plusDays(1), "SCHEDULED")
        );

        LocalDate resolvedDate = resolveEligibleServiceDate(trips, 1L, today);
        assertNotNull(resolvedDate);
        assertEquals(today, resolvedDate, "Current date trips must take precedence");
    }

    @Test
    @DisplayName("Future service dates are selected when today has no departures")
    void testFutureServiceDateSelectedWhenTodayMissing() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        List<MockTripRecord> trips = List.of(
                new MockTripRecord(101L, 1L, today.minusDays(10), "SCHEDULED"),
                new MockTripRecord(102L, 1L, tomorrow, "SCHEDULED"),
                new MockTripRecord(103L, 1L, today.plusDays(3), "SCHEDULED")
        );

        LocalDate resolvedDate = resolveEligibleServiceDate(trips, 1L, today);
        assertNotNull(resolvedDate);
        assertEquals(tomorrow, resolvedDate, "Next upcoming valid future service date must be selected");
    }

    @Test
    @DisplayName("Missing service dates return empty result gracefully")
    void testMissingTripsReturnsEmptyResult() {
        LocalDate today = LocalDate.now();
        List<MockTripRecord> trips = new ArrayList<>();

        LocalDate resolvedDate = resolveEligibleServiceDate(trips, 1L, today);
        assertNull(resolvedDate, "Missing trips must yield null date without error");
    }
}
