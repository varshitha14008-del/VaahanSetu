package com.vaahansetu.service;

import java.time.LocalDate;
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

    public Map<String, Object> getJourneyById(Long journeyId) {
        return journeyRepository.findJourneyById(journeyId);
    }

    public List<Map<String, Object>> getPassengerDemand(Long sessionId) {
        return journeyRepository.findPassengerDemand(sessionId);
    }

    public List<Map<String, Object>> getTripPassengerDemand(Long sessionId, Long routeId, LocalDate serviceDate) {
        return journeyRepository.findTripPassengerDemand(sessionId, routeId, serviceDate);
    }

    public int updateStatus(Long journeyId, String status) {
        return journeyRepository.updateJourneyStatus(journeyId, status);
    }
}
