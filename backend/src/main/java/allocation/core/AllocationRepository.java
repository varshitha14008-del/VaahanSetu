package allocation.core;

import static allocation.core.AllocationModels.*;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.time.LocalTime;

/**
 * All SQL for the allocation module: reads the inputs and writes the
 * before/after results.
 */
public class AllocationRepository {

        /**
         * Journey statuses that count as demand. UNCONFIRMED / EXPIRED / CANCELLED /
         * COMPLETED do not.
         * (Open team decision in the README - change this one line if the team decides
         * otherwise.)
         */
        private static final String COUNTED_STATUSES = "'REGISTERED','BOARDED'";

        /** Marks rows written by this module so a re-run only replaces its own rows. */
        static final String AUTO = "AUTO:";

        // ---------------- connection ----------------

        private final javax.sql.DataSource dataSource;

        public AllocationRepository() {
                this(null);
        }

        public AllocationRepository(javax.sql.DataSource dataSource) {
                this.dataSource = dataSource;
        }

        /**
         * Uses the injected DataSource if available, or reads DB_URL / DB_USER / DB_PASSWORD
         * from the environment.
         */
        public Connection open() throws SQLException {
                if (dataSource != null) {
                        return dataSource.getConnection();
                }
                String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/vaahansetu");
                String user = System.getenv().getOrDefault("DB_USER", "vaahansetu_app");
                String password = System.getenv("DB_PASSWORD");
                if (password == null) {
                        return DriverManager.getConnection(url, user, "");
                }
                return DriverManager.getConnection(url, user, password);
        }

        // ---------------- small helpers ----------------

        private interface RowMapper<T> {
                T map(ResultSet rs) throws SQLException;
        }

        private static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params)
                        throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < params.length; i++)
                                ps.setObject(i + 1, params[i]);
                        try (ResultSet rs = ps.executeQuery()) {
                                List<T> out = new ArrayList<>();
                                while (rs.next())
                                        out.add(mapper.map(rs));
                                return out;
                        }
                }
        }

        // ---------------- session lookups ----------------

        /** The single ACTIVE planning session, or empty if there is none. */
        public OptionalLong activeSessionId(Connection c) throws SQLException {
                List<Long> ids = query(c, "SELECT session_id FROM planning_sessions WHERE status = 'ACTIVE'",
                                rs -> rs.getLong(1));
                return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
        }

        /** Changes whenever any journey of the session is added, edited or removed. */
        public String demandFingerprint(Connection c, long sessionId) throws SQLException {
                String sql = "SELECT COALESCE(md5(string_agg(journey_id::text || ':' || trip_id::text || ':' || "
                                + "boarding_stop_id::text || ':' || destination_stop_id::text || ':' || passenger_count::text "
                                + "|| ':' || journey_status::text, ',' ORDER BY journey_id)), '') "
                                + "FROM passenger_journeys WHERE session_id = ?";
                return query(c, sql, rs -> rs.getString(1), sessionId).get(0);
        }

        public String inputFingerprint(AllocationModels.Input input) {
                StringBuilder data = new StringBuilder();

                input.routes().stream()
                                .sorted(java.util.Comparator.comparingLong(AllocationModels.Route::id))
                                .forEach(r -> {
                                        data.append("ROUTE|")
                                                        .append(r.id()).append('|')
                                                        .append(r.code()).append('|')
                                                        .append(r.name()).append('|')
                                                        .append(r.stopIds())
                                                        .append('\n');
                                });

                input.buses().stream()
                                .sorted(java.util.Comparator.comparingLong(AllocationModels.Bus::id))
                                .forEach(b -> {
                                        data.append("BUS|")
                                                        .append(b.id()).append('|')
                                                        .append(b.capacity()).append('|')
                                                        .append(b.status()).append('|')
                                                        .append(b.restMinutes()).append('|')
                                                        .append(b.restStart()).append('|')
                                                        .append(b.restEnd()).append('|')
                                                        .append(b.selectedForSession())
                                                        .append('\n');
                                });

                input.trips().stream()
                                .sorted(java.util.Comparator.comparingLong(AllocationModels.Trip::id))
                                .forEach(t -> {
                                        data.append("TRIP|")
                                                        .append(t.id()).append('|')
                                                        .append(t.routeId()).append('|')
                                                        .append(t.departure()).append('|')
                                                        .append(t.arrival()).append('|')
                                                        .append(t.currentBusId()).append('|')
                                                        .append(t.currentBusCapacity())
                                                        .append('\n');
                                });

                input.journeys().stream()
                                .sorted(java.util.Comparator
                                                .comparingLong(AllocationModels.Journey::tripId)
                                                .thenComparingLong(AllocationModels.Journey::boardingStopId)
                                                .thenComparingLong(AllocationModels.Journey::destinationStopId))
                                .forEach(j -> {
                                        data.append("JOURNEY|")
                                                        .append(j.tripId()).append('|')
                                                        .append(j.boardingStopId()).append('|')
                                                        .append(j.destinationStopId()).append('|')
                                                        .append(j.passengers())
                                                        .append('\n');
                                });

                input.commitments().stream()
                                .sorted(java.util.Comparator
                                                .comparingLong(AllocationModels.Commitment::busId)
                                                .thenComparingLong(AllocationModels.Commitment::tripId))
                                .forEach(c -> {
                                        data.append("COMMITMENT|")
                                                        .append(c.busId()).append('|')
                                                        .append(c.tripId()).append('|')
                                                        .append(c.routeCode()).append('|')
                                                        .append(c.start()).append('|')
                                                        .append(c.end())
                                                        .append('\n');
                                });
                input.buses().stream()
                                .sorted(java.util.Comparator.comparingLong(AllocationModels.Bus::id))
                                .forEach(bus -> {
                                        data.append("AVAILABILITY|")
                                                        .append(bus.id()).append('|')
                                                        .append(availabilityState(bus, input))
                                                        .append('\n');
                                });
                try {
                        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

                        return java.util.HexFormat.of().formatHex(hash);

                } catch (java.security.NoSuchAlgorithmException e) {
                        throw new IllegalStateException("SHA-256 is unavailable.", e);
                }
        }

        private static String availabilityState(
                        Bus bus,
                        Input input) {

                if (!"ACTIVE".equals(bus.status())) {
                        return "INACTIVE:" + bus.status();
                }

                LocalDateTime now = input.time();

                if (isInsideFixedRestWindow(bus, now)) {
                        return "FIXED_REST";
                }

                for (Commitment c : input.commitments()) {
                        if (c.busId() != bus.id()) {
                                continue;
                        }

                        if (!now.isBefore(c.start()) && now.isBefore(c.end())) {
                                return "ON_TRIP:" + c.tripId();
                        }

                        LocalDateTime restEnd = c.end().plusMinutes(bus.restMinutes());

                        if (!now.isBefore(c.end()) && now.isBefore(restEnd)) {
                                return "POST_TRIP_REST:" + c.tripId();
                        }
                }

                return "AVAILABLE";
        }

        private static boolean isInsideFixedRestWindow(
                        Bus bus,
                        LocalDateTime time) {

                if (!bus.hasFixedRestWindow()) {
                        return false;
                }

                LocalTime now = time.toLocalTime();

                if (bus.restStart().isBefore(bus.restEnd())) {
                        return !now.isBefore(bus.restStart())
                                        && now.isBefore(bus.restEnd());
                }

                return !now.isBefore(bus.restStart())
                                || now.isBefore(bus.restEnd());
        }

        // ---------------- load ----------------

        public Input load(Connection c, long sessionId, LocalDateTime time) throws SQLException {
                if (query(c, "SELECT 1 FROM planning_sessions WHERE session_id = ?", rs -> 1, sessionId).isEmpty())
                        throw new IllegalArgumentException("Planning session " + sessionId + " does not exist.");

                // The team schema includes these TIME columns; fail with a clear message if an
                // older
                // database has not yet been updated. No schema changes are performed by Java.
                try (PreparedStatement check = c.prepareStatement(
                                "SELECT rest_start, rest_end FROM buses WHERE 1 = 0")) {
                        check.executeQuery().close();
                } catch (SQLException e) {
                        throw new SQLException("The buses table must include TIME columns rest_start and rest_end, "
                                        + "alongside rest_duration_minutes. Update the local schema before running DB integration.",
                                        e);
                }

                LocalDate day = time.toLocalDate();

                // Check if scheduled trips exist for this day and session.
                // Only current or explicitly valid future service dates are eligible.
                // Never fall back to past dates (< day).
                List<LocalDate> availableDays = query(c, "SELECT DISTINCT t.service_date FROM scheduled_trips t "
                                + "JOIN session_routes sr ON sr.route_id = t.route_id AND sr.session_id = ? "
                                + "WHERE t.status = 'SCHEDULED' AND t.service_date >= ? ORDER BY t.service_date ASC",
                                rs -> rs.getObject(1, LocalDate.class), sessionId, day);

                LocalDate targetDay = day;
                LocalDateTime filterTime = time;
                if (!availableDays.isEmpty() && !availableDays.contains(day)) {
                        targetDay = availableDays.get(0);
                        filterTime = targetDay.atStartOfDay();
                }

                // Routes selected for the session, with their stops in order.
                Map<Long, List<Long>> stops = new LinkedHashMap<>();
                query(c, "SELECT rs.route_id, rs.stop_id FROM route_stops rs "
                                + "JOIN session_routes sr ON sr.route_id = rs.route_id "
                                + "WHERE sr.session_id = ? ORDER BY rs.route_id, rs.stop_sequence",
                                rs -> stops.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2)),
                                sessionId);
                List<Route> routes = query(c, "SELECT r.route_id, r.route_code, r.route_name FROM session_routes sr "
                                + "JOIN routes r ON r.route_id = sr.route_id WHERE sr.session_id = ? AND r.is_active "
                                + "ORDER BY r.route_id",
                                rs -> new Route(rs.getLong(1), rs.getString(2), rs.getString(3),
                                                stops.getOrDefault(rs.getLong(1), List.of())),
                                sessionId);

                // Buses selected for the session.
                // Load the full fleet for the bus-details page and availability calculation.
                // session_buses is retained as a display/selection flag; an active free bus may
                // also
                // be borrowed for a selected route if doing so does not conflict with another
                // commitment.
                List<Bus> buses = query(c, "SELECT b.bus_id, b.registration_number, b.capacity, b.status::text, "
                                + "b.current_location, b.rest_duration_minutes, b.rest_start, b.rest_end, b.notes, "
                                + "b.created_at, (sb.bus_id IS NOT NULL) AS selected_for_session "
                                + "FROM buses b LEFT JOIN session_buses sb ON sb.bus_id = b.bus_id AND sb.session_id = ? "
                                + "ORDER BY b.bus_id",
                                rs -> new Bus(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                                                rs.getString(5), rs.getInt(6),
                                                rs.getObject(7, java.time.LocalTime.class),
                                                rs.getObject(8, java.time.LocalTime.class), rs.getString(9),
                                                rs.getObject(10, java.time.OffsetDateTime.class), rs.getBoolean(11)),
                                sessionId);

                // Trips to allocate: SCHEDULED trips of the selected routes, on the allocation
                // day, not yet departed.
                List<Trip> trips = query(c, "SELECT t.trip_id, t.route_id, t.departure_time, t.expected_arrival_time, "
                                + "ta.bus_id, COALESCE(b.capacity, 0) FROM scheduled_trips t "
                                + "JOIN session_routes sr ON sr.route_id = t.route_id AND sr.session_id = ? "
                                + "LEFT JOIN trip_assignments ta ON ta.trip_id = t.trip_id AND ta.status = 'ACTIVE' "
                                + "LEFT JOIN buses b ON b.bus_id = ta.bus_id "
                                + "WHERE t.status = 'SCHEDULED' AND t.service_date = ? AND t.departure_time >= ? "
                                + "ORDER BY t.departure_time, t.trip_id",
                                rs -> new Trip(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDateTime.class),
                                                rs.getObject(4, LocalDateTime.class), rs.getObject(5, Long.class),
                                                rs.getInt(6)),
                                sessionId, targetDay, filterTime);

                // Passenger demand for those trips.
                List<Journey> journeys = query(c, "SELECT pj.trip_id, pj.boarding_stop_id, pj.destination_stop_id, "
                                + "pj.passenger_count FROM passenger_journeys pj JOIN scheduled_trips t ON t.trip_id = pj.trip_id "
                                + "JOIN session_routes sr ON sr.route_id = t.route_id AND sr.session_id = pj.session_id "
                                + "WHERE pj.session_id = ? AND pj.journey_status IN (" + COUNTED_STATUSES + ") "
                                + "AND t.status = 'SCHEDULED' AND t.service_date = ? AND t.departure_time >= ?",
                                rs -> new Journey(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
                                sessionId, targetDay, filterTime);

                // Existing commitments of the entire fleet.
                // Trips being reallocated are removed below; all other assignments stay fixed.
                List<Commitment> all = query(c, "SELECT ta.bus_id, t.trip_id, r.route_code, "
                                + "t.departure_time, t.expected_arrival_time "
                                + "FROM trip_assignments ta "
                                + "JOIN scheduled_trips t ON t.trip_id = ta.trip_id "
                                + "JOIN routes r ON r.route_id = t.route_id "
                                + "WHERE ta.status = 'ACTIVE' AND t.status <> 'CANCELLED' "
                                + "AND t.departure_time >= ? AND t.departure_time < ?",
                                rs -> new Commitment(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                                rs.getObject(4, LocalDateTime.class),
                                                rs.getObject(5, LocalDateTime.class)),
                                targetDay.minusDays(1).atStartOfDay(), targetDay.plusDays(2).atStartOfDay());

                Set<Long> reallocated = new HashSet<>();
                for (Trip t : trips)
                        reallocated.add(t.id());
                List<Commitment> fixed = new ArrayList<>();
                for (Commitment cm : all)
                        if (!reallocated.contains(cm.tripId()))
                                fixed.add(cm);

                return new Input(time, routes, buses, trips, journeys, fixed);
        }

        // ---------------- save ----------------

        /**
         * Replaces this module's previous runs for the session with two new runs:
         * baseline (current assignments, no allocation logic) and proposed (after
         * allocation).
         * trip_assignments is NOT modified: the proposal is only stored (README
         * decision 6).
         */
        public long save(Connection c, long sessionId, Result r) throws SQLException {
                try (PreparedStatement del = c.prepareStatement(
                                "DELETE FROM allocation_runs WHERE session_id = ? AND notes LIKE ?")) {
                        del.setLong(1, sessionId);
                        del.setString(2, AUTO + "%");
                        del.executeUpdate(); // cascades to assignments + segment results
                }
                long baselineRun = insertRun(c, sessionId, "APPROVED", r.beforeTotal(),
                                AUTO + " baseline - current assignments, no allocation logic, allocation time "
                                                + r.time());
                long proposedRun = insertRun(c, sessionId, "PROPOSED", r.afterTotal(),
                                AUTO + " proposed - greedy allocation, allocation time " + r.time());

                String sql = "INSERT INTO allocation_assignments (allocation_run_id, trip_id, previous_bus_id, "
                                + "proposed_bus_id, assignment_type, reason) VALUES (?, ?, ?, ?, ?::allocation_assignment_type, ?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (TripOutcome o : r.outcomes()) {
                                // baseline: keep whatever bus the trip has today
                                addAssignment(ps, baselineRun, o.tripId(), o.previousBusId(), o.previousBusId(),
                                                o.previousBusId() == null ? "UNASSIGNED" : "UNCHANGED",
                                                o.previousBusId() == null ? "No bus assigned today."
                                                                : "Current assignment.");
                                addAssignment(ps, proposedRun, o.tripId(), o.previousBusId(), o.proposedBusId(),
                                                o.type(), o.reason());
                        }
                        ps.executeBatch();
                }
                insertSegments(c, baselineRun, r.beforeSegments());
                insertSegments(c, proposedRun, r.afterSegments());

                return proposedRun;
        }

        private long insertRun(Connection c, long sessionId, String status, Metrics m, String notes)
                        throws SQLException {
                String sql = "INSERT INTO allocation_runs (session_id, status, is_simulated, total_demand, "
                                + "total_accommodated, total_unmet_demand, total_unused_capacity, notes) "
                                + "VALUES (?, ?::allocation_status, FALSE, ?, ?, ?, ?, ?) RETURNING allocation_run_id";
                return query(c, sql, rs -> rs.getLong(1), sessionId, status, m.demand(), m.accommodated(),
                                m.unmet(), m.unused(), notes).get(0);
        }

        private void addAssignment(PreparedStatement ps, long run, long tripId, Long prev, Long next,
                        String type, String reason) throws SQLException {
                ps.setLong(1, run);
                ps.setLong(2, tripId);
                ps.setObject(3, prev, Types.BIGINT);
                ps.setObject(4, next, Types.BIGINT);
                ps.setString(5, type);
                ps.setString(6, reason);
                ps.addBatch();
        }

        private void insertSegments(Connection c, long run, List<SegmentRow> rows) throws SQLException {
                String sql = "INSERT INTO allocation_segment_results (allocation_run_id, route_id, segment_start_stop_id, "
                                + "segment_end_stop_id, demand, allocated_capacity, accommodated, unmet_demand, unused_capacity) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (SegmentRow s : rows) {
                                ps.setLong(1, run);
                                ps.setLong(2, s.routeId());
                                ps.setLong(3, s.startStopId());
                                ps.setLong(4, s.endStopId());
                                ps.setInt(5, s.demand());
                                ps.setInt(6, s.capacity());
                                ps.setInt(7, s.accommodated());
                                ps.setInt(8, s.unmet());
                                ps.setInt(9, s.unused());
                                ps.addBatch();
                        }
                        ps.executeBatch();
                }
        }
}
