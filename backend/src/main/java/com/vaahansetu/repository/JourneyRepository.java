package com.vaahansetu.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JourneyRepository {

    private final JdbcTemplate jdbcTemplate;

    public JourneyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Find today's trips that have an active allocated bus
    public List<Map<String, Object>> findTodaysTrips(Long routeId) {
        return findTodaysTrips(routeId, null);
    }

    public List<Map<String, Object>> findTodaysTrips(Long routeId, LocalDate serviceDate) {
        String sql = """
                SELECT st.trip_id AS "tripId",
                       st.route_id AS "routeId",
                       st.service_date AS "serviceDate",
                       TO_CHAR(st.departure_time, 'HH24:MI') AS "departureTimeFormatted",
                       TO_CHAR(st.expected_arrival_time, 'HH24:MI') AS "arrivalTimeFormatted",
                       st.departure_time AS "departureTime",
                       st.expected_arrival_time AS "expectedArrivalTime",
                       st.status,
                       b.bus_id AS "busId",
                       b.registration_number AS "registrationNumber",
                       b.capacity
                FROM scheduled_trips st
                JOIN LATERAL (
                    SELECT
                        MIN(ta.bus_id) AS bus_id,
                        STRING_AGG(b2.registration_number, ', ' ORDER BY b2.bus_id) AS registration_number,
                        COALESCE(SUM(b2.capacity), 0) AS capacity
                    FROM trip_assignments ta
                    JOIN buses b2 ON b2.bus_id = ta.bus_id
                    WHERE ta.trip_id = st.trip_id
                      AND ta.status = 'ACTIVE'
                      AND b2.status = 'ACTIVE'
                ) b ON b.bus_id IS NOT NULL
                WHERE st.route_id = ?
                  AND st.service_date = COALESCE(?,
                        (SELECT started_at::date FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1),
                        (SELECT MAX(service_date) FROM scheduled_trips WHERE route_id = ?),
                        (SELECT MAX(service_date) FROM scheduled_trips),
                        CURRENT_DATE)
                  AND st.status = 'SCHEDULED'
                ORDER BY st.departure_time
                """;

        return jdbcTemplate.queryForList(
                sql,
                routeId,
                serviceDate,
                routeId
        );
    }

    public List<Map<String, Object>> findDeparturesForRoute(Long routeId) {
        return findTodaysTrips(routeId, null);
    }

    public List<Map<String, Object>> findDeparturesForRoute(Long routeId, LocalDate serviceDate) {
        return findTodaysTrips(routeId, serviceDate);
    }

    // Find all trips for a route today (whether bus is allocated yet or not)
    public List<Map<String, Object>> findTripsForRoute(Long routeId) {
        return findTripsForRoute(routeId, null);
    }

    public List<Map<String, Object>> findTripsForRoute(Long routeId, LocalDate serviceDate) {
        String sql = """
                SELECT st.trip_id AS "tripId",
                       st.route_id AS "routeId",
                       st.service_date AS "serviceDate",
                       TO_CHAR(st.departure_time, 'HH24:MI') AS "departureTimeFormatted",
                       TO_CHAR(st.expected_arrival_time, 'HH24:MI') AS "arrivalTimeFormatted",
                       st.departure_time AS "departureTime",
                       st.expected_arrival_time AS "expectedArrivalTime",
                       st.status,
                       b.bus_id AS "busId",
                       COALESCE(b.registration_number, 'UNASSIGNED') AS "registrationNumber",
                       COALESCE(b.capacity, 0) AS "capacity"
                FROM scheduled_trips st
                LEFT JOIN LATERAL (
                    SELECT
                        STRING_AGG(b2.registration_number, ', ' ORDER BY b2.bus_id) AS registration_number,
                        COALESCE(SUM(b2.capacity), 0) AS capacity,
                        MIN(ta2.bus_id) AS bus_id
                    FROM trip_assignments ta2
                    JOIN buses b2 ON b2.bus_id = ta2.bus_id
                    WHERE ta2.trip_id = st.trip_id
                      AND ta2.status = 'ACTIVE'
                      AND b2.status = 'ACTIVE'
                ) b ON TRUE
                WHERE st.route_id = ?
                  AND st.service_date = COALESCE(?,
                        (SELECT started_at::date FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1),
                        (SELECT MAX(service_date) FROM scheduled_trips WHERE route_id = ?),
                        (SELECT MAX(service_date) FROM scheduled_trips),
                        CURRENT_DATE)
                  AND st.status = 'SCHEDULED'
                ORDER BY st.departure_time
                """;

        return jdbcTemplate.queryForList(
                sql,
                routeId,
                serviceDate,
                routeId
        );
    }

    // Find the currently active planning session
    public Long findActiveSessionId() {

        String sql = """
                SELECT session_id
                FROM planning_sessions
                WHERE status = 'ACTIVE'
                ORDER BY started_at DESC
                LIMIT 1
                """;

        List<Long> sessions =
                jdbcTemplate.queryForList(sql, Long.class);

        return sessions.isEmpty() ? null : sessions.get(0);
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

    // Get active stops belonging to the selected route in sequence
    public List<Long> findStopOrder(Long routeId) {

        String sql = """
                SELECT rs.stop_id
                FROM route_stops rs
                JOIN stops s
                    ON s.stop_id = rs.stop_id
                WHERE rs.route_id = ?
                  AND s.is_active = TRUE
                ORDER BY rs.stop_sequence
                """;

        return jdbcTemplate.queryForList(
                sql,
                Long.class,
                routeId
        );
    }

    // Save a passenger journey and return its generated ID
    public Long saveJourney(
            Long sessionId,
            Long tripId,
            Long boardingStopId,
            Long destinationStopId,
            Integer passengerCount) {

        String sql = """
                INSERT INTO passenger_journeys (
                    session_id,
                    trip_id,
                    boarding_stop_id,
                    destination_stop_id,
                    passenger_count,
                    journey_status
                )
                VALUES (?, ?, ?, ?, ?, 'REGISTERED')
                RETURNING journey_id
                """;

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                sessionId,
                tripId,
                boardingStopId,
                destinationStopId,
                passengerCount
        );
    }

    // Check if a duplicate journey was submitted recently within 2 minutes (SRS F9, NFR-S7)
    public boolean isDuplicateJourney(
            Long sessionId,
            Long tripId,
            Long boardingStopId,
            Long destinationStopId,
            Integer passengerCount) {

        String sql = """
                SELECT COUNT(*)
                FROM passenger_journeys
                WHERE session_id = ?
                  AND trip_id = ?
                  AND boarding_stop_id = ?
                  AND destination_stop_id = ?
                  AND passenger_count = ?
                  AND journey_status IN ('REGISTERED', 'BOARDED')
                  AND submitted_at >= NOW() - INTERVAL '2 minutes'
                """;

        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                sessionId,
                tripId,
                boardingStopId,
                destinationStopId,
                passengerCount
        );

        return count != null && count > 0;
    }

    // Find a saved journey
    public Map<String, Object> findJourneyById(Long journeyId) {

        String sql = """
                SELECT pj.journey_id AS "journeyId",
                       st.route_id AS "routeId",
                       pj.trip_id AS "tripId",
                       pj.boarding_stop_id AS "boardingStopId",
                       pj.destination_stop_id AS "destinationStopId",
                       pj.passenger_count AS "passengerCount",
                       pj.journey_status AS "journeyStatus",
                       pj.boarding_confirmed_at AS "boardingConfirmedAt",
                       pj.alighting_confirmed_at AS "alightingConfirmedAt",
                       pj.submitted_at AS "submittedAt",
                       pj.passenger_reference AS "passengerReference"
                FROM passenger_journeys pj
                JOIN scheduled_trips st
                    ON st.trip_id = pj.trip_id
                WHERE pj.journey_id = ?
                """;

        List<Map<String, Object>> results =
                jdbcTemplate.queryForList(sql, journeyId);

        return results.isEmpty() ? null : results.get(0);
    }

    // Get passenger demand grouped by route for the active session and service date
    public List<Map<String, Object>> findPassengerDemand(Long sessionId) {
        return findPassengerDemand(sessionId, null);
    }

    public List<Map<String, Object>> findPassengerDemand(Long sessionId, LocalDate serviceDate) {
        Long targetSessionId = sessionId != null ? sessionId : findActiveSessionId();
        if (targetSessionId == null) {
            return List.of();
        }

        // Route-level demand aggregation. Logic reconciled with findTripPassengerDemand:
        //   - booked_count = SUM of passenger_count for REGISTERED/BOARDED/COMPLETED
        //   - inquiry_count = SUM of passenger_count for UNCONFIRMED
        //   - admin adjustments reported SEPARATELY (routeAdminAdjustment), NOT added to totalDemand
        //   - totalDemand = booked_count + inquiry_count (no admin adjustments)
        //   - occupancyPercentage and overcapacity based on total bus capacity assigned to the route
        String sql = """
                SELECT r.route_id AS "routeId",
                       r.route_code AS "routeCode",
                       r.route_name AS "routeName",
                       r.origin_name AS "originName",
                       r.destination_name AS "destinationName",
                       COALESCE(j.booked_count, 0) AS "confirmedCount",
                       COALESCE(j.booked_count, 0) AS "bookedCount",
                       COALESCE(j.booked_count, 0) AS "bookedPassengerCount",
                       COALESCE(j.inquiry_count, 0) AS "searchCount",
                       COALESCE(j.inquiry_count, 0) AS "activeInquiryCount",
                       COALESCE(a.adj_count, 0) AS "adjustmentCount",
                       COALESCE(a.adj_count, 0) AS "routeAdminAdjustment",
                       COALESCE(a.adj_count, 0) AS "routeAdjustmentCount",
                       COALESCE(b.total_capacity, 0) AS "busCapacity",
                       COALESCE(b.total_capacity, 0) AS "capacity",
                       COALESCE(b.bus_count, 0) AS "busCount",
                       GREATEST(0, COALESCE(j.booked_count, 0) + COALESCE(j.inquiry_count, 0)) AS "passengerCount",
                       GREATEST(0, COALESCE(j.booked_count, 0) + COALESCE(j.inquiry_count, 0)) AS "totalDemand",
                       CASE
                           WHEN COALESCE(b.total_capacity, 0) > 0 THEN
                               ROUND((COALESCE(j.booked_count, 0)::numeric * 100.0) / b.total_capacity, 1)
                           ELSE 0.0
                       END AS "occupancyPercentage",
                       CASE
                           WHEN COALESCE(b.total_capacity, 0) > 0 AND COALESCE(j.booked_count, 0) > b.total_capacity THEN TRUE
                           ELSE FALSE
                       END AS "overcapacity",
                       CASE
                           WHEN COALESCE(b.total_capacity, 0) > 0 AND COALESCE(j.booked_count, 0) > b.total_capacity THEN TRUE
                           ELSE FALSE
                       END AS "isOvercapacity"
                FROM routes r
                JOIN session_routes sr ON sr.route_id = r.route_id AND sr.session_id = ?
                LEFT JOIN LATERAL (
                    SELECT
                        COALESCE(SUM(CASE WHEN pj.journey_status IN ('REGISTERED', 'BOARDED', 'COMPLETED') THEN pj.passenger_count ELSE 0 END), 0) AS booked_count,
                        COALESCE(SUM(CASE WHEN pj.journey_status = 'UNCONFIRMED' THEN pj.passenger_count ELSE 0 END), 0) AS inquiry_count
                    FROM passenger_journeys pj
                    JOIN scheduled_trips st ON st.trip_id = pj.trip_id
                    WHERE st.route_id = r.route_id
                      AND (pj.session_id = ? OR ? IS NULL)
                      AND st.service_date = COALESCE(?,
                            CURRENT_DATE,
                            (SELECT MAX(service_date) FROM scheduled_trips WHERE route_id = r.route_id AND service_date >= CURRENT_DATE),
                            (SELECT MAX(service_date) FROM scheduled_trips),
                            CURRENT_DATE)
                ) j ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(MAX(sub.max_seg), 0) AS adj_count
                    FROM (
                        SELECT COALESCE(SUM(sda.demand_delta), 0) AS max_seg
                        FROM segment_demand_adjustments sda
                        WHERE sda.route_id = r.route_id
                          AND (sda.session_id = ? OR ? IS NULL)
                        GROUP BY sda.segment_start_stop_id
                    ) sub
                ) a ON TRUE
                LEFT JOIN LATERAL (
                    SELECT
                        COALESCE(SUM(b2.capacity), 0) AS total_capacity,
                        COUNT(DISTINCT b2.bus_id) AS bus_count
                    FROM scheduled_trips st2
                    JOIN trip_assignments ta2 ON ta2.trip_id = st2.trip_id AND ta2.status = 'ACTIVE'
                    JOIN buses b2 ON b2.bus_id = ta2.bus_id AND b2.status = 'ACTIVE'
                    WHERE st2.route_id = r.route_id
                      AND st2.service_date = COALESCE(?,
                            CURRENT_DATE,
                            (SELECT MAX(service_date) FROM scheduled_trips WHERE route_id = r.route_id AND service_date >= CURRENT_DATE),
                            (SELECT MAX(service_date) FROM scheduled_trips),
                            CURRENT_DATE)
                ) b ON TRUE
                WHERE r.is_active = TRUE
                ORDER BY r.route_id
                """;
        return jdbcTemplate.queryForList(sql, targetSessionId, targetSessionId, targetSessionId, serviceDate, targetSessionId, targetSessionId, serviceDate);
    }

    // Get passenger demand separated for EACH scheduled trip in the active session and service date
    public List<Map<String, Object>> findTripPassengerDemand(Long sessionId) {
        return findTripPassengerDemand(sessionId, null, null);
    }

    public List<Map<String, Object>> findTripPassengerDemand(Long sessionId, Long routeId, LocalDate serviceDate) {
        Long targetSessionId = sessionId != null ? sessionId : findActiveSessionId();
        if (targetSessionId == null) {
            return List.of();
        }

        String sql = """
                SELECT st.trip_id                                                     AS "scheduledTripId",
                       st.trip_id                                                     AS "tripId",
                       st.route_id                                                    AS "routeId",
                       r.route_code                                                   AS "routeCode",
                       r.route_name                                                   AS "routeName",
                       r.origin_name                                                  AS "originName",
                       r.destination_name                                             AS "destinationName",
                       TO_CHAR(st.service_date, 'YYYY-MM-DD')                         AS "serviceDate",
                       TO_CHAR(st.departure_time, 'YYYY-MM-DD"T"HH24:MI:SS')          AS "departureTime",
                       TO_CHAR(st.departure_time, 'HH24:MI')                          AS "departureTimeFormatted",
                       TO_CHAR(st.expected_arrival_time, 'YYYY-MM-DD"T"HH24:MI:SS')   AS "arrivalTime",
                       TO_CHAR(st.expected_arrival_time, 'HH24:MI')                   AS "arrivalTimeFormatted",
                       b.registration_number                                          AS "assignedBus",
                       b.registration_number                                          AS "busRegistration",
                       COALESCE(b.capacity, 0)                                        AS "busCapacity",
                       COALESCE(b.capacity, 0)                                        AS "capacity",
                       COALESCE(j.booked_count, 0)                                    AS "bookedPassengerCount",
                       COALESCE(j.booked_count, 0)                                    AS "bookedCount",
                       COALESCE(j.inquiry_count, 0)                                   AS "activeInquiryCount",
                       COALESCE(j.inquiry_count, 0)                                   AS "searchCount",
                       0                                                              AS "adminAdjustments",
                       0                                                              AS "adjustmentCount",
                       COALESCE(a.adj_count, 0)                                       AS "routeAdminAdjustment",
                       COALESCE(a.adj_count, 0)                                       AS "routeAdjustmentCount",
                       GREATEST(0, COALESCE(j.booked_count, 0) + COALESCE(j.inquiry_count, 0)) AS "totalDemand",
                       GREATEST(0, COALESCE(j.booked_count, 0) + COALESCE(j.inquiry_count, 0)) AS "passengerCount",
                       CASE
                           WHEN COALESCE(b.capacity, 0) > 0 THEN
                               ROUND((COALESCE(j.booked_count, 0)::numeric * 100.0) / b.capacity, 1)
                           ELSE 0.0
                       END                                                            AS "occupancyPercentage",
                       CASE
                           WHEN COALESCE(b.capacity, 0) > 0 AND COALESCE(j.booked_count, 0) > b.capacity THEN TRUE
                           ELSE FALSE
                       END                                                            AS "overcapacity",
                       CASE
                           WHEN COALESCE(b.capacity, 0) > 0 AND COALESCE(j.booked_count, 0) > b.capacity THEN TRUE
                           ELSE FALSE
                       END                                                            AS "isOvercapacity"
                FROM scheduled_trips st
                JOIN routes r ON r.route_id = st.route_id
                JOIN session_routes sr ON sr.route_id = r.route_id AND sr.session_id = ?::bigint
                LEFT JOIN LATERAL (
                    SELECT
                        STRING_AGG(b2.registration_number, ', ' ORDER BY b2.bus_id) AS registration_number,
                        COALESCE(SUM(b2.capacity), 0) AS capacity,
                        MIN(ta2.bus_id) AS bus_id
                    FROM trip_assignments ta2
                    JOIN buses b2 ON b2.bus_id = ta2.bus_id
                    WHERE ta2.trip_id = st.trip_id
                      AND ta2.status = 'ACTIVE'
                      AND b2.status = 'ACTIVE'
                ) b ON TRUE
                LEFT JOIN LATERAL (
                    SELECT
                        COALESCE(SUM(CASE WHEN pj.journey_status IN ('REGISTERED', 'BOARDED', 'COMPLETED') THEN pj.passenger_count ELSE 0 END), 0) AS booked_count,
                        COALESCE(SUM(CASE WHEN pj.journey_status = 'UNCONFIRMED' THEN pj.passenger_count ELSE 0 END), 0) AS inquiry_count
                    FROM passenger_journeys pj
                    WHERE pj.trip_id = st.trip_id
                      AND (pj.session_id = ?::bigint OR ?::bigint IS NULL)
                ) j ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(MAX(sub.max_seg), 0) AS adj_count
                    FROM (
                        SELECT COALESCE(SUM(sda.demand_delta), 0) AS max_seg
                        FROM segment_demand_adjustments sda
                        WHERE sda.route_id = st.route_id
                          AND (sda.session_id = ?::bigint OR ?::bigint IS NULL)
                        GROUP BY sda.segment_start_stop_id
                    ) sub
                ) a ON TRUE
                WHERE r.is_active = TRUE
                  AND st.status = 'SCHEDULED'
                  AND (?::bigint IS NULL OR st.route_id = ?::bigint)
                  AND st.service_date = COALESCE(?::date,
                        (SELECT started_at::date FROM planning_sessions WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1),
                        (SELECT MAX(service_date) FROM scheduled_trips),
                        CURRENT_DATE)
                ORDER BY st.departure_time, st.trip_id
                """;
        return jdbcTemplate.queryForList(
                sql,
                targetSessionId,
                targetSessionId, targetSessionId,
                targetSessionId, targetSessionId,
                routeId, routeId,
                serviceDate
        );
    }

    // Record passenger travel search intent into common demand
    public Long recordSearchIntent(Long sessionId, Long tripId, Long boardingStopId, Long destinationStopId, Integer count) {
        return recordSearchIntent(sessionId, tripId, boardingStopId, destinationStopId, count, null);
    }

    public Long recordSearchIntent(Long sessionId, Long tripId, Long boardingStopId, Long destinationStopId, Integer count, Long inquiryId) {
        int validCount = count != null && count > 0 ? count : 1;

        if (inquiryId != null) {
            String updateSql = """
                    UPDATE passenger_journeys
                    SET session_id = ?,
                        trip_id = ?,
                        boarding_stop_id = ?,
                        destination_stop_id = ?,
                        passenger_count = ?,
                        submitted_at = NOW()
                    WHERE journey_id = ?
                      AND journey_status = 'UNCONFIRMED'
                    """;
            int rows = jdbcTemplate.update(
                    updateSql,
                    sessionId,
                    tripId,
                    boardingStopId,
                    destinationStopId,
                    validCount,
                    inquiryId
            );
            if (rows > 0) {
                return inquiryId;
            }
        }

        String sql = """
                INSERT INTO passenger_journeys (
                    session_id,
                    trip_id,
                    boarding_stop_id,
                    destination_stop_id,
                    passenger_count,
                    journey_status
                )
                VALUES (?, ?, ?, ?, ?, 'UNCONFIRMED')
                RETURNING journey_id
                """;
        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                sessionId,
                tripId,
                boardingStopId,
                destinationStopId,
                validCount
        );
    }

    // Convert an active UNCONFIRMED inquiry into a confirmed REGISTERED booking
    public boolean convertInquiryToBooking(Long inquiryId, Long sessionId, Long tripId, Long boardingStopId, Long destinationStopId, Integer passengerCount) {
        if (inquiryId == null) {
            return false;
        }
        int validCount = passengerCount != null && passengerCount > 0 ? passengerCount : 1;
        String sql = """
                UPDATE passenger_journeys
                SET journey_status = 'REGISTERED',
                    session_id = ?,
                    trip_id = ?,
                    boarding_stop_id = ?,
                    destination_stop_id = ?,
                    passenger_count = ?,
                    submitted_at = NOW()
                WHERE journey_id = ?
                  AND journey_status = 'UNCONFIRMED'
                """;
        int rows = jdbcTemplate.update(
                sql,
                sessionId,
                tripId,
                boardingStopId,
                destinationStopId,
                validCount,
                inquiryId
        );
        return rows > 0;
    }

    // Update the status of a passenger journey
    public int updateJourneyStatus(Long journeyId, String status) {

        String sql;
        if ("BOARDED".equalsIgnoreCase(status)) {
            sql = """
                    UPDATE passenger_journeys
                    SET journey_status = ?::journey_status,
                        boarding_confirmed_at = COALESCE(boarding_confirmed_at, NOW())
                    WHERE journey_id = ?
                    """;
        } else if ("COMPLETED".equalsIgnoreCase(status)) {
            sql = """
                    UPDATE passenger_journeys
                    SET journey_status = ?::journey_status,
                        alighting_confirmed_at = COALESCE(alighting_confirmed_at, NOW())
                    WHERE journey_id = ?
                    """;
        } else {
            sql = """
                    UPDATE passenger_journeys
                    SET journey_status = ?::journey_status
                    WHERE journey_id = ?
                    """;
        }

        return jdbcTemplate.update(
                sql,
                status,
                journeyId
        );
    }
}
