package com.vaahansetu.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journeys")
@CrossOrigin(origins = "*")
public class JourneyController {

    private final PassengerJourneyController passengerJourneyController;

    public JourneyController(PassengerJourneyController passengerJourneyController) {
        this.passengerJourneyController = passengerJourneyController;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> bookJourney(@RequestBody Map<String, Object> request) {
        return passengerJourneyController.registerJourney(request);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getJourney(@PathVariable Long id) {
        return passengerJourneyController.getJourney(id);
    }
}
