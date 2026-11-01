
package allocation.core;

import static allocation.core.AllocationModels.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Pure allocation logic with no database access.
 *
 * Objective: minimise total unmet passenger demand.
 * Method: constraint-aware GREEDY heuristic.
 *
 * Trips with the highest demand are considered first.
 * Each trip receives the available bus that minimises unmet demand.
 *
 * Note: Greedy allocation is not guaranteed to find the globally
 * optimal allocation.
 */
public final class AllocationEngine {

    private AllocationEngine() {
    }

    // =========================================================
    // MAIN ALLOCATION METHOD
    // =========================================================

    public static Result allocate(Input in) {

        validateInput(in);

        Map<Long, Route> routes = new HashMap<>();

        for (Route r : in.routes()) {
            routes.put(r.id(), r);
        }

        Map<Long, int[]> demand = demandPerTrip(in, routes);

        // Store fixed and newly allocated busy intervals for every bus.
        Map<Long, List<LocalDateTime[]>> busy = new HashMap<>();

        for (Bus b : in.buses()) {
            busy.put(b.id(), new ArrayList<>());
        }

        // Existing commitments cannot be changed.
        for (Commitment c : in.commitments()) {

            List<LocalDateTime[]> intervals = busy.get(c.busId());

            if (intervals != null) {
                intervals.add(new LocalDateTime[] {
                        c.start(),
                        c.end()
                });
            }
        }

        // Only ACTIVE buses can receive new assignments.
        List<Bus> eligible = new ArrayList<>();

        for (Bus b : in.buses()) {
            if ("ACTIVE".equals(b.status())) {
                eligible.add(b);
            }
        }

        eligible.sort(Comparator.comparingLong(Bus::id));

        // Process trips with the highest total demand first.
        List<Trip> order = new ArrayList<>(in.trips());

        order.sort(
                Comparator.comparingInt(
                        (Trip t) -> -sum(demand.get(t.id())))
                        .thenComparing(Trip::departure)
                        .thenComparingLong(Trip::id));

        Map<Long, Bus> proposed = new HashMap<>();

        Map<Long, TripOutcome> outcomeByTrip = new HashMap<>();

        for (Trip t : order) {

            int[] d = demand.get(t.id());

            Bus best = null;
            int bestUnmet = Integer.MAX_VALUE;

            for (Bus b : eligible) {

                if (!isFree(b, busy.get(b.id()), t)) {
                    continue;
                }

                if (overlapsFixedRestWindow(b, t)) {
                    continue;
                }

                int unmetDemand = unmet(d, b.capacity());

                if (best == null ||
                        isBetter(
                                b,
                                unmetDemand,
                                best,
                                bestUnmet,
                                t.currentBusId())) {

                    best = b;
                    bestUnmet = unmetDemand;
                }
            }

            if (best != null) {

                proposed.put(t.id(), best);

                busy.get(best.id()).add(
                        new LocalDateTime[] {
                                t.departure(),
                                t.arrival()
                        });
            }

            outcomeByTrip.put(
                    t.id(),
                    outcome(t, best, d));
        }

        // Keep the original trip order in the final report.
        List<TripOutcome> outcomes = new ArrayList<>();

        for (Trip t : in.trips()) {
            outcomes.add(outcomeByTrip.get(t.id()));
        }

        // Capacity before allocation.
        List<SegmentRow> before = segments(
                in,
                demand,
                t -> t.currentBusCapacity());

        // Capacity after allocation.
        List<SegmentRow> after = segments(
                in,
                demand,
                t -> proposed.containsKey(t.id())
                        ? proposed.get(t.id()).capacity()
                        : 0);

        return buildResult(
                in,
                routes,
                outcomes,
                before,
                after);
    }

    // =========================================================
    // INPUT VALIDATION
    // =========================================================

    private static void validateInput(Input in) {

        Objects.requireNonNull(in, "Allocation input cannot be null.");

        Objects.requireNonNull(in.time(), "Allocation time cannot be null.");
        Objects.requireNonNull(in.routes(), "Routes cannot be null.");
        Objects.requireNonNull(in.buses(), "Buses cannot be null.");
        Objects.requireNonNull(in.trips(), "Trips cannot be null.");
        Objects.requireNonNull(in.journeys(), "Journeys cannot be null.");
        Objects.requireNonNull(in.commitments(), "Commitments cannot be null.");

        Set<Long> routeIds = new HashSet<>();
        Set<Long> busIds = new HashSet<>();
        Set<Long> tripIds = new HashSet<>();

        for (Route r : in.routes()) {

            Objects.requireNonNull(r, "Route cannot be null.");
            Objects.requireNonNull(r.code(), "Route code cannot be null.");
            Objects.requireNonNull(r.name(), "Route name cannot be null.");
            Objects.requireNonNull(r.stopIds(), "Route stops cannot be null.");
            if (r.stopIds().size() < 2) {
                throw new IllegalArgumentException(
                        "Route " + r.id() + " must contain at least two stops.");
            }
            if (!routeIds.add(r.id())) {
                throw new IllegalArgumentException(
                        "Duplicate route ID: " + r.id());
            }

            Set<Long> stops = new HashSet<>();

            for (Long stopId : r.stopIds()) {

                if (stopId == null) {
                    throw new IllegalArgumentException(
                            "Route " + r.id() + " contains a null stop ID.");
                }

                if (!stops.add(stopId)) {
                    throw new IllegalArgumentException(
                            "Route " + r.id() + " contains duplicate stop ID: " + stopId);
                }

            }
        }

        for (Bus b : in.buses()) {

            Objects.requireNonNull(b, "Bus cannot be null.");
            Objects.requireNonNull(b.status(), "Bus status cannot be null.");

            if (!busIds.add(b.id())) {
                throw new IllegalArgumentException(
                        "Duplicate bus ID: " + b.id());
            }

            if (b.capacity() <= 0) {
                throw new IllegalArgumentException(
                        "Bus capacity must be positive for bus " + b.id());
            }

            if (b.restMinutes() < 0) {
                throw new IllegalArgumentException(
                        "Negative rest duration for bus " + b.id());
            }

            if ((b.restStart() == null) != (b.restEnd() == null)) {
                throw new IllegalArgumentException(
                        "Both fixed rest start and end times must be supplied for bus "
                                + b.id());
            }
        }

        for (Trip t : in.trips()) {

            Objects.requireNonNull(t, "Trip cannot be null.");
            Objects.requireNonNull(t.departure(), "Trip departure cannot be null.");
            Objects.requireNonNull(t.arrival(), "Trip arrival cannot be null.");

            if (!tripIds.add(t.id())) {
                throw new IllegalArgumentException(
                        "Duplicate trip ID: " + t.id());
            }

            // Prevent the inactive/missing-route NullPointerException.
            if (!routeIds.contains(t.routeId())) {
                throw new IllegalArgumentException(
                        "Trip " + t.id()
                                + " references missing or inactive route "
                                + t.routeId());
            }

            if (!t.departure().isBefore(t.arrival())) {
                throw new IllegalArgumentException(
                        "Trip " + t.id()
                                + " must arrive after its departure.");
            }

            if (t.currentBusCapacity() < 0) {
                throw new IllegalArgumentException(
                        "Negative current bus capacity for trip " + t.id());
            }
        }

        for (Journey j : in.journeys()) {

            Objects.requireNonNull(j, "Journey cannot be null.");

            // Validate only journeys belonging to trips in this allocation.
            Trip journeyTrip = null;

            for (Trip t : in.trips()) {
                if (t.id() == j.tripId()) {
                    journeyTrip = t;
                    break;
                }
            }

            if (journeyTrip == null) {
                continue;
            }

            if (j.passengers() <= 0) {
                throw new IllegalArgumentException(
                        "Passenger count must be positive for journey "
                                + j.tripId());
            }

            Route route = null;

            for (Route r : in.routes()) {
                if (r.id() == journeyTrip.routeId()) {
                    route = r;
                    break;
                }
            }

            List<Long> stops = route.stopIds();

            int from = stops.indexOf(j.boardingStopId());
            int to = stops.indexOf(j.destinationStopId());

            if (from < 0 || to < 0 || to <= from) {
                throw new IllegalArgumentException(
                        "Invalid boarding or destination stop for journey "
                                + j.tripId());
            }
        }

        for (Commitment c : in.commitments()) {

            Objects.requireNonNull(c, "Commitment cannot be null.");
            Objects.requireNonNull(c.start(), "Commitment start cannot be null.");
            Objects.requireNonNull(c.end(), "Commitment end cannot be null.");
            if (!busIds.contains(c.busId())) {
                throw new IllegalArgumentException(
                        "Commitment references unknown bus ID: " + c.busId());
            }
            if (!c.start().isBefore(c.end())) {
                throw new IllegalArgumentException(
                        "Commitment must end after it starts. Trip ID: "
                                + c.tripId());
            }
        }
    }

    // =========================================================
    // PASSENGER DEMAND CALCULATION
    // =========================================================

    /**
     * Segment i represents stop i -> stop i+1.
     *
     * A passenger boarding at stop b and leaving at stop d
     * contributes demand to segments b through d-1.
     */
    private static Map<Long, int[]> demandPerTrip(
            Input in,
            Map<Long, Route> routes) {

        Map<Long, Trip> trips = new HashMap<>();

        Map<Long, int[]> demand = new HashMap<>();

        for (Trip t : in.trips()) {

            trips.put(t.id(), t);

            Route route = routes.get(t.routeId());

            int segmentCount = Math.max(
                    0,
                    route.stopIds().size() - 1);

            demand.put(
                    t.id(),
                    new int[segmentCount]);
        }

        for (Journey j : in.journeys()) {

            Trip t = trips.get(j.tripId());

            // Ignore journeys not belonging to the trips being allocated.
            if (t == null) {
                continue;
            }

            Route route = routes.get(t.routeId());

            List<Long> stops = route.stopIds();

            int from = stops.indexOf(j.boardingStopId());

            int to = stops.indexOf(j.destinationStopId());

            // Ignore invalid journeys rather than counting them incorrectly.
            if (from < 0 || to <= from) {
                continue;
            }

            int[] d = demand.get(t.id());

            for (int s = from; s < to; s++) {

                d[s] = Math.addExact(
                        d[s],
                        j.passengers());
            }
        }

        return demand;
    }

    private static int sum(int[] values) {

        int total = 0;

        for (int value : values) {
            total = Math.addExact(total, value);
        }

        return total;
    }

    private static int unmet(int[] demand, int capacity) {

        int total = 0;

        for (int d : demand) {

            total = Math.addExact(
                    total,
                    Math.max(0, d - capacity));
        }

        return total;
    }

    // =========================================================
    // BUS AVAILABILITY
    // =========================================================

    /**
     * A bus cannot perform overlapping trips.
     *
     * The minimum rest period is required after the earlier trip.
     * A trip beginning exactly when the required rest ends is allowed.
     */
    private static boolean isFree(
            Bus bus,
            List<LocalDateTime[]> intervals,
            Trip trip) {

        for (LocalDateTime[] interval : intervals) {

            LocalDateTime existingStart = interval[0];
            LocalDateTime existingEnd = interval[1];

            boolean tripStartsAfterExisting = !trip.departure().isBefore(
                    existingEnd.plusMinutes(bus.restMinutes()));

            boolean tripEndsBeforeExisting = !existingStart.isBefore(
                    trip.arrival().plusMinutes(bus.restMinutes()));

            if (!tripStartsAfterExisting && !tripEndsBeforeExisting) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether a trip overlaps any fixed daily rest window.
     *
     * Works with:
     * - Normal rest windows, such as 12:00 to 13:00.
     * - Overnight rest windows, such as 22:00 to 06:00.
     * - Trips spanning multiple days.
     */
    private static boolean overlapsFixedRestWindow(
            Bus bus,
            Trip trip) {

        if (!bus.hasFixedRestWindow()) {
            return false;
        }

        LocalDate firstDate = trip.departure()
                .toLocalDate()
                .minusDays(1);

        LocalDate lastDate = trip.arrival()
                .toLocalDate();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {

            LocalDateTime start = date.atTime(bus.restStart());

            LocalDateTime end = date.atTime(bus.restEnd());

            if (bus.restEnd().isBefore(bus.restStart())) {
                end = end.plusDays(1);
            }

            boolean overlaps = trip.departure().isBefore(end)
                    && start.isBefore(trip.arrival());

            if (overlaps) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether a particular time falls inside the bus's
     * fixed daily rest window.
     */
    private static boolean isInsideFixedRestWindow(
            Bus bus,
            LocalDateTime time) {

        if (!bus.hasFixedRestWindow()) {
            return false;
        }

        java.time.LocalTime now = time.toLocalTime();

        if (bus.restStart().isBefore(bus.restEnd())) {

            return !now.isBefore(bus.restStart())
                    && now.isBefore(bus.restEnd());
        }

        // Overnight rest window.
        return !now.isBefore(bus.restStart())
                || now.isBefore(bus.restEnd());
    }

    /**
     * Bus selection priority:
     * 1. Lowest unmet passenger demand.
     * 2. Prefer the trip's current bus when tied.
     * 3. Prefer the smaller bus when still tied.
     * 4. Keep the first bus in sorted ID order for any remaining tie.
     */
    private static boolean isBetter(
            Bus bus,
            int unmetDemand,
            Bus best,
            int bestUnmet,
            Long currentBusId) {

        if (unmetDemand != bestUnmet) {
            return unmetDemand < bestUnmet;
        }

        boolean isCurrent = currentBusId != null && bus.id() == currentBusId;

        boolean bestIsCurrent = currentBusId != null && best.id() == currentBusId;

        if (isCurrent != bestIsCurrent) {
            return isCurrent;
        }

        return bus.capacity() < best.capacity();
    }

    // =========================================================
    // TRIP ALLOCATION OUTCOMES
    // =========================================================

    private static TripOutcome outcome(
            Trip trip,
            Bus best,
            int[] demand) {

        Long previousBus = trip.currentBusId();

        if (best == null) {

            return new TripOutcome(
                    trip.id(),
                    trip.routeId(),
                    previousBus,
                    null,
                    "UNASSIGNED",
                    "No ACTIVE bus is free for this full trip because of "
                            + "overlapping trips, minimum rest, fixed daily rest, "
                            + "or existing commitments.");
        }

        if (previousBus != null && previousBus.longValue() == best.id()) {

            return new TripOutcome(
                    trip.id(),
                    trip.routeId(),
                    previousBus,
                    best.id(),
                    "UNCHANGED",
                    "Current bus is already the best available choice.");
        }

        String reason = String.format(
                "Bus %s (capacity %d) leaves %d unmet passengers compared "
                        + "with %d using the current bus.",
                best.regNo(),
                best.capacity(),
                unmet(demand, best.capacity()),
                unmet(demand, trip.currentBusCapacity()));

        return new TripOutcome(
                trip.id(),
                trip.routeId(),
                previousBus,
                best.id(),
                "REASSIGNED",
                reason);
    }

    // =========================================================
    // SEGMENT-WISE CAPACITY AND DEMAND
    // =========================================================

    /**
     * Calculates total demand and capacity for each route segment.
     *
     * Capacity is added for each trip on that route.
     * An unassigned trip contributes zero proposed capacity.
     */
    private static List<SegmentRow> segments(
            Input in,
            Map<Long, int[]> demand,
            java.util.function.ToIntFunction<Trip> capacityOf) {

        Map<Long, int[]> segmentDemand = new LinkedHashMap<>();

        Map<Long, int[]> segmentCapacity = new LinkedHashMap<>();

        for (Route r : in.routes()) {

            int count = Math.max(
                    0,
                    r.stopIds().size() - 1);

            segmentDemand.put(r.id(), new int[count]);

            segmentCapacity.put(r.id(), new int[count]);
        }

        for (Trip t : in.trips()) {

            int[] d = demand.get(t.id());

            int[] routeDemand = segmentDemand.get(t.routeId());

            int[] routeCapacity = segmentCapacity.get(t.routeId());

            int capacity = capacityOf.applyAsInt(t);

            for (int i = 0; i < d.length; i++) {

                routeDemand[i] = Math.addExact(
                        routeDemand[i],
                        d[i]);

                routeCapacity[i] = Math.addExact(
                        routeCapacity[i],
                        capacity);
            }
        }

        List<SegmentRow> rows = new ArrayList<>();

        for (Route r : in.routes()) {

            int[] d = segmentDemand.get(r.id());

            int[] c = segmentCapacity.get(r.id());

            for (int i = 0; i < d.length; i++) {

                rows.add(
                        new SegmentRow(
                                r.id(),
                                r.stopIds().get(i),
                                r.stopIds().get(i + 1),
                                d[i],
                                c[i]));
            }
        }

        return rows;
    }

    // =========================================================
    // METRICS
    // =========================================================

    private static Metrics metrics(List<SegmentRow> rows) {

        int demand = 0;
        int capacity = 0;
        int accommodated = 0;
        int unmet = 0;
        int unused = 0;

        for (SegmentRow r : rows) {

            demand = Math.addExact(demand, r.demand());

            capacity = Math.addExact(capacity, r.capacity());

            accommodated = Math.addExact(
                    accommodated,
                    r.accommodated());

            unmet = Math.addExact(unmet, r.unmet());

            unused = Math.addExact(unused, r.unused());
        }

        return new Metrics(
                demand,
                capacity,
                accommodated,
                unmet,
                unused);
    }

    private static List<SegmentRow> forRoute(
            List<SegmentRow> rows,
            long routeId) {

        List<SegmentRow> result = new ArrayList<>();

        for (SegmentRow r : rows) {

            if (r.routeId() == routeId) {
                result.add(r);
            }
        }

        return result;
    }

    // =========================================================
    // FINAL RESULT AND REPORT GENERATION
    // =========================================================

    private static Result buildResult(
            Input in,
            Map<Long, Route> routes,
            List<TripOutcome> outcomes,
            List<SegmentRow> before,
            List<SegmentRow> after) {

        Map<Long, Set<String>> originalRoutes = new HashMap<>();

        Map<Long, Set<String>> newRoutes = new HashMap<>();

        Map<Long, List<Long>> busesPerRoute = new HashMap<>();

        for (Bus b : in.buses()) {

            originalRoutes.put(b.id(), new TreeSet<>());

            newRoutes.put(b.id(), new TreeSet<>());
        }

        // Existing fixed commitments remain on their original buses.
        for (Commitment c : in.commitments()) {

            if (originalRoutes.containsKey(c.busId())) {

                originalRoutes.get(c.busId()).add(c.routeCode());

                newRoutes.get(c.busId()).add(c.routeCode());
            }
        }

        // Add old and proposed assignments to the reports.
        for (TripOutcome o : outcomes) {

            Route route = routes.get(o.routeId());

            String routeCode = route.code();

            if (o.previousBusId() != null
                    && originalRoutes.containsKey(o.previousBusId())) {

                originalRoutes.get(o.previousBusId()).add(routeCode);
            }

            if (o.proposedBusId() != null) {

                newRoutes.get(o.proposedBusId()).add(routeCode);

                List<Long> assignedBuses = busesPerRoute.computeIfAbsent(
                        o.routeId(),
                        k -> new ArrayList<>());

                if (!assignedBuses.contains(o.proposedBusId())) {
                    assignedBuses.add(o.proposedBusId());
                }
            }
        }

        // Route reports.
        List<RouteReport> routeReports = new ArrayList<>();

        for (Route r : in.routes()) {

            routeReports.add(
                    new RouteReport(
                            r.id(),
                            r.code(),
                            r.name(),
                            metrics(forRoute(before, r.id())),
                            metrics(forRoute(after, r.id())),
                            busesPerRoute.getOrDefault(
                                    r.id(),
                                    List.of())));
        }

        // Count unique buses assigned to at least one proposed trip.
        Set<Long> assigned = new HashSet<>();

        for (TripOutcome o : outcomes) {

            if (o.proposedBusId() != null) {
                assigned.add(o.proposedBusId());
            }
        }

        int considered = 0;

        List<BusReport> busReports = new ArrayList<>();

        for (Bus b : in.buses()) {

            if ("ACTIVE".equals(b.status())) {
                considered++;
            }

            String reason = unavailableReason(b, in);

            busReports.add(
                    new BusReport(
                            b.id(),
                            b.regNo(),
                            b.capacity(),
                            join(originalRoutes.get(b.id())),
                            join(newRoutes.get(b.id())),
                            reason == null,
                            reason));
        }

        int leftUnassigned = 0;

        for (Bus b : in.buses()) {

            if ("ACTIVE".equals(b.status())
                    && !assigned.contains(b.id())) {

                leftUnassigned++;
            }
        }

        return new Result(
                in.time(),
                metrics(before),
                metrics(after),
                considered,
                assigned.size(),
                leftUnassigned,
                routeReports,
                busReports,
                outcomes,
                before,
                after);
    }

    private static String join(Set<String> routes) {

        if (routes == null || routes.isEmpty()) {
            return "-";
        }

        return String.join(", ", routes);
    }

    /**
     * Reports whether a bus is available at the allocation time.
     *
     * This checks fixed commitments and fixed daily rest only.
     * It does not consider newly proposed assignments.
     */
    private static String unavailableReason(
            Bus bus,
            Input in) {

        if (!"ACTIVE".equals(bus.status())) {
            return "Bus status is " + bus.status();
        }

        LocalDateTime now = in.time();

        if (isInsideFixedRestWindow(bus, now)) {

            return "Fixed daily rest window ("
                    + bus.restStart()
                    + "-"
                    + bus.restEnd()
                    + ")";
        }

        for (Commitment c : in.commitments()) {

            if (c.busId() != bus.id()) {
                continue;
            }

            if (!now.isBefore(c.start()) && now.isBefore(c.end())) {

                return "On trip "
                        + c.tripId()
                        + " ("
                        + c.routeCode()
                        + ") until "
                        + c.end();
            }

            LocalDateTime restEnd = c.end().plusMinutes(bus.restMinutes());

            if (!now.isBefore(c.end()) && now.isBefore(restEnd)) {

                return "Resting after trip "
                        + c.tripId()
                        + " until "
                        + restEnd;
            }
        }

        return null;
    }
}
