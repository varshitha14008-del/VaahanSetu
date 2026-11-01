package com.vaahansetu.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.TokenSession;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class OfficerController {

    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final Random random = new Random();

    // Elapsed simulation minutes counter for active session
    private int simulationElapsedMinutes = 0;

    public OfficerController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    // -------------------------------------------------------------
    // FLEET MANAGEMENT
    // -------------------------------------------------------------

    @GetMapping("/officer/buses")
    public ResponseEntity<List<Map<String, Object>>> getFleet(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireOfficer(authHeader);

        String sql = """
                SELECT bus_id AS "busId",
                       registration_number AS "registrationNumber",
                       capacity,
                       status::text AS "status",
                       current_location AS "currentLocation",
                       rest_duration_minutes AS "restDurationMinutes",
                       rest_start AS "restStart",
                       rest_end AS "restEnd",
                       notes,
                       created_at AS "createdAt"
                FROM buses
                ORDER BY bus_id
                """;

        return ResponseEntity.ok(jdbcTemplate.queryForList(sql));
    }

    @PatchMapping("/officer/buses/{busId}/status")
    public ResponseEntity<Map<String, Object>> updateBusStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long busId,
            @RequestBody Map<String, String> body) {
        authService.requireOfficer(authHeader);

        if (body == null || body.get("status") == null) {
            throw new IllegalArgumentException("Status value is required.");
        }

        String newStatus = body.get("status").trim().toUpperCase();
        if (!List.of("ACTIVE", "MAINTENANCE", "INACTIVE").contains(newStatus)) {
            throw new IllegalArgumentException("Invalid bus status: " + newStatus);
        }

        int updated = jdbcTemplate.update(
                "UPDATE buses SET status = ?::bus_status WHERE bus_id = ?",
                newStatus, busId);

        if (updated == 0) {
            throw new IllegalArgumentException("Bus with ID " + busId + " not found.");
        }

        return ResponseEntity.ok(Map.of(
                "message", "Bus status updated successfully.",
                "busId", busId,
                "status", newStatus));
    }

    // -------------------------------------------------------------
    // ROUTE & STOP MANAGEMENT
    // -------------------------------------------------------------

    @GetMapping("/officer/routes")
    public ResponseEntity<List<Map<String, Object>>> getRoutes(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireOfficer(authHeader);

        String routeSql = """
                SELECT r.route_id AS "routeId",
                       r.route_code AS "routeCode",
                       r.route_name AS "routeName",
                       r.origin_name AS "originName",
                       r.destination_name AS "destinationName",
                       r.estimated_duration_minutes AS "estimatedDurationMinutes",
                       r.is_active AS "isActive"
                FROM routes r
                ORDER BY r.route_id
                """;
        List<Map<String, Object>> routes = jdbcTemplate.queryForList(routeSql);

        String stopSql = """
                SELECT rs.route_id AS "routeId",
                       s.stop_id AS "stopId",
                       s.stop_name AS "stopName",
                       rs.stop_sequence AS "stopSequence",
                       rs.scheduled_offset_minutes AS "scheduledOffsetMinutes"
                FROM route_stops rs
                JOIN stops s ON s.stop_id = rs.stop_id
                ORDER BY rs.route_id, rs.stop_sequence
                """;
        List<Map<String, Object>> allStops = jdbcTemplate.queryForList(stopSql);

        Map<Long, List<Map<String, Object>>> stopsByRoute = new HashMap<>();
        for (Map<String, Object> stop : allStops) {
            Long rId = ((Number) stop.get("routeId")).longValue();
            stopsByRoute.computeIfAbsent(rId, k -> new ArrayList<>()).add(stop);
        }

        for (Map<String, Object> route : routes) {
            Long rId = ((Number) route.get("routeId")).longValue();
            route.put("stops", stopsByRoute.getOrDefault(rId, List.of()));
        }

        return ResponseEntity.ok(routes);
    }

    // -------------------------------------------------------------
    // SCHEDULED TRIPS & LIVE ASSIGNMENTS
    // -------------------------------------------------------------

    @GetMapping("/officer/trips")
    public ResponseEntity<List<Map<String, Object>>> getScheduledTrips(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireOfficer(authHeader);

        String sql = """
                SELECT st.trip_id AS "tripId",
                       st.route_id AS "routeId",
                       r.route_code AS "routeCode",
                       r.route_name AS "routeName",
                       st.service_date AS "serviceDate",
                       st.departure_time AS "departureTime",
                       st.expected_arrival_time AS "expectedArrivalTime",
                       st.status::text AS "tripStatus",
                       b.bus_id AS "currentBusId",
                       b.registration_number AS "currentBusReg",
                       b.capacity AS "currentBusCapacity",
                       b.status::text AS "currentBusStatus",
                       b.rest_duration_minutes AS "restDurationMinutes",
                       b.rest_start AS "restStart",
                       b.rest_end AS "restEnd"
                FROM scheduled_trips st
                JOIN routes r ON r.route_id = st.route_id
                LEFT JOIN trip_assignments ta ON ta.trip_id = st.trip_id AND ta.status = 'ACTIVE'
                LEFT JOIN buses b ON b.bus_id = ta.bus_id
                WHERE st.service_date = COALESCE(
                        (SELECT service_date FROM scheduled_trips WHERE service_date = CURRENT_DATE LIMIT 1),
                        (SELECT service_date FROM scheduled_trips ORDER BY service_date DESC LIMIT 1)
                )
                ORDER BY st.departure_time, st.trip_id
                """;

        return ResponseEntity.ok(jdbcTemplate.queryForList(sql));
    }

    // -------------------------------------------------------------
    // PLANNING SESSION & SELECTION POOL
    // -------------------------------------------------------------

    @GetMapping("/officer/sessions/active")
    public ResponseEntity<Map<String, Object>> getActiveSession(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireOfficer(authHeader);

        String sql = """
                SELECT session_id AS "sessionId",
                       session_name AS "sessionName",
                       started_at AS "startedAt",
                       status::text AS "status"
                FROM planning_sessions
                WHERE status = 'ACTIVE'
                ORDER BY started_at DESC
                LIMIT 1
                """;
        List<Map<String, Object>> sessions = jdbcTemplate.queryForList(sql);
        if (sessions.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        Map<String, Object> session = sessions.get(0);
        Long sessionId = ((Number) session.get("sessionId")).longValue();

        List<Long> selectedRouteIds = jdbcTemplate.queryForList(
                "SELECT route_id FROM session_routes WHERE session_id = ? ORDER BY route_id",
                Long.class, sessionId);

        List<Long> selectedBusIds = jdbcTemplate.queryForList(
                "SELECT bus_id FROM session_buses WHERE session_id = ? ORDER BY bus_id",
                Long.class, sessionId);

        session.put("selectedRouteIds", selectedRouteIds);
        session.put("selectedBusIds", selectedBusIds);
        session.put("simulationElapsedMinutes", simulationElapsedMinutes);

        return ResponseEntity.ok(session);
    }

    @PutMapping("/officer/sessions/{sessionId}/selection")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateSessionSelection(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long sessionId,
            @RequestBody Map<String, List<Long>> payload) {
        authService.requireOfficer(authHeader);

        List<Long> routeIds = payload != null ? payload.get("routeIds") : List.of();
        List<Long> busIds = payload != null ? payload.get("busIds") : List.of();

        if (routeIds != null) {
            jdbcTemplate.update("DELETE FROM session_routes WHERE session_id = ?", sessionId);
            for (Long rId : routeIds) {
                jdbcTemplate.update("INSERT INTO session_routes (session_id, route_id) VALUES (?, ?)", sessionId, rId);
            }
        }

        if (busIds != null) {
            jdbcTemplate.update("DELETE FROM session_buses WHERE session_id = ?", sessionId);
            for (Long bId : busIds) {
                jdbcTemplate.update("INSERT INTO session_buses (session_id, bus_id) VALUES (?, ?)", sessionId, bId);
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Session selections updated successfully.",
                "sessionId", sessionId,
                "selectedRouteCount", routeIds != null ? routeIds.size() : 0,
                "selectedBusCount", busIds != null ? busIds.size() : 0));
    }

    // -------------------------------------------------------------
    // SEGMENT-LEVEL DEMAND BREAKDOWN
    // -------------------------------------------------------------

    @GetMapping("/officer/trips/{tripId}/demand")
    public ResponseEntity<List<Map<String, Object>>> getTripSegmentDemand(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long tripId) {
        authService.requireOfficer(authHeader);

        // Retrieve route for this trip and current bus capacity
        List<Map<String, Object>> tripRows = jdbcTemplate.queryForList("""
                SELECT st.route_id AS "routeId",
                       r.route_code AS "routeCode",
                       st.departure_time AS "departureTime",
                       COALESCE(b.capacity, 0) AS "currentCapacity"
                FROM scheduled_trips st
                JOIN routes r ON r.route_id = st.route_id
                LEFT JOIN trip_assignments ta ON ta.trip_id = st.trip_id AND ta.status = 'ACTIVE'
                LEFT JOIN buses b ON b.bus_id = ta.bus_id
                WHERE st.trip_id = ?
                """, tripId);

        if (tripRows.isEmpty()) {
            throw new IllegalArgumentException("Trip " + tripId + " not found.");
        }

        Long routeId = ((Number) tripRows.get(0).get("routeId")).longValue();
        String routeCode = (String) tripRows.get(0).get("routeCode");
        int capacity = ((Number) tripRows.get(0).get("currentCapacity")).intValue();

        // Get ordered stops for this route
        String stopSql = """
                SELECT s.stop_id AS "stopId", s.stop_name AS "stopName", rs.stop_sequence AS "seq"
                FROM route_stops rs
                JOIN stops s ON s.stop_id = rs.stop_id
                WHERE rs.route_id = ?
                ORDER BY rs.stop_sequence
                """;
        List<Map<String, Object>> stops = jdbcTemplate.queryForList(stopSql, routeId);

        if (stops.size() < 2) {
            return ResponseEntity.ok(List.of());
        }

        // Active session ID
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1",
                Long.class);

        // Journeys for this trip
        String pjSql = """
                SELECT boarding_stop_id AS "bStop", destination_stop_id AS "dStop", passenger_count AS "cnt"
                FROM passenger_journeys
                WHERE trip_id = ? AND journey_status IN ('REGISTERED', 'BOARDED')
                """;
        List<Map<String, Object>> journeys = jdbcTemplate.queryForList(pjSql, tripId);

        // Simulated adjustments for this route and session
        Map<String, Integer> simDeltas = new HashMap<>();
        if (sessionId != null) {
            List<Map<String, Object>> adjs = jdbcTemplate.queryForList("""
                    SELECT segment_start_stop_id AS "sStop", segment_end_stop_id AS "eStop", demand_delta AS "delta"
                    FROM segment_demand_adjustments
                    WHERE session_id = ? AND route_id = ?
                    """, sessionId, routeId);
            for (Map<String, Object> a : adjs) {
                String key = a.get("sStop") + "->" + a.get("eStop");
                simDeltas.put(key, ((Number) a.get("delta")).intValue());
            }
        }

        // Build segment list
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < stops.size() - 1; i++) {
            Map<String, Object> s1 = stops.get(i);
            Map<String, Object> s2 = stops.get(i + 1);

            Long s1Id = ((Number) s1.get("stopId")).longValue();
            Long s2Id = ((Number) s2.get("stopId")).longValue();

            int regDemand = 0;
            for (Map<String, Object> j : journeys) {
                Long bStop = ((Number) j.get("bStop")).longValue();
                Long dStop = ((Number) j.get("dStop")).longValue();
                int cnt = ((Number) j.get("cnt")).intValue();

                // Find index of bStop and dStop in route
                int bIdx = -1, dIdx = -1;
                for (int k = 0; k < stops.size(); k++) {
                    if (stops.get(k).get("stopId").equals(bStop)) bIdx = k;
                    if (stops.get(k).get("stopId").equals(dStop)) dIdx = k;
                }

                if (bIdx >= 0 && dIdx > bIdx && i >= bIdx && i < dIdx) {
                    regDemand += cnt;
                }
            }

            String key = s1Id + "->" + s2Id;
            int delta = simDeltas.getOrDefault(key, 0);
            int effective = Math.max(0, regDemand + delta);

            Map<String, Object> row = new HashMap<>();
            row.put("tripId", tripId);
            row.put("routeCode", routeCode);
            row.put("startStopId", s1Id);
            row.put("startStopName", s1.get("stopName"));
            row.put("endStopId", s2Id);
            row.put("endStopName", s2.get("stopName"));
            row.put("registeredDemand", regDemand);
            row.put("simulatedDelta", delta);
            row.put("effectiveDemand", effective);
            row.put("currentBusCapacity", capacity);
            row.put("accommodated", Math.min(effective, capacity));
            row.put("unmetDemand", Math.max(0, effective - capacity));
            row.put("unusedCapacity", Math.max(0, capacity - effective));
            result.add(row);
        }

        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------
    // SIMULATION CONTROLS (ADMIN ONLY)
    // -------------------------------------------------------------

    @PostMapping("/admin/simulation/step")
    @Transactional
    public ResponseEntity<Map<String, Object>> stepSimulation(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireAdmin(authHeader);

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1",
                Long.class);

        if (sessionId == null) {
            throw new IllegalStateException("No active planning session to simulate.");
        }

        simulationElapsedMinutes += 2;

        // Generate +/- 5 to 10 demand adjustments for selected routes
        List<Long> routeIds = jdbcTemplate.queryForList(
                "SELECT route_id FROM session_routes WHERE session_id = ?", Long.class, sessionId);

        if (routeIds.isEmpty()) {
            routeIds = jdbcTemplate.queryForList("SELECT route_id FROM routes WHERE is_active = TRUE", Long.class);
        }

        int generatedDeltas = 0;
        for (Long rId : routeIds) {
            List<Map<String, Object>> stops = jdbcTemplate.queryForList("""
                    SELECT stop_id FROM route_stops WHERE route_id = ? ORDER BY stop_sequence
                    """, rId);

            for (int i = 0; i < stops.size() - 1; i++) {
                Long s1 = ((Number) stops.get(i).get("stop_id")).longValue();
                Long s2 = ((Number) stops.get(i + 1).get("stop_id")).longValue();

                // Magnitude between 5 and 10 per SRS F18
                int magnitude = 5 + random.nextInt(6);
                int sign = random.nextBoolean() ? 1 : -1;
                int delta = sign * magnitude;

                jdbcTemplate.update("""
                        INSERT INTO segment_demand_adjustments (session_id, route_id, segment_start_stop_id, segment_end_stop_id, demand_delta, is_simulated)
                        VALUES (?, ?, ?, ?, ?, TRUE)
                        """, sessionId, rId, s1, s2, delta);
                generatedDeltas++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Simulation step completed (+2 minutes).",
                "simulationElapsedMinutes", simulationElapsedMinutes,
                "adjustmentsGenerated", generatedDeltas,
                "sessionId", sessionId));
    }

    @PostMapping("/admin/simulation/reset")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetSimulation(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.requireAdmin(authHeader);

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1",
                Long.class);

        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM segment_demand_adjustments WHERE session_id = ?", sessionId);
        }
        simulationElapsedMinutes = 0;

        return ResponseEntity.ok(Map.of(
                "message", "Simulation adjustments and elapsed clock reset successfully.",
                "simulationElapsedMinutes", 0));
    }
}
