package com.vaahansetu.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.vaahansetu.repository.JourneyRepository;

@Service
public class JourneyService {

    private final JourneyRepository journeyRepository;

    public JourneyService(JourneyRepository journeyRepository) {
        this.journeyRepository = journeyRepository;
    }

    public List<Map<String, Object>> findTodaysTrips(Long routeId) {
        return journeyRepository.findTodaysTrips(routeId);
    }

    public Long findActiveSessionId() {
        return journeyRepository.findActiveSessionId();
    }

    public List<Long> findStopOrder(Long routeId) {
        return journeyRepository.findStopOrder(routeId);
    }

    public Long saveJourney(Long sessionId, Long tripId, Long boardingStopId, Long destinationStopId, Integer passengerCount) {
        return journeyRepository.saveJourney(sessionId, tripId, boardingStopId, destinationStopId, passengerCount);
    }

    public Map<String, Object> findJourneyById(Long journeyId) {
        return journeyRepository.findJourneyById(journeyId);
    }

    public List<Map<String, Object>> findPassengerDemand(Long sessionId) {
        return journeyRepository.findPassengerDemand(sessionId);
    }

    public int updateJourneyStatus(Long journeyId, String status) {
        return journeyRepository.updateJourneyStatus(journeyId, status);
    }
}
