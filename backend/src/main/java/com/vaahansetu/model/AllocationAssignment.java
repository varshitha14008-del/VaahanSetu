
package com.vaahansetu.model;

public class AllocationAssignment {

    private Long allocationAssignmentId;
    private Long allocationRunId;
    private Long tripId;
    private Long previousBusId;
    private Long proposedBusId;
    private String assignmentType;
    private String reason;

    public AllocationAssignment() {
    }

    public Long getAllocationAssignmentId() {
        return allocationAssignmentId;
    }

    public void setAllocationAssignmentId(Long allocationAssignmentId) {
        this.allocationAssignmentId = allocationAssignmentId;
    }

    public Long getAllocationRunId() {
        return allocationRunId;
    }

    public void setAllocationRunId(Long allocationRunId) {
        this.allocationRunId = allocationRunId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getPreviousBusId() {
        return previousBusId;
    }

    public void setPreviousBusId(Long previousBusId) {
        this.previousBusId = previousBusId;
    }

    public Long getProposedBusId() {
        return proposedBusId;
    }

    public void setProposedBusId(Long proposedBusId) {
        this.proposedBusId = proposedBusId;
    }

    public String getAssignmentType() {
        return assignmentType;
    }

    public void setAssignmentType(String assignmentType) {
        this.assignmentType = assignmentType;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
