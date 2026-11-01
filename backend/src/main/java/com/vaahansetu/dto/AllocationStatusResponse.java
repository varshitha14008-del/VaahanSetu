package com.vaahansetu.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class AllocationStatusResponse {

    private Long allocationRunId;
    private String status;
    private LocalDateTime allocationTime;
    private Map<String, Object> baselineMetrics;
    private Map<String, Object> proposedMetrics;
    private List<Map<String, Object>> tripAssignments;
    private List<Map<String, Object>> segmentResults;
    private List<Map<String, Object>> busReports;
    private String message;

    public AllocationStatusResponse() {
    }

    public Long getAllocationRunId() {
        return allocationRunId;
    }

    public void setAllocationRunId(Long allocationRunId) {
        this.allocationRunId = allocationRunId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getAllocationTime() {
        return allocationTime;
    }

    public void setAllocationTime(LocalDateTime allocationTime) {
        this.allocationTime = allocationTime;
    }

    public Map<String, Object> getBaselineMetrics() {
        return baselineMetrics;
    }

    public void setBaselineMetrics(Map<String, Object> baselineMetrics) {
        this.baselineMetrics = baselineMetrics;
    }

    public Map<String, Object> getProposedMetrics() {
        return proposedMetrics;
    }

    public void setProposedMetrics(Map<String, Object> proposedMetrics) {
        this.proposedMetrics = proposedMetrics;
    }

    public List<Map<String, Object>> getTripAssignments() {
        return tripAssignments;
    }

    public void setTripAssignments(List<Map<String, Object>> tripAssignments) {
        this.tripAssignments = tripAssignments;
    }

    public List<Map<String, Object>> getSegmentResults() {
        return segmentResults;
    }

    public void setSegmentResults(List<Map<String, Object>> segmentResults) {
        this.segmentResults = segmentResults;
    }

    public List<Map<String, Object>> getBusReports() {
        return busReports;
    }

    public void setBusReports(List<Map<String, Object>> busReports) {
        this.busReports = busReports;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
