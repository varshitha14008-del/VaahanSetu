
package com.vaahansetu.model;

import java.time.LocalDate;
import java.time.LocalTime;

public class ScheduledTrip {

    private Long tripId;
    private Long routeId;
    private LocalDate serviceDate;
    private LocalTime departureTime;
    private LocalTime expectedArrivalTime;
    private Integer requiredCapacity;
    private String status;

    public ScheduledTrip() {
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public void setServiceDate(LocalDate serviceDate) {
        this.serviceDate = serviceDate;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public LocalTime getExpectedArrivalTime() {
        return expectedArrivalTime;
    }

    public void setExpectedArrivalTime(LocalTime expectedArrivalTime) {
        this.expectedArrivalTime = expectedArrivalTime;
    }

    public Integer getRequiredCapacity() {
        return requiredCapacity;
    }

    public void setRequiredCapacity(Integer requiredCapacity) {
        this.requiredCapacity = requiredCapacity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
