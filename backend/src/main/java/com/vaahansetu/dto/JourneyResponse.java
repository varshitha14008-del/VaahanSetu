package com.vaahansetu.dto;

public class JourneyResponse {

    private Long journeyId;
    private String journeyStatus;
    private String message;

    public JourneyResponse() {
    }

    public JourneyResponse(Long journeyId, String journeyStatus, String message) {
        this.journeyId = journeyId;
        this.journeyStatus = journeyStatus;
        this.message = message;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public String getJourneyStatus() {
        return journeyStatus;
    }

    public void setJourneyStatus(String journeyStatus) {
        this.journeyStatus = journeyStatus;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
