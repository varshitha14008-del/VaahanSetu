
package com.vaahansetu.model;

public class Bus {

    private Long busId;
    private String registrationNumber;
    private Integer capacity;
    private String status;
    private String currentLocation;
    private String notes;

    public Bus() {
    }

    public Long getBusId() {
        return busId;
    }

    public void setBusId(Long busId) {
        this.busId = busId;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(String currentLocation) {
        this.currentLocation = currentLocation;
    }

    private Integer restDurationMinutes;
    private java.time.LocalTime restStart;
    private java.time.LocalTime restEnd;

    public Integer getRestDurationMinutes() {
        return restDurationMinutes;
    }

    public void setRestDurationMinutes(Integer restDurationMinutes) {
        this.restDurationMinutes = restDurationMinutes;
    }

    public java.time.LocalTime getRestStart() {
        return restStart;
    }

    public void setRestStart(java.time.LocalTime restStart) {
        this.restStart = restStart;
    }

    public java.time.LocalTime getRestEnd() {
        return restEnd;
    }

    public void setRestEnd(java.time.LocalTime restEnd) {
        this.restEnd = restEnd;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
