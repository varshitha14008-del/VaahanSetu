package com.vaahansetu.allocation;

import static com.vaahansetu.allocation.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Pure allocation logic: no database access, so it can be tested and explained on its own.
 *
 * Objective: maximise actual passenger accommodation across trips while eliminating route-length bias.
 * Method: constraint-aware GREEDY - prioritises allocating buses to trips where they accommodate
 * the greatest physical passenger load (peak passengers on the vehicle, then length-normalized
 * average passenger occupancy), while respecting bus capacity, turnaround rest intervals,
 * daily rest windows, and prior commitments. Trips with large peak demand can receive multiple
 * buses when demand warrants it, and trips are not allocated unnecessary buses once satisfied.
 */
public final class AllocationEngine {
    private AllocationEngine() {}

    public static Result allocate(Input in) {
        Map<Long, Route> routes = new HashMap<>();
        for (Route r : in.routes()) routes.put(r.id(), r);

        Map<Long, int[]> demand = demandPerTrip(in, routes);

        // Busy intervals per bus start with fixed commitments (trips we must not touch).
        Map<Long, List<LocalDateTime[]>> busy = new HashMap<>();
        for (Bus b : in.buses()) busy.put(b.id(), new ArrayList<>());
        for (Commitment c : in.commitments()) {
            List<LocalDateTime[]> list = busy.get(c.busId());
            if (list != null) list.add(new LocalDateTime[]{c.start(), c.end()});
        }

        List<Bus> eligible = new ArrayList<>();                 // only ACTIVE buses can be assigned
        for (Bus b : in.buses()) if ("ACTIVE".equals(b.status())) eligible.add(b);
        eligible.sort(Comparator.comparingLong(Bus::id));

        // Map from tripId to list of allocated buses
        Map<Long, List<Bus>> tripBuses = new LinkedHashMap<>();
        for (Trip t : in.trips()) {
            tripBuses.put(t.id(), new ArrayList<>());
        }

        // Precompute peak demand per trip
        Map<Long, Integer> peakPerTrip = new HashMap<>();
        for (Trip t : in.trips()) {
            int[] d = demand.get(t.id());
            int peak = 0;
            for (int x : d) if (x > peak) peak = x;
            peakPerTrip.put(t.id(), peak);
        }

        // Iterative greedy multi-bus assignment:
        // Prioritizes buses by actual passengers accommodated on the vehicle (peakGain),
        // followed by length-normalized average passenger occupancy per segment to avoid
        // artificially inflating longer routes with more segments.
        while (true) {
            Trip bestTrip = null;
            Bus bestBus = null;
            int maxPeakGain = 0;
            double maxAvgGain = 0.0;

            for (Trip t : in.trips()) {
                int[] d = demand.get(t.id());
                int peak = peakPerTrip.get(t.id());
                List<Bus> assigned = tripBuses.get(t.id());
                int curCap = 0;
                for (Bus b : assigned) curCap += b.capacity();

                // Peak demand already accommodated: do not allocate unnecessary buses (Example C)
                if (curCap >= peak) continue;

                int curUnmet = unmet(d, curCap);
                if (curUnmet <= 0) continue;

                int segCount = Math.max(1, d.length);

                for (Bus b : eligible) {
                    if (assigned.contains(b)) continue;
                    if (!isFree(b, busy.get(b.id()), t) || overlapsFixedRestWindow(b, t)) continue;

                    int newCap = curCap + b.capacity();
                    int newUnmet = unmet(d, newCap);
                    int segGain = curUnmet - newUnmet;
                    if (segGain <= 0) continue;

                    int peakGain = Math.min(peak - curCap, b.capacity());
                    if (peakGain <= 0) continue;

                    double avgGain = (double) segGain / segCount;

                    boolean isBetter = false;
                    if (bestTrip == null) {
                        isBetter = true;
                    } else if (peakGain != maxPeakGain) {
                        // 1. Primary: actual peak passengers accommodated on the vehicle
                        isBetter = (peakGain > maxPeakGain);
                    } else if (Math.abs(avgGain - maxAvgGain) > 0.001) {
                        // 2. Secondary: length-normalized average passenger occupancy per segment
                        isBetter = (avgGain > maxAvgGain);
                    } else {
                        // 3. Tie-breakers: remaining unmet peak demand
                        int bestCurCap = 0;
                        for (Bus bb : tripBuses.get(bestTrip.id())) bestCurCap += bb.capacity();
                        int curRemainingPeak = peak - curCap;
                        int bestRemainingPeak = peakPerTrip.get(bestTrip.id()) - bestCurCap;
                        if (curRemainingPeak != bestRemainingPeak) {
                            isBetter = (curRemainingPeak > bestRemainingPeak);
                        } else {
                            // 4. Retain trip's current bus
                            boolean bIsCurrent = t.currentBusId() != null && b.id() == t.currentBusId();
                            boolean bestIsCurrent = bestTrip.currentBusId() != null && bestBus.id() == bestTrip.currentBusId();
                            if (bIsCurrent != bestIsCurrent) {
                                isBetter = bIsCurrent;
                            } else if (!t.departure().equals(bestTrip.departure())) {
                                // 5. Earlier departure
                                isBetter = t.departure().isBefore(bestTrip.departure());
                            } else {
                                // 6. Smaller trip ID, then smaller bus ID
                                isBetter = t.id() < bestTrip.id() || (t.id() == bestTrip.id() && b.id() < bestBus.id());
                            }
                        }
                    }

                    if (isBetter) {
                        bestTrip = t;
                        bestBus = b;
                        maxPeakGain = peakGain;
                        maxAvgGain = avgGain;
                    }
                }
            }

            if (bestTrip == null || bestBus == null || maxPeakGain <= 0) {
                break;
            }

            tripBuses.get(bestTrip.id()).add(bestBus);
            busy.get(bestBus.id()).add(new LocalDateTime[]{bestTrip.departure(), bestTrip.arrival()});
        }

        Map<Long, TripOutcome> outcomeByTrip = new HashMap<>();
        for (Trip t : in.trips()) {
            int[] d = demand.get(t.id());
            List<Bus> assigned = tripBuses.get(t.id());
            outcomeByTrip.put(t.id(), outcome(t, assigned, d));
        }

        // Keep outcomes in departure order for readable output.
        List<TripOutcome> outcomes = new ArrayList<>();
        for (Trip t : in.trips()) outcomes.add(outcomeByTrip.get(t.id()));

        List<SegmentRow> before = segments(in, routes, demand, Trip::currentBusCapacity);
        List<SegmentRow> after = segments(in, routes, demand,
                t -> {
                    List<Bus> buses = tripBuses.get(t.id());
                    if (buses == null || buses.isEmpty()) return 0;
                    int total = 0;
                    for (Bus b : buses) total += b.capacity();
                    return total;
                });

        return buildResult(in, routes, outcomes, before, after);
    }

    // ---------------- demand ----------------

    /** Segment i = stop i -> stop i+1. A journey boarding at stop b and leaving at stop d
     *  occupies segments b .. d-1 (it is not counted on the segment starting at its destination). */
    private static Map<Long, int[]> demandPerTrip(Input in, Map<Long, Route> routes) {
        Map<Long, Trip> trips = new HashMap<>();
        Map<Long, int[]> demand = new HashMap<>();
        for (Trip t : in.trips()) {
            trips.put(t.id(), t);
            demand.put(t.id(), new int[Math.max(0, routes.get(t.routeId()).stopIds().size() - 1)]);
        }
        for (Journey j : in.journeys()) {
            Trip t = trips.get(j.tripId());
            if (t == null) continue;
            List<Long> stops = routes.get(t.routeId()).stopIds();
            int from = stops.indexOf(j.boardingStopId());
            int to = stops.indexOf(j.destinationStopId());
            if (from < 0 || to <= from) continue;               // invalid journey: ignore
            int[] d = demand.get(t.id());
            for (int s = from; s < to; s++) d[s] += j.passengers();
        }
        for (int[] d : demand.values()) {
            for (int s = 0; s < d.length; s++) {
                if (d[s] < 0) d[s] = 0;
            }
        }
        return demand;
    }

    private static int sum(int[] a) { int s = 0; for (int x : a) s += x; return s; }

    private static int unmet(int[] demand, int capacity) {
        int u = 0;
        for (int d : demand) u += Math.max(0, d - capacity);
        return u;
    }

    // ---------------- availability ----------------

    /** Checks if two time intervals conflict respecting rest interval. */
    public static boolean isTurnaroundConflict(LocalDateTime dep1, LocalDateTime arr1,
                                               LocalDateTime dep2, LocalDateTime arr2,
                                               int restMinutes) {
        if (dep1 == null || arr1 == null || dep2 == null || arr2 == null) return false;
        boolean startsAfterOther = !dep1.isBefore(arr2.plusMinutes(restMinutes));
        boolean endsBeforeOther = !dep2.isBefore(arr1.plusMinutes(restMinutes));
        return !startsAfterOther && !endsBeforeOther;
    }

    /** A bus is free for a trip if it does not overlap any other trip, counting the bus's rest
     *  time after the earlier of the two trips. */
    private static boolean isFree(Bus bus, List<LocalDateTime[]> intervals, Trip t) {
        for (LocalDateTime[] iv : intervals) {
            if (isTurnaroundConflict(t.departure(), t.arrival(), iv[0], iv[1], bus.restMinutes())) {
                return false;
            }
        }
        return true;
    }

    /** Fixed daily rest window is independent of rest_duration_minutes. Handles windows crossing midnight. */
    public static boolean overlapsFixedRestWindow(java.time.LocalTime restStart, java.time.LocalTime restEnd,
                                                  LocalDateTime tripDeparture, LocalDateTime tripArrival) {
        if (restStart == null || restEnd == null || tripDeparture == null || tripArrival == null) return false;
        LocalDateTime day = tripDeparture.toLocalDate().atStartOfDay();
        // Include the preceding date because a rest window may cross midnight into departure day.
        for (int offset = -1; offset <= 2; offset++) {
            java.time.LocalDate d = day.toLocalDate().plusDays(offset);
            LocalDateTime start = d.atTime(restStart);
            LocalDateTime end = d.atTime(restEnd);
            if (restEnd.isBefore(restStart)) end = end.plusDays(1);
            if (tripDeparture.isBefore(end) && start.isBefore(tripArrival)) return true;
        }
        return false;
    }

    private static boolean overlapsFixedRestWindow(Bus bus, Trip trip) {
        if (!bus.hasFixedRestWindow()) return false;
        return overlapsFixedRestWindow(bus.restStart(), bus.restEnd(), trip.departure(), trip.arrival());
    }

    private static boolean isInsideFixedRestWindow(Bus bus, LocalDateTime time) {
        if (!bus.hasFixedRestWindow()) return false;
        java.time.LocalTime now = time.toLocalTime();
        if (bus.restStart().isBefore(bus.restEnd()))
            return !now.isBefore(bus.restStart()) && now.isBefore(bus.restEnd());
        return !now.isBefore(bus.restStart()) || now.isBefore(bus.restEnd());
    }

    // ---------------- results ----------------

    private static TripOutcome outcome(Trip t, List<Bus> assigned, int[] d) {
        Long prev = t.currentBusId();
        int peak = 0;
        for (int x : d) if (x > peak) peak = x;

        if (assigned == null || assigned.isEmpty()) {
            int unmetCount = unmet(d, 0);
            return new TripOutcome(t.id(), t.routeId(), prev, null, "UNASSIGNED",
                    String.format("Peak demand is %d passengers. No free active bus available (due to 30-min turnaround constraint, daily rest window, or prior commitments).", peak),
                    List.of(), peak, unmetCount);
        }

        int totalCap = 0;
        List<Long> busIds = new ArrayList<>();
        List<String> regNos = new ArrayList<>();
        for (Bus b : assigned) {
            totalCap += b.capacity();
            busIds.add(b.id());
            regNos.add(b.regNo());
        }

        int unmetCount = unmet(d, totalCap);
        int occupancyPercent = totalCap > 0 ? Math.min(100, (peak * 100) / totalCap) : 0;
        Long primaryBusId = busIds.get(0);

        if (assigned.size() > 1) {
            String regList = String.join(", ", regNos);
            if (unmetCount == 0) {
                return new TripOutcome(t.id(), t.routeId(), prev, primaryBusId, "REASSIGNED",
                        String.format("Assigned %d buses: %s (%d seats total) to accommodate high peak load of %d passengers (%d%% occupancy) with zero unmet demand.",
                                assigned.size(), regList, totalCap, peak, occupancyPercent),
                        busIds, peak, unmetCount);
            } else {
                return new TripOutcome(t.id(), t.routeId(), prev, primaryBusId, "REASSIGNED",
                        String.format("Assigned %d buses: %s (%d seats total) for %d peak passengers (%d%% occupancy); leaves %d unmet passenger-segments due to high route demand.",
                                assigned.size(), regList, totalCap, peak, occupancyPercent, unmetCount),
                        busIds, peak, unmetCount);
            }
        }

        Bus best = assigned.get(0);
        if (prev != null && prev.equals(best.id())) {
            if (unmetCount == 0) {
                return new TripOutcome(t.id(), t.routeId(), prev, best.id(), "UNCHANGED",
                        String.format("Retained Bus %s (%d seats): fully accommodates peak load (%d passengers, %d%% occupancy) while respecting 30-min rest constraint.",
                                best.regNo(), best.capacity(), peak, occupancyPercent),
                        busIds, peak, unmetCount);
            } else {
                return new TripOutcome(t.id(), t.routeId(), prev, best.id(), "UNCHANGED",
                        String.format("Retained Bus %s (%d seats): highest capacity available bus; leaves %d unmet passenger-segments due to high route demand.",
                                best.regNo(), best.capacity(), unmetCount),
                        busIds, peak, unmetCount);
            }
        }

        if (prev == null) {
            return new TripOutcome(t.id(), t.routeId(), prev, best.id(), "REASSIGNED",
                    String.format("Assigned Bus %s (%d seats) for %d peak passengers (%d%% occupancy); satisfies operational rest and scheduling constraints.",
                            best.regNo(), best.capacity(), peak, occupancyPercent),
                    busIds, peak, unmetCount);
        }

        int prevUnmet = unmet(d, t.currentBusCapacity());
        if (unmetCount < prevUnmet) {
            return new TripOutcome(t.id(), t.routeId(), prev, best.id(), "REASSIGNED",
                    String.format("Upgraded to larger Bus %s (%d seats vs %d seats): reduced unmet demand from %d to %d for peak load of %d passengers.",
                            best.regNo(), best.capacity(), t.currentBusCapacity(), prevUnmet, unmetCount, peak),
                    busIds, peak, unmetCount);
        } else {
            return new TripOutcome(t.id(), t.routeId(), prev, best.id(), "REASSIGNED",
                    String.format("Reassigned to Bus %s (%d seats): optimizes fleet utilization (%d%% occupancy) for %d peak passengers with zero unmet demand.",
                            best.regNo(), best.capacity(), occupancyPercent, peak),
                    busIds, peak, unmetCount);
        }
    }

    /** Calculates segment metrics accurately accumulated per trip so that unused capacity on
     *  off-peak trips does not erase unmet demand from peak rush hour trips. */
    private static List<SegmentRow> segments(Input in, Map<Long, Route> routes, Map<Long, int[]> demand,
                                             java.util.function.ToIntFunction<Trip> capacityOf) {
        Map<Long, int[]> dem = new LinkedHashMap<>();
        Map<Long, int[]> cap = new LinkedHashMap<>();
        Map<Long, int[]> acc = new LinkedHashMap<>();
        Map<Long, int[]> unm = new LinkedHashMap<>();
        Map<Long, int[]> unu = new LinkedHashMap<>();
        for (Route r : in.routes()) {
            int n = Math.max(0, r.stopIds().size() - 1);
            dem.put(r.id(), new int[n]);
            cap.put(r.id(), new int[n]);
            acc.put(r.id(), new int[n]);
            unm.put(r.id(), new int[n]);
            unu.put(r.id(), new int[n]);
        }
        for (Trip t : in.trips()) {
            int[] d = demand.get(t.id());
            int c = capacityOf.applyAsInt(t);
            for (int i = 0; i < d.length; i++) {
                dem.get(t.routeId())[i] += d[i];
                cap.get(t.routeId())[i] += c;
                acc.get(t.routeId())[i] += Math.min(d[i], c);
                unm.get(t.routeId())[i] += Math.max(0, d[i] - c);
                unu.get(t.routeId())[i] += Math.max(0, c - d[i]);
            }
        }
        List<SegmentRow> rows = new ArrayList<>();
        for (Route r : in.routes()) {
            for (int i = 0; i < dem.get(r.id()).length; i++) {
                rows.add(new SegmentRow(r.id(), r.stopIds().get(i), r.stopIds().get(i + 1),
                        dem.get(r.id())[i], cap.get(r.id())[i],
                        acc.get(r.id())[i], unm.get(r.id())[i], unu.get(r.id())[i]));
            }
        }
        return rows;
    }

    private static Metrics metrics(List<SegmentRow> rows) {
        int d = 0, c = 0, a = 0, u = 0, f = 0;
        for (SegmentRow r : rows) {
            d += r.demand(); c += r.capacity(); a += r.accommodated(); u += r.unmet(); f += r.unused();
        }
        return new Metrics(d, c, a, u, f);
    }

    private static List<SegmentRow> forRoute(List<SegmentRow> rows, long routeId) {
        List<SegmentRow> out = new ArrayList<>();
        for (SegmentRow r : rows) if (r.routeId() == routeId) out.add(r);
        return out;
    }

    private static Result buildResult(Input in, Map<Long, Route> routes,
                                      List<TripOutcome> outcomes, List<SegmentRow> before, List<SegmentRow> after) {
        // Which buses ended up on which trips / routes
        Map<Long, Set<String>> originalRoutes = new HashMap<>();
        Map<Long, Set<String>> newRoutes = new HashMap<>();
        Map<Long, List<Long>> busesPerRoute = new HashMap<>();
        for (Bus b : in.buses()) { originalRoutes.put(b.id(), new TreeSet<>()); newRoutes.put(b.id(), new TreeSet<>()); }
        for (Commitment c : in.commitments()) {
            if (originalRoutes.containsKey(c.busId())) {
                originalRoutes.get(c.busId()).add(c.routeCode());
                newRoutes.get(c.busId()).add(c.routeCode());
            }
        }
        for (TripOutcome o : outcomes) {
            String code = routes.get(o.routeId()).code();
            if (o.previousBusId() != null && originalRoutes.containsKey(o.previousBusId()))
                originalRoutes.get(o.previousBusId()).add(code);
            List<Long> assignedBusList = (o.proposedBusIds() != null && !o.proposedBusIds().isEmpty())
                    ? o.proposedBusIds()
                    : (o.proposedBusId() != null ? List.of(o.proposedBusId()) : List.of());
            for (Long bId : assignedBusList) {
                if (newRoutes.containsKey(bId)) {
                    newRoutes.get(bId).add(code);
                }
                List<Long> l = busesPerRoute.computeIfAbsent(o.routeId(), k -> new ArrayList<>());
                if (!l.contains(bId)) l.add(bId);
            }
        }

        List<RouteReport> routeReports = new ArrayList<>();
        for (Route r : in.routes()) {
            routeReports.add(new RouteReport(r.id(), r.code(), r.name(),
                    metrics(forRoute(before, r.id())), metrics(forRoute(after, r.id())),
                    busesPerRoute.getOrDefault(r.id(), List.of())));
        }

        Set<Long> assigned = new HashSet<>();
        for (TripOutcome o : outcomes) {
            if (o.proposedBusIds() != null && !o.proposedBusIds().isEmpty()) {
                assigned.addAll(o.proposedBusIds());
            } else if (o.proposedBusId() != null) {
                assigned.add(o.proposedBusId());
            }
        }
        int considered = 0;
        List<BusReport> busReports = new ArrayList<>();
        for (Bus b : in.buses()) {
            if ("ACTIVE".equals(b.status())) considered++;
            String reason = unavailableReason(b, in);
            busReports.add(new BusReport(b.id(), b.regNo(), b.capacity(),
                    join(originalRoutes.get(b.id())), join(newRoutes.get(b.id())), reason == null, reason));
        }
        int left = 0;
        for (Bus b : in.buses()) if ("ACTIVE".equals(b.status()) && !assigned.contains(b.id())) left++;

        return new Result(in.time(), metrics(before), metrics(after), considered, assigned.size(), left,
                routeReports, busReports, outcomes, before, after);
    }

    private static String join(Set<String> s) { return s.isEmpty() ? "-" : String.join(", ", s); }

    /** Why a bus cannot be used at the requested time (null = available). Uses the fixed
     *  commitments only, because those are the trips that really bind the bus at that moment. */
    private static String unavailableReason(Bus b, Input in) {
        if (!"ACTIVE".equals(b.status())) return "Bus status is " + b.status();
        LocalDateTime now = in.time();
        if (isInsideFixedRestWindow(b, now))
            return "Fixed daily rest window (" + b.restStart() + "-" + b.restEnd() + ")";
        for (Commitment c : in.commitments()) {
            if (c.busId() != b.id()) continue;
            if (!now.isBefore(c.start()) && now.isBefore(c.end()))
                return "On trip " + c.tripId() + " (" + c.routeCode() + ") until " + c.end();
            LocalDateTime restEnd = c.end().plusMinutes(b.restMinutes());
            if (!now.isBefore(c.end()) && now.isBefore(restEnd))
                return "Resting after trip " + c.tripId() + " until " + restEnd;
        }
        return null;
    }
}
