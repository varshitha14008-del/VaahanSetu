package com.vaahansetu.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RouteRepository {

    private final JdbcTemplate jdbcTemplate;

    public RouteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Get routes for the active planning session (SRS F4 / U2)
    public List<Map<String, Object>> findRoutesForActiveSession() {
        return findRoutesBySessionId(null);
    }

    // Get routes for a specific planning session (or active session if sessionId is null)
    public List<Map<String, Object>> findRoutesBySessionId(Long sessionId) {
        String sql;
        if (sessionId != null) {
            sql = """
                    SELECT r.route_id AS "routeId",
                           r.route_code AS "routeCode",
                           r.route_name AS "routeName",
                           r.origin_name AS "originName",
                           r.origin_name AS "origin",
                           r.destination_name AS "destinationName",
                           r.destination_name AS "destination",
                           r.estimated_duration_minutes AS "estimatedDurationMinutes"
                    FROM routes r
                    JOIN session_routes sr ON sr.route_id = r.route_id
                    WHERE sr.session_id = ?
                      AND r.is_active = TRUE
                    ORDER BY r.route_id
                    """;
            return jdbcTemplate.queryForList(sql, sessionId);
        } else {
            sql = """
                    SELECT r.route_id AS "routeId",
                           r.route_code AS "routeCode",
                           r.route_name AS "routeName",
                           r.origin_name AS "originName",
                           r.origin_name AS "origin",
                           r.destination_name AS "destinationName",
                           r.destination_name AS "destination",
                           r.estimated_duration_minutes AS "estimatedDurationMinutes"
                    FROM routes r
                    JOIN session_routes sr ON sr.route_id = r.route_id
                    WHERE sr.session_id = (
                        SELECT session_id
                        FROM planning_sessions
                        WHERE status = 'ACTIVE'
                        ORDER BY started_at DESC
                        LIMIT 1
                    )
                      AND r.is_active = TRUE
                    ORDER BY r.route_id
                    """;
            return jdbcTemplate.queryForList(sql);
        }
    }

    // Check whether a specific route is selected for a planning session
    public boolean isRouteSelectedForSession(Long sessionId, Long routeId) {
        if (sessionId == null || routeId == null) {
            return false;
        }
        String sql = """
                SELECT COUNT(*)
                FROM session_routes
                WHERE session_id = ? AND route_id = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, sessionId, routeId);
        return count != null && count > 0;
    }

    // Get all active master routes (catalog / unfiltered fallback)
    public List<Map<String, Object>> findAllActiveRoutes() {

        String sql = """
                SELECT route_id AS "routeId",
                       route_code AS "routeCode",
                       route_name AS "routeName",
                       origin_name AS "originName",
                       origin_name AS "origin",
                       destination_name AS "destinationName",
                       destination_name AS "destination",
                       estimated_duration_minutes AS "estimatedDurationMinutes"
                FROM routes
                WHERE is_active = TRUE
                ORDER BY route_id
                """;

        return jdbcTemplate.queryForList(sql);
    }

    // Get active stops for a selected route
    public List<Map<String, Object>> findStopsByRouteId(Long routeId) {

        String sql = """
                SELECT s.stop_id AS "stopId",
                       s.stop_name AS "stopName",
                       s.is_active AS "isActive"
                FROM stops s
                JOIN route_stops rs ON rs.stop_id = s.stop_id
                WHERE rs.route_id = ?
                  AND s.is_active = TRUE
                ORDER BY rs.stop_sequence
                """;

        return jdbcTemplate.queryForList(sql, routeId);
    }

    // Get buses actively allocated to a route for today
    public List<Map<String, Object>> findAllocatedBuses(Long routeId) {
        return findAllocatedBuses(routeId, null);
    }

    public List<Map<String, Object>> findAllocatedBuses(Long routeId, LocalDate serviceDate) {
        String sql = """
                SELECT b.bus_id AS "busId",
                       b.registration_number AS "registrationNumber",
                       b.capacity AS "capacity",
                       b.current_location AS "currentLocation",
                       st.trip_id AS "tripId",
                       st.departure_time AS "departureTime",
                       st.expected_arrival_time AS "expectedArrivalTime"
                FROM scheduled_trips st
                JOIN trip_assignments ta ON ta.trip_id = st.trip_id
                JOIN buses b ON b.bus_id = ta.bus_id
                WHERE st.route_id = ?
                  AND st.service_date = COALESCE(?,
                        (SELECT started_at::date FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1),
                        (SELECT MAX(service_date) FROM scheduled_trips WHERE route_id = ?),
                        (SELECT MAX(service_date) FROM scheduled_trips),
                        CURRENT_DATE)
                  AND ta.status = 'ACTIVE'
                  AND b.status = 'ACTIVE'
                ORDER BY st.departure_time
                """;

        return jdbcTemplate.queryForList(
                sql,
                routeId,
                serviceDate,
                routeId
        );
    }
}
