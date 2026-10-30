
package com.vaahansetu.model;

public class RouteStop {

    private Long routeId;
    private Long stopId;
    private Integer stopSequence;
    private Integer scheduledOffsetMinutes;

    public RouteStop() {
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getStopId() {
        return stopId;
    }

    public void setStopId(Long stopId) {
        this.stopId = stopId;
    }

    public Integer getStopSequence() {
        return stopSequence;
    }

    public void setStopSequence(Integer stopSequence) {
        this.stopSequence = stopSequence;
    }

    public Integer getScheduledOffsetMinutes() {
        return scheduledOffsetMinutes;
    }

    public void setScheduledOffsetMinutes(Integer scheduledOffsetMinutes) {
        this.scheduledOffsetMinutes = scheduledOffsetMinutes;
    }
}
