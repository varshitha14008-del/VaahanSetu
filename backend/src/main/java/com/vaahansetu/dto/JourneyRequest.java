
package com.vaahansetu.dto;

public class JourneyRequest {

    private Long routeId;
    private Long tripId;
    private Long boardingStopId;
    private Long destinationStopId;
    private Integer passengerCount;
    private Long sessionId;

    public JourneyRequest() {
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getBoardingStopId() {
        return boardingStopId;
    }

    public void setBoardingStopId(Long boardingStopId) {
        this.boardingStopId = boardingStopId;
    }

    public Long getDestinationStopId() {
        return destinationStopId;
    }

    public void setDestinationStopId(Long destinationStopId) {
        this.destinationStopId = destinationStopId;
    }

    public Integer getPassengerCount() {
        return passengerCount;
    }

    public void setPassengerCount(Integer passengerCount) {
        this.passengerCount = passengerCount;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }
}
