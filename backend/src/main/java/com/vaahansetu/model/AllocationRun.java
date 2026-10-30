
package com.vaahansetu.model;

import java.time.LocalDateTime;

public class AllocationRun {

    private Long allocationRunId;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String status;
    private Integer totalDemand;
    private Integer totalAccommodated;
    private Integer totalUnmetDemand;
    private String notes;

    public AllocationRun() {
    }

    public Long getAllocationRunId() {
        return allocationRunId;
    }

    public void setAllocationRunId(Long allocationRunId) {
        this.allocationRunId = allocationRunId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
