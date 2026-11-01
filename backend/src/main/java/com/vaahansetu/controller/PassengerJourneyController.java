
package com.vaahansetu.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaahansetu.repository.JourneyRepository;

@RestController
@RequestMapping("/api/passenger-journeys")
@CrossOrigin(origins = "*")
public class PassengerJourneyController {

    private final JourneyRepository journeyRepository;

    public PassengerJourneyController(
            JourneyRepository journeyRepository) {
        this.journeyRepository = journeyRepository;
    }

    // Register a passenger journey
    @PostMapping
    public ResponseEntity<Map<String, Object>> registerJourney(
            @RequestBody Map<String, Object> request) {

        if (request == null) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Please provide journey details."
            );
        }

        Long routeId = toLong(request.get("routeId"));
        Long boardingStopId =
                toLong(request.get("boardingStopId"));
        Long destinationStopId =
                toLong(request.get("destinationStopId"));
        Integer passengerCount =
                toInteger(request.get("passengerCount"));

        // Validate required fields
        if (routeId == null
                || boardingStopId == null
                || destinationStopId == null
                || passengerCount == null) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    "Please provide all journey details."
            );
        }

        // Validate passenger count
        if (passengerCount < 1 || passengerCount > 10) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Passenger count must be between 1 and 10."
            );
        }

        // Validate route stops and their order
        List<Long> stopOrder =
                journeyRepository.findStopOrder(routeId);

        int boardingIndex =
                stopOrder.indexOf(boardingStopId);

        int destinationIndex =
                stopOrder.indexOf(destinationStopId);

        if (boardingIndex == -1 || destinationIndex == -1) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "The selected stops do not belong to this route."
            );
        }

        if (boardingIndex >= destinationIndex) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Destination stop must be after the boarding stop."
            );
        }

        // Find today's trips with active bus allocations
        List<Map<String, Object>> trips =
                journeyRepository.findTodaysTrips(routeId);

        if (trips.isEmpty()) {
            return error(
                    HttpStatus.CONFLICT,
                    "No buses available at the moment. Please wait..."
            );
        }

        // Use the first eligible scheduled trip
        Long tripId = toLong(trips.get(0).get("tripId"));

        if (tripId == null) {
            return error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to identify the scheduled trip."
            );
        }

        // Find the active planning session
        Long sessionId =
                journeyRepository.findActiveSessionId();

        if (sessionId == null) {
            return error(
                    HttpStatus.CONFLICT,
                    "No active planning session is available at the moment."
            );
        }

        // Save journey in the database
        Long journeyId = journeyRepository.saveJourney(
                sessionId,
                tripId,
                boardingStopId,
                destinationStopId,
                passengerCount
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                Map.of(
                        "message",
                        "Your journey has been registered successfully.",
                        "journeyId",
                        journeyId,
                        "journeyStatus",
                        "REGISTERED"
                )
        );
    }

    // Get journey details by ID
    @GetMapping("/{journeyId}")
    public ResponseEntity<Map<String, Object>> getJourney(
            @PathVariable Long journeyId) {

        Map<String, Object> journey =
                journeyRepository.findJourneyById(journeyId);

        if (journey == null) {
            return error(
                    HttpStatus.NOT_FOUND,
                    "Journey not found."
            );
        }

        return ResponseEntity.ok(journey);
    }

    // Get passenger demand for the active planning session
    @GetMapping("/demand")
    public ResponseEntity<Map<String, Object>> getPassengerDemand() {

        Long sessionId =
                journeyRepository.findActiveSessionId();

        if (sessionId == null) {
            return ResponseEntity.ok(Map.of());
        }

        List<Map<String, Object>> demandList =
                journeyRepository.findPassengerDemand(sessionId);

        Map<String, Object> demandMap = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : demandList) {
            Object routeId = row.get("routeId");
            Object count = row.get("passengerCount");
            if (routeId != null) {
                demandMap.put(String.valueOf(routeId), count != null ? Long.valueOf(count.toString()) : 0L);
            }
        }

        return ResponseEntity.ok(demandMap);
    }

    // Update journey status
    @PatchMapping("/{journeyId}/status")
    public ResponseEntity<Map<String, Object>> updateJourneyStatus(
            @PathVariable Long journeyId,
            @RequestBody Map<String, Object> request) {

        if (request == null || request.get("status") == null) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Please provide a journey status."
            );
        }

        String status = request.get("status")
                .toString()
                .trim()
                .toUpperCase();

        List<String> allowedStatuses = List.of(
                "REGISTERED",
                "BOARDED",
                "COMPLETED",
                "CANCELLED"
        );

        if (!allowedStatuses.contains(status)) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "Invalid journey status."
            );
        }

        Map<String, Object> existingJourney =
                journeyRepository.findJourneyById(journeyId);

        if (existingJourney == null) {
            return error(
                    HttpStatus.NOT_FOUND,
                    "Journey not found."
            );
        }

        int updatedRows =
                journeyRepository.updateJourneyStatus(
                        journeyId,
                        status
                );

        if (updatedRows == 0) {
            return error(
                    HttpStatus.NOT_FOUND,
                    "Journey status could not be updated."
            );
        }

        return ResponseEntity.ok(
                Map.of(
                        "message",
                        "Journey status updated successfully.",
                        "journeyId",
                        journeyId,
                        "journeyStatus",
                        status
                )
        );
    }

    // Safely convert a value to Long
    private Long toLong(Object value) {

        if (value == null) {
            return null;
        }

        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // Safely convert a value to Integer
    private Integer toInteger(Object value) {

        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // Standard error response
    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String message) {

        return ResponseEntity.status(status).body(
                Map.of("message", message)
        );
    }
}
