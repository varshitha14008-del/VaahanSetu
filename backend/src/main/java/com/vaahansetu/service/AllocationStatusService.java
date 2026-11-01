package com.vaahansetu.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import allocation.core.AllocationEngine;
import allocation.core.AllocationModels;
import allocation.core.AllocationRepository;
import com.vaahansetu.dto.AllocationStatusResponse;

@Service
public class AllocationStatusService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final AllocationRepository allocationRepository;

    public AllocationStatusService(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.allocationRepository = new AllocationRepository(dataSource);
    }

    public Long getActiveSessionId() {
        String sql = "SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1";
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public AllocationStatusResponse executeAllocation(Long requestedSessionId) {
        Long sessionId = requestedSessionId;
        if (sessionId == null || sessionId <= 0) {
            sessionId = getActiveSessionId();
        }

        if (sessionId == null) {
            throw new IllegalStateException("No active planning session is available for allocation.");
        }

        try (Connection conn = allocationRepository.open()) {
            conn.setAutoCommit(false);
            try {
                // Concurrency control: acquire row-level lock on planning session to serialize execution
                try (java.sql.PreparedStatement lockStmt = conn.prepareStatement(
                        "SELECT session_id FROM planning_sessions WHERE session_id = ? FOR UPDATE")) {
                    lockStmt.setLong(1, sessionId);
                    try (java.sql.ResultSet rs = lockStmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("Planning session " + sessionId + " does not exist.");
                        }
                    }
                }

                LocalDateTime now = LocalDateTime.now();
                AllocationModels.Input input = allocationRepository.load(conn, sessionId, now);

                if (input.trips().isEmpty()) {
                    throw new IllegalStateException("No eligible trips found for allocation in session " + sessionId + ".");
                }

                AllocationModels.Result result = AllocationEngine.allocate(input);
                // Directly retrieve and associate this execution's unique generated proposal run ID
                long proposedRunId = allocationRepository.save(conn, sessionId, result);
                conn.commit();

                return mapToResponse(proposedRunId, "PROPOSED", result);

            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Allocation calculation failed: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during allocation: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> approveAndApplyProposal(Long runId, Long officerUserId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("Valid allocation run ID is required.");
        }

        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT allocation_run_id, status::text, session_id FROM allocation_runs WHERE allocation_run_id = ? FOR UPDATE",
                runId);

        if (runs.isEmpty()) {
            throw new IllegalArgumentException("Allocation run " + runId + " not found.");
        }

        String currentStatus = (String) runs.get(0).get("status");
        if (!"PROPOSED".equalsIgnoreCase(currentStatus)) {
            throw new IllegalStateException("Only PROPOSED runs can be approved. Current status is " + currentStatus + ".");
        }

        List<Map<String, Object>> assignments = jdbcTemplate.queryForList(
                "SELECT trip_id, previous_bus_id, proposed_bus_id, assignment_type::text " +
                "FROM allocation_assignments WHERE allocation_run_id = ?", runId);

        int reassignedCount = 0;
        int unchangedCount = 0;
        int unassignedCount = 0;

        for (Map<String, Object> row : assignments) {
            Long tripId = ((Number) row.get("trip_id")).longValue();
            Long proposedBusId = row.get("proposed_bus_id") != null
                    ? ((Number) row.get("proposed_bus_id")).longValue()
                    : null;
            String type = (String) row.get("assignment_type");

            if ("UNASSIGNED".equalsIgnoreCase(type) || proposedBusId == null) {
                // Trip has no bus
                jdbcTemplate.update(
                        "UPDATE trip_assignments SET status = 'CANCELLED' WHERE trip_id = ? AND status = 'ACTIVE'",
                        tripId);
                unassignedCount++;
            } else if ("REASSIGNED".equalsIgnoreCase(type)) {
                // Mark prior active assignment REPLACED
                jdbcTemplate.update(
                        "UPDATE trip_assignments SET status = 'REPLACED' WHERE trip_id = ? AND status = 'ACTIVE'",
                        tripId);

                // Insert new active assignment
                jdbcTemplate.update(
                        "INSERT INTO trip_assignments (trip_id, bus_id, assigned_by, status) VALUES (?, ?, ?, 'ACTIVE')",
                        tripId, proposedBusId, officerUserId);
                reassignedCount++;
            } else {
                // UNCHANGED: ensure active record exists
                unchangedCount++;
            }
        }

        // Mark run as APPROVED
        jdbcTemplate.update(
                "UPDATE allocation_runs SET status = 'APPROVED', notes = COALESCE(notes, '') || ' [APPROVED by user ' || COALESCE(?::text, 'system') || ']' WHERE allocation_run_id = ?",
                officerUserId, runId);

        return Map.of(
                "message", "Allocation proposal approved and applied successfully.",
                "allocationRunId", runId,
                "status", "APPROVED",
                "reassignedTrips", reassignedCount,
                "unchangedTrips", unchangedCount,
                "unassignedTrips", unassignedCount);
    }

    public AllocationStatusResponse getLatestAllocation(Long requestedSessionId) {
        Long sessionId = requestedSessionId != null && requestedSessionId > 0 ? requestedSessionId : getActiveSessionId();
        if (sessionId == null) {
            throw new IllegalStateException("No active planning session found.");
        }

        List<Long> runIds = jdbcTemplate.queryForList(
                "SELECT allocation_run_id FROM allocation_runs WHERE session_id = ? ORDER BY allocation_run_id DESC LIMIT 1",
                Long.class, sessionId);

        if (runIds.isEmpty()) {
            return null;
        }

        return getAllocationRunDetails(runIds.get(0));
    }

    public AllocationStatusResponse getAllocationRunDetails(Long runId) {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT allocation_run_id, session_id, status::text, total_demand, total_accommodated, " +
                "total_unmet_demand, total_unused_capacity, created_at, notes FROM allocation_runs WHERE allocation_run_id = ?",
                runId);

        if (runs.isEmpty()) {
            throw new IllegalArgumentException("Allocation run " + runId + " not found.");
        }

        Map<String, Object> run = runs.get(0);
        Long sessionId = ((Number) run.get("session_id")).longValue();

        // Baseline run (APPROVED)
        List<Map<String, Object>> baselineRuns = jdbcTemplate.queryForList(
                "SELECT total_demand, total_accommodated, total_unmet_demand, total_unused_capacity " +
                "FROM allocation_runs WHERE session_id = ? AND status = 'APPROVED' AND notes LIKE 'AUTO: baseline%' ORDER BY allocation_run_id DESC LIMIT 1",
                sessionId);

        Map<String, Object> baselineMetrics = baselineRuns.isEmpty() ? Map.of() : Map.of(
                "demand", baselineRuns.get(0).get("total_demand"),
                "accommodated", baselineRuns.get(0).get("total_accommodated"),
                "unmet", baselineRuns.get(0).get("total_unmet_demand"),
                "unused", baselineRuns.get(0).get("total_unused_capacity"));

        Map<String, Object> proposedMetrics = Map.of(
                "demand", run.get("total_demand"),
                "accommodated", run.get("total_accommodated"),
                "unmet", run.get("total_unmet_demand"),
                "unused", run.get("total_unused_capacity"));

        // Assignments
        String assignSql = """
                SELECT aa.trip_id AS "tripId",
                       r.route_code AS "routeCode",
                       st.departure_time AS "departureTime",
                       aa.previous_bus_id AS "previousBusId",
                       pb.registration_number AS "previousBusReg",
                       aa.proposed_bus_id AS "proposedBusId",
                       nb.registration_number AS "proposedBusReg",
                       aa.assignment_type::text AS "assignmentType",
                       aa.reason
                FROM allocation_assignments aa
                JOIN scheduled_trips st ON st.trip_id = aa.trip_id
                JOIN routes r ON r.route_id = st.route_id
                LEFT JOIN buses pb ON pb.bus_id = aa.previous_bus_id
                LEFT JOIN buses nb ON nb.bus_id = aa.proposed_bus_id
                WHERE aa.allocation_run_id = ?
                ORDER BY st.departure_time, aa.trip_id
                """;
        List<Map<String, Object>> assignments = jdbcTemplate.queryForList(assignSql, runId);

        // Segments
        String segSql = """
                SELECT asr.route_id AS "routeId",
                       r.route_code AS "routeCode",
                       s1.stop_name AS "startStop",
                       s2.stop_name AS "endStop",
                       asr.demand,
                       asr.allocated_capacity AS "capacity",
                       asr.accommodated,
                       asr.unmet_demand AS "unmet",
                       asr.unused_capacity AS "unused"
                FROM allocation_segment_results asr
                JOIN routes r ON r.route_id = asr.route_id
                JOIN stops s1 ON s1.stop_id = asr.segment_start_stop_id
                JOIN stops s2 ON s2.stop_id = asr.segment_end_stop_id
                WHERE asr.allocation_run_id = ?
                ORDER BY r.route_id, asr.segment_start_stop_id
                """;
        List<Map<String, Object>> segments = jdbcTemplate.queryForList(segSql, runId);

        AllocationStatusResponse resp = new AllocationStatusResponse();
        resp.setAllocationRunId(runId);
        resp.setStatus((String) run.get("status"));
        resp.setBaselineMetrics(baselineMetrics);
        resp.setProposedMetrics(proposedMetrics);
        resp.setTripAssignments(assignments);
        resp.setSegmentResults(segments);
        resp.setMessage("Retrieved allocation run " + runId + ".");
        return resp;
    }

    private AllocationStatusResponse mapToResponse(Long runId, String status, AllocationModels.Result result) {
        AllocationStatusResponse resp = new AllocationStatusResponse();
        resp.setAllocationRunId(runId);
        resp.setStatus(status);
        resp.setAllocationTime(result.time());

        resp.setBaselineMetrics(Map.of(
                "demand", result.beforeTotal().demand(),
                "capacity", result.beforeTotal().capacity(),
                "accommodated", result.beforeTotal().accommodated(),
                "unmet", result.beforeTotal().unmet(),
                "unused", result.beforeTotal().unused()));

        resp.setProposedMetrics(Map.of(
                "demand", result.afterTotal().demand(),
                "capacity", result.afterTotal().capacity(),
                "accommodated", result.afterTotal().accommodated(),
                "unmet", result.afterTotal().unmet(),
                "unused", result.afterTotal().unused()));

        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (AllocationModels.TripOutcome o : result.outcomes()) {
            Map<String, Object> map = new HashMap<>();
            map.put("tripId", o.tripId());
            map.put("routeId", o.routeId());
            map.put("previousBusId", o.previousBusId());
            map.put("proposedBusId", o.proposedBusId());
            map.put("assignmentType", o.type());
            map.put("reason", o.reason());
            outcomes.add(map);
        }
        resp.setTripAssignments(outcomes);

        List<Map<String, Object>> segs = new ArrayList<>();
        for (AllocationModels.SegmentRow s : result.afterSegments()) {
            Map<String, Object> map = new HashMap<>();
            map.put("routeId", s.routeId());
            map.put("startStopId", s.startStopId());
            map.put("endStopId", s.endStopId());
            map.put("demand", s.demand());
            map.put("capacity", s.capacity());
            map.put("accommodated", s.accommodated());
            map.put("unmet", s.unmet());
            map.put("unused", s.unused());
            segs.add(map);
        }
        resp.setSegmentResults(segs);

        List<Map<String, Object>> busReps = new ArrayList<>();
        for (AllocationModels.BusReport b : result.buses()) {
            busReps.add(Map.of(
                    "busId", b.busId(),
                    "regNo", b.regNo(),
                    "capacity", b.capacity(),
                    "originalRoutes", b.originalRoutes() != null ? b.originalRoutes() : "",
                    "newRoutes", b.newRoutes() != null ? b.newRoutes() : "",
                    "availableAtTime", b.availableAtTime(),
                    "unavailableReason", b.unavailableReason() != null ? b.unavailableReason() : ""));
        }
        resp.setBusReports(busReps);
        resp.setMessage("Allocation run completed successfully.");
        return resp;
    }
}
