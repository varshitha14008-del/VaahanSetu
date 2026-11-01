package com.vaahansetu.model;

import java.time.LocalDateTime;

public class AllocationResult {

    private Long allocationRunId;
    private String status;
    private LocalDateTime allocationTime;
    private Integer totalDemand;
    private Integer totalAccommodated;
    private Integer totalUnmetDemand;
    private Integer totalUnusedCapacity;
    private String notes;

    public AllocationResult() {
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

    public Integer getTotalDemand() {
        return totalDemand;
    }

    public void setTotalDemand(Integer totalDemand) {
        this.totalDemand = totalDemand;
    }

    public Integer getTotalAccommodated() {
        return totalAccommodated;
    }

    public void setTotalAccommodated(Integer totalAccommodated) {
        this.totalAccommodated = totalAccommodated;
    }

    public Integer getTotalUnmetDemand() {
        return totalUnmetDemand;
    }

    public void setTotalUnmetDemand(Integer totalUnmetDemand) {
        this.totalUnmetDemand = totalUnmetDemand;
    }

    public Integer getTotalUnusedCapacity() {
        return totalUnusedCapacity;
    }

    public void setTotalUnusedCapacity(Integer totalUnusedCapacity) {
        this.totalUnusedCapacity = totalUnusedCapacity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
