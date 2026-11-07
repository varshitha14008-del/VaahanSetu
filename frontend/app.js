"use strict";

const API_BASE_URL = window.VAAHAN_API_URL || (
    (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1")
        ? "http://localhost:8080/api"
        : `${window.location.origin}/api`
);

const journeyForm = document.getElementById("journeyForm");
const travelDate = document.getElementById("travelDate");
const routeSelect = document.getElementById("route");
const tripSelect = document.getElementById("tripSelect");
const boardingSelect = document.getElementById("boarding");
const destinationSelect = document.getElementById("destination");
const message = document.getElementById("message");

const routeDetails = document.getElementById("routeDetails");
const routeCode = document.getElementById("routeCode");
const routeOrigin = document.getElementById("routeOrigin");
const routeDestination = document.getElementById("routeDestination");
const routeDuration = document.getElementById("routeDuration");

const decreasePassengers = document.getElementById("decreasePassengers");
const increasePassengers = document.getElementById("increasePassengers");
const passengerCountValue = document.getElementById("passengerCountValue");

const busDetails = document.getElementById("busDetails");
const tripBusAssignment = document.getElementById("tripBusAssignment");
const busCount = document.getElementById("busCount");
const busNumbers = document.getElementById("busNumbers");

const confirmationCard = document.getElementById("confirmationCard");
const waitingCard = document.getElementById("waitingCard");
const demandList = document.getElementById("demandList");

const confirmJourneyId = document.getElementById("confirmJourneyId");
const confirmRoute = document.getElementById("confirmRoute");
const confirmTrip = document.getElementById("confirmTrip");
const confirmBoarding = document.getElementById("confirmBoarding");
const confirmDestination = document.getElementById("confirmDestination");
const confirmPassengers = document.getElementById("confirmPassengers");
const confirmStatus = document.getElementById("confirmStatus");

const boardJourneyBtn = document.getElementById("boardJourneyBtn");
const completeJourneyBtn = document.getElementById("completeJourneyBtn");
const cancelJourneyBtn = document.getElementById("cancelJourneyBtn");
const actionFeedback = document.getElementById("actionFeedback");

let passengerCount = 1;
let routes = [];
let routeTrips = [];

let busPollingTimer = null;
let currentBusRouteId = null;

let statusPollingTimer = null;
let activeJourneyId = null;
let currentInquiryId = null;

// ======================================
// PASSENGER COUNT
// ======================================

function updatePassengerCount() {
    passengerCountValue.textContent = passengerCount;
    passengerCountValue.setAttribute(
        "aria-label",
        `${passengerCount} passengers, minimum 1 and maximum 10`
    );
}

increasePassengers.addEventListener("click", function () {
    if (passengerCount < 10) {
        passengerCount++;
        updatePassengerCount();
        if (destinationSelect && destinationSelect.value) {
            logSearchIntent();
        }
    }
});

decreasePassengers.addEventListener("click", function () {
    if (passengerCount > 1) {
        passengerCount--;
        updatePassengerCount();
        if (destinationSelect && destinationSelect.value) {
            logSearchIntent();
        }
    }
});

// ======================================
// DROPDOWN FUNCTION
// ======================================

function populateDropdown(selectElement, items, placeholder) {
    selectElement.replaceChildren();

    const firstOption = document.createElement("option");
    firstOption.value = "";
    firstOption.textContent = placeholder;
    selectElement.appendChild(firstOption);

    items.forEach(function (item) {
        const option = document.createElement("option");
        option.value = item.value;
        option.textContent = item.label;
        selectElement.appendChild(option);
    });
}

// ======================================
// LOAD ROUTES
// ======================================

async function loadRoutes() {
    try {
        message.textContent = "Loading routes...";

        const response = await fetch(
            `${API_BASE_URL}/routes`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error(`Server returned HTTP ${response.status}`);
        }

        routes = await response.json();

        if (!Array.isArray(routes) || routes.length === 0) {
            routes = [];
            message.textContent = "No routes are currently available for the active planning session.";
            populateDropdown(routeSelect, [], "No routes available");
            if (routeDetails) routeDetails.hidden = true;
            if (busDetails) busDetails.hidden = true;
            populateDropdown(tripSelect, [], "No scheduled departures");
            populateDropdown(boardingSelect, [], "No stops available");
            populateDropdown(destinationSelect, [], "No destination available");
            return;
        }

        const routeItems = routes.map(function (route) {
            const rCode = route.routeCode || route.routeName || ('Route #' + route.routeId);
            const rOrigin = route.originName || route.origin || 'Origin';
            const rDest = route.destinationName || route.destination || 'Destination';
            return {
                value: route.routeId,
                label: `${rCode} (${rOrigin} → ${rDest})`
            };
        });

        populateDropdown(routeSelect, routeItems, "Choose a route");
        message.textContent = "";

    } catch (error) {
        console.error("Route loading error:", error);
        routes = [];
        message.textContent = "Unable to connect to backend service. Please check connection.";
        populateDropdown(routeSelect, [], "Could not load routes");
        if (routeDetails) routeDetails.hidden = true;
        if (busDetails) busDetails.hidden = true;
        populateDropdown(tripSelect, [], "No scheduled departures");
        populateDropdown(boardingSelect, [], "No stops available");
        populateDropdown(destinationSelect, [], "No destination available");
    }
}

// ======================================
// LOAD SCHEDULED TRIPS FOR ROUTE
// ======================================

async function loadScheduledTrips(routeId) {
    try {
        const dateParam = travelDate && travelDate.value ? `?date=${encodeURIComponent(travelDate.value)}` : "";
        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/trips${dateParam}`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load trips.");
        }

        routeTrips = await response.json();

        if (!Array.isArray(routeTrips) || routeTrips.length === 0) {
            populateDropdown(tripSelect, [], "No scheduled departures today");
            updateTripBusAssignment();
            return;
        }

        const tripItems = routeTrips.map(function (trip) {
            const dep = trip.departureTimeFormatted || (trip.departureTime ? trip.departureTime.substring(11, 16) : "Dep");
            const arr = trip.arrivalTimeFormatted || (trip.expectedArrivalTime ? trip.expectedArrivalTime.substring(11, 16) : "Arr");
            const busInfo = trip.registrationNumber && trip.registrationNumber !== "UNASSIGNED"
                ? ` [Bus: ${trip.registrationNumber} (${trip.capacity} seats)]`
                : " [Pending allocation]";
            return {
                value: trip.tripId,
                label: `Departure ${dep} - Arrival ${arr}${busInfo}`
            };
        });

        populateDropdown(tripSelect, tripItems, "Choose a scheduled departure");
        if (tripItems.length > 0) {
            tripSelect.selectedIndex = 1;
        }
        updateTripBusAssignment();

    } catch (error) {
        console.error("Trip loading error:", error);
        populateDropdown(tripSelect, [], "Could not load trips");
        updateTripBusAssignment();
    }
}

function updateTripBusAssignment() {
    if (!tripBusAssignment) return;
    if (!tripSelect || !tripSelect.value) {
        tripBusAssignment.textContent = "Choose a scheduled departure to view assigned bus";
        return;
    }
    const selectedTrip = Array.isArray(routeTrips)
        ? routeTrips.find(function (t) { return String(t.tripId) === String(tripSelect.value); })
        : null;
    if (selectedTrip && selectedTrip.registrationNumber && selectedTrip.registrationNumber !== "UNASSIGNED") {
        tripBusAssignment.innerHTML = `<strong style="color: #0369a1;">${selectedTrip.registrationNumber}</strong> (${selectedTrip.capacity} seats)`;
    } else {
        tripBusAssignment.innerHTML = `<span style="color: #64748b;">Pending Allocation</span>`;
    }
}

if (tripSelect) {
    tripSelect.addEventListener("change", function () {
        updateTripBusAssignment();
        logSearchIntent();
    });
}

// ======================================
// LOAD PASSENGER DEMAND (PER SCHEDULED TRIP)
// ======================================

async function loadPassengerDemand() {
    try {
        const params = new URLSearchParams();
        if (travelDate && travelDate.value) {
            params.append("date", travelDate.value);
        }
        if (routeSelect && routeSelect.value) {
            params.append("routeId", routeSelect.value);
        }
        const queryString = params.toString() ? `?${params.toString()}` : "";
        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys/trips/demand${queryString}`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load passenger demand.");
        }

        const demandData = await response.json();
        demandList.replaceChildren();

        if (!Array.isArray(demandData) || demandData.length === 0) {
            if (routeSelect && routeSelect.value) {
                demandList.hidden = false;
                const emptyCard = document.createElement("div");
                emptyCard.className = "demand-item";
                emptyCard.style.textAlign = "center";
                emptyCard.style.color = "#64748b";
                emptyCard.style.background = "#f8fafc";
                emptyCard.style.borderColor = "#cbd5e1";
                emptyCard.innerHTML = "<p style='color: #64748b; font-weight: normal; margin: 0;'>No scheduled departures found for this route on the selected date.</p>";
                demandList.appendChild(emptyCard);
            } else {
                demandList.hidden = true;
            }
            return;
        }

        demandList.hidden = false;

        // If any displayed routes have route-level adjustments, display them separately ONCE per route
        const routesWithAdjustments = [];
        const seenRouteIds = new Set();
        demandData.forEach(function (t) {
            const adj = t.routeAdminAdjustment != null ? t.routeAdminAdjustment : (t.routeAdjustmentCount || 0);
            if (adj !== 0 && !seenRouteIds.has(t.routeId)) {
                seenRouteIds.add(t.routeId);
                routesWithAdjustments.push({
                    routeId: t.routeId,
                    routeCode: t.routeCode || t.routeName || `Route #${t.routeId}`,
                    adj: adj
                });
            }
        });

        if (routesWithAdjustments.length > 0) {
            const adjBanner = document.createElement("div");
            adjBanner.className = "route-adjustment-banner";
            adjBanner.style.background = "#f0fdfa";
            adjBanner.style.border = "1px dashed #0d9488";
            adjBanner.style.borderRadius = "10px";
            adjBanner.style.padding = "10px 14px";
            adjBanner.style.fontSize = "13px";
            adjBanner.style.color = "#0f766e";
            adjBanner.style.fontWeight = "500";
            const adjDetails = routesWithAdjustments.map(function (r) {
                return `<strong>${r.routeCode}</strong>: ${r.adj > 0 ? '+' : ''}${r.adj} route-level adjustment`;
            }).join(" | ");
            adjBanner.innerHTML = `<strong>Route-Level Adjustments:</strong> ${adjDetails} (applied across route segments, separate from individual departures).`;
            demandList.appendChild(adjBanner);
        }

        demandData.forEach(function (trip) {
            const tripId = trip.scheduledTripId || trip.tripId;
            const rCode = trip.routeCode || trip.routeName || `Route #${trip.routeId}`;
            const rOrigin = trip.originName || "Origin";
            const rDest = trip.destinationName || "Destination";
            const depTime = trip.departureTimeFormatted || (trip.departureTime ? trip.departureTime.substring(11, 16) : "--:--");
            const arrTime = trip.arrivalTimeFormatted || (trip.arrivalTime ? trip.arrivalTime.substring(11, 16) : "--:--");
            
            const total = trip.totalDemand != null ? trip.totalDemand : (trip.passengerCount || 0);
            const booked = trip.bookedPassengerCount != null ? trip.bookedPassengerCount : (trip.bookedCount || 0);
            const inquiries = trip.activeInquiryCount != null ? trip.activeInquiryCount : (trip.searchCount || 0);
            const bus = trip.assignedBus || trip.busRegistration;
            const cap = trip.busCapacity != null ? trip.busCapacity : (trip.capacity || 0);
            const occ = trip.occupancyPercentage != null ? trip.occupancyPercentage : 0;
            const isOvercap = trip.overcapacity === true || trip.isOvercapacity === true;

            const demandCard = document.createElement("div");
            demandCard.className = "demand-item trip-demand-card";

            // Trip Header: Departure Time, Route Code, and Occupancy Badge
            const header = document.createElement("div");
            header.style.display = "flex";
            header.style.justifyContent = "space-between";
            header.style.alignItems = "center";
            header.style.flexWrap = "wrap";
            header.style.gap = "8px";
            header.style.marginBottom = "8px";

            const title = document.createElement("h3");
            title.style.margin = "0";
            title.style.fontSize = "16px";
            title.textContent = `Departure: ${depTime} - ${arrTime} (${rCode})`;

            let badgeHtml = "";
            if (isOvercap) {
                badgeHtml = `<span class="status-badge" style="background:#fee2e2; color:#991b1b; font-size:12px; font-weight:600; padding:3px 8px; border-radius:6px;">Overcapacity (${occ}% - ${booked}/${cap} booked)</span>`;
            } else if (cap > 0) {
                const badgeBg = occ > 75 ? "#fef3c7" : "#e0f2fe";
                const badgeColor = occ > 75 ? "#92400e" : "#0369a1";
                badgeHtml = `<span class="status-badge" style="background:${badgeBg}; color:${badgeColor}; font-size:12px; font-weight:600; padding:3px 8px; border-radius:6px;">${occ}% Occupancy (${booked}/${cap} booked)</span>`;
            } else {
                badgeHtml = `<span class="status-badge" style="background:#f1f5f9; color:#475569; font-size:12px; font-weight:600; padding:3px 8px; border-radius:6px;">Pending Allocation</span>`;
            }

            const badgeSpan = document.createElement("div");
            badgeSpan.innerHTML = badgeHtml;

            header.appendChild(title);
            header.appendChild(badgeSpan);
            demandCard.appendChild(header);

            // Route info subheader
            const routeSub = document.createElement("p");
            routeSub.style.margin = "0 0 6px 0";
            routeSub.style.fontSize = "13px";
            routeSub.style.color = "#64748b";
            routeSub.style.fontWeight = "normal";
            routeSub.textContent = `${rOrigin} → ${rDest}`;
            demandCard.appendChild(routeSub);

            // Bus Assignment info
            const busInfo = document.createElement("p");
            busInfo.style.margin = "0 0 8px 0";
            busInfo.style.fontSize = "13px";
            busInfo.style.color = "#334155";
            busInfo.style.fontWeight = "normal";
            if (bus && bus !== "UNASSIGNED") {
                busInfo.innerHTML = `<strong>Bus assigned to this trip:</strong> ${bus} (${cap} seats)`;
            } else {
                busInfo.innerHTML = `<strong>Bus assigned to this trip:</strong> <span style="color:#64748b;">Pending bus allocation</span>`;
            }
            demandCard.appendChild(busInfo);

            // Demand Count with breakdown (strictly trip-level booked & inquiry passengers)
            const count = document.createElement("p");
            count.className = "passenger-count";
            count.style.margin = "0";
            count.style.fontSize = "15px";

            const breakdown = [];
            breakdown.push(`<strong>${booked}</strong> booked`);
            if (inquiries > 0) breakdown.push(`<strong>${inquiries}</strong> active inquiries`);
            const sub = breakdown.length > 0 ? ` [${breakdown.join(', ')}]` : '';

            count.innerHTML = `<strong>${total}</strong> recorded passenger demand${sub}`;
            demandCard.appendChild(count);

            demandList.appendChild(demandCard);
        });

    } catch (error) {
        console.error("Demand loading error:", error);
        demandList.hidden = true;
    }
}

// Record search intent into common demand
async function logSearchIntent() {
    if (!routeSelect.value || !boardingSelect.value || !destinationSelect.value) return;
    try {
        const payload = {
            routeId: Number(routeSelect.value),
            tripId: tripSelect.value ? Number(tripSelect.value) : null,
            boardingStopId: Number(boardingSelect.value),
            destinationStopId: Number(destinationSelect.value),
            passengerCount: passengerCount
        };
        if (currentInquiryId) {
            payload.inquiryId = currentInquiryId;
        }

        const response = await fetch(`${API_BASE_URL}/passenger-journeys/intent`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (response.ok) {
            const data = await response.json();
            if (data && (data.inquiryId || data.journeyId)) {
                currentInquiryId = data.inquiryId || data.journeyId;
            }
        }
        loadPassengerDemand();
    } catch (e) {
        console.warn('Search intent logging:', e);
    }
}

// ======================================
// LOAD STOPS
// ======================================

async function loadStops(routeId) {
    try {
        message.textContent = "Loading route stops...";

        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/stops`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load stops.");
        }

        const stops = await response.json();

        const selectedRoute = routes.find(function (route) {
            return String(route.routeId) === String(routeId);
        });

        if (selectedRoute) {
            selectedRoute.stops = stops;
        }

        if (!Array.isArray(stops) || stops.length === 0) {
            message.textContent = "No stops found for this route.";
            populateDropdown(boardingSelect, [], "No stops available");
            populateDropdown(destinationSelect, [], "No destination available");
            return;
        }

        const boardingItems = stops.map(function (stop) {
            return {
                value: stop.stopId,
                label: stop.stopName
            };
        });

        populateDropdown(boardingSelect, boardingItems, "Choose boarding stop");
        populateDropdown(destinationSelect, [], "Choose destination");
        message.textContent = "";

    } catch (error) {
        console.error("Stop loading error:", error);
        message.textContent = "Could not load stops for this route.";
        populateDropdown(boardingSelect, [], "Could not load stops");
        populateDropdown(destinationSelect, [], "Could not load destination");
    }
}

// ======================================
// LOAD ALLOCATED BUSES
// ======================================

async function loadAllocatedBuses(routeId) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/buses`,
            {
                method: "GET",
                cache: "no-store"
            }
        );

        if (!response.ok) {
            throw new Error("Could not load allocated buses.");
        }

        const buses = await response.json();

        if (
            String(routeSelect.value) !== String(routeId) ||
            currentBusRouteId !== String(routeId)
        ) {
            return;
        }

        busDetails.hidden = false;

        if (!Array.isArray(buses) || buses.length === 0) {
            busCount.textContent = "No buses currently assigned to this route.";
            busNumbers.textContent = "-";
            updateTripBusAssignment();
            return;
        }

        // Deduplicate unique buses for route fleet allocation
        const uniqueBusesMap = new Map();
        buses.forEach(function (b) {
            const reg = b.registrationNumber || b.busRegistration;
            if (reg && !uniqueBusesMap.has(reg)) {
                uniqueBusesMap.set(reg, b);
            }
        });
        const uniqueBuses = Array.from(uniqueBusesMap.values());

        busCount.textContent = `${uniqueBuses.length} bus(es) allocated to this route (across ${uniqueBuses.length} scheduled departures)`;
        busNumbers.textContent = uniqueBuses.map(function (bus) {
            return `Registration Number: ${bus.registrationNumber}\nActual Capacity: ${bus.capacity} seats`;
        }).join("\n\n");

        updateTripBusAssignment();

    } catch (error) {
        console.error("Bus loading error:", error);
    }
}

function stopBusPolling() {
    if (busPollingTimer !== null) {
        clearTimeout(busPollingTimer);
        busPollingTimer = null;
    }
    currentBusRouteId = null;
}

function startBusPolling(routeId) {
    stopBusPolling();
    currentBusRouteId = String(routeId);
    busDetails.hidden = false;
    busCount.textContent = "Checking bus availability...";
    busNumbers.textContent = "";

    async function refreshBuses() {
        if (currentBusRouteId !== String(routeId)) return;
        await loadAllocatedBuses(routeId);
        if (currentBusRouteId === String(routeId)) {
            busPollingTimer = setTimeout(refreshBuses, 6000);
        }
    }
    refreshBuses();
}

// ======================================
// ROUTE CHANGE EVENT
// ======================================

routeSelect.addEventListener("change", async function () {
    currentInquiryId = null;
    message.textContent = "";
    actionFeedback.textContent = "";
    confirmationCard.hidden = true;
    waitingCard.hidden = true;

    loadPassengerDemand();

    if (!routeSelect.value) {
        stopBusPolling();
        routeDetails.hidden = true;
        busDetails.hidden = true;
        if (tripBusAssignment) tripBusAssignment.textContent = "";
        busCount.textContent = "";
        busNumbers.textContent = "";
        populateDropdown(tripSelect, [], "Choose a scheduled departure");
        populateDropdown(boardingSelect, [], "Choose boarding stop");
        populateDropdown(destinationSelect, [], "Choose destination");
        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute) {
        message.textContent = "Selected route not found.";
        return;
    }

    routeCode.textContent = selectedRoute.routeCode || selectedRoute.routeName || "-";
    routeOrigin.textContent = selectedRoute.originName || "-";
    routeDestination.textContent = selectedRoute.destinationName || "-";
    routeDuration.textContent = selectedRoute.estimatedDurationMinutes
        ? selectedRoute.estimatedDurationMinutes + " minutes"
        : "Not available";
    routeDetails.hidden = false;

    startBusPolling(routeSelect.value);
    await loadScheduledTrips(routeSelect.value);
    await loadStops(routeSelect.value);
});

// ======================================
// BOARDING STOP CHANGE
// ======================================

boardingSelect.addEventListener("change", function () {
    currentInquiryId = null;
    populateDropdown(destinationSelect, [], "Choose destination");

    if (!boardingSelect.value || !routeSelect.value) return;

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute || !Array.isArray(selectedRoute.stops)) return;

    const boardingIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === boardingSelect.value;
    });

    if (boardingIndex === -1) return;

    const laterStops = selectedRoute.stops
        .slice(boardingIndex + 1)
        .map(function (stop) {
            return {
                value: stop.stopId,
                label: stop.stopName
            };
        });

    populateDropdown(destinationSelect, laterStops, "Choose destination");
});

destinationSelect.addEventListener("change", function () {
    if (destinationSelect.value) {
        logSearchIntent();
    }
});

// ======================================
// CHECK JOURNEY STATUS
// ======================================

async function checkJourneyStatus(journeyId) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys/${journeyId}`,
            {
                method: "GET",
                cache: "no-store"
            }
        );

        if (!response.ok) throw new Error("Could not check journey status.");

        const journey = await response.json();
        if (String(activeJourneyId) !== String(journeyId)) return;

        updateStatusBadge(journey.journeyStatus);

        if (journey.journeyStatus === "REGISTERED") {
            statusPollingTimer = setTimeout(function () {
                checkJourneyStatus(journeyId);
            }, 4000);
        } else {
            activeJourneyId = null;
            statusPollingTimer = null;
        }

    } catch (error) {
        console.error("Could not check journey status:", error);
    }
}

function updateStatusBadge(status) {
    confirmStatus.textContent = status;
    if (status === "REGISTERED") {
        confirmStatus.style.background = "#fef3c7";
        confirmStatus.style.color = "#92400e";
        boardJourneyBtn.disabled = false;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = false;
    } else if (status === "BOARDED") {
        confirmStatus.style.background = "#dbeafe";
        confirmStatus.style.color = "#1e40af";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = false;
        cancelJourneyBtn.disabled = true;
    } else if (status === "COMPLETED") {
        confirmStatus.style.background = "#dcfce7";
        confirmStatus.style.color = "#166534";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = true;
    } else if (status === "CANCELLED") {
        confirmStatus.style.background = "#fee2e2";
        confirmStatus.style.color = "#991b1b";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = true;
    }
}

function startStatusPolling(journeyId) {
    if (statusPollingTimer !== null) {
        clearTimeout(statusPollingTimer);
    }
    activeJourneyId = String(journeyId);
    statusPollingTimer = null;
    checkJourneyStatus(journeyId);
}

// ======================================
// JOURNEY ACTION BUTTONS (BOARD / COMPLETE / CANCEL)
// ======================================

async function updateJourneyAction(newStatus) {
    if (!confirmJourneyId.textContent || confirmJourneyId.textContent === "-") return;
    const jId = confirmJourneyId.textContent;

    actionFeedback.textContent = `Updating journey status to ${newStatus}...`;
    try {
        const resp = await fetch(`${API_BASE_URL}/passenger-journeys/${jId}/status`, {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ status: newStatus })
        });
        const res = await resp.json();
        if (!resp.ok) {
            throw new Error(res.message || "Failed to update status.");
        }
        updateStatusBadge(newStatus);
        actionFeedback.textContent = `Status successfully updated: ${newStatus}`;
        if (newStatus === "CANCELLED" || newStatus === "COMPLETED") {
            if (statusPollingTimer !== null) {
                clearTimeout(statusPollingTimer);
                statusPollingTimer = null;
            }
            activeJourneyId = null;
        }
        await loadPassengerDemand();
    } catch (e) {
        actionFeedback.textContent = e.message || "Status update failed.";
    }
}

boardJourneyBtn.addEventListener("click", () => updateJourneyAction("BOARDED"));
completeJourneyBtn.addEventListener("click", () => updateJourneyAction("COMPLETED"));
cancelJourneyBtn.addEventListener("click", () => updateJourneyAction("CANCELLED"));

// ======================================
// JOURNEY FORM SUBMISSION
// ======================================

journeyForm.addEventListener("submit", async function (event) {
    event.preventDefault();

    const submitButton = journeyForm.querySelector('button[type="submit"]');
    if (submitButton.disabled) return;

    confirmationCard.hidden = true;
    waitingCard.hidden = true;
    actionFeedback.textContent = "";

    if (!routeSelect.value || !boardingSelect.value || !destinationSelect.value) {
        message.textContent = "Please select a route, boarding stop and destination.";
        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute || !Array.isArray(selectedRoute.stops)) {
        message.textContent = "Please select a valid route.";
        return;
    }

    const boardingIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === boardingSelect.value;
    });

    const destinationIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === destinationSelect.value;
    });

    if (boardingIndex < 0 || destinationIndex <= boardingIndex) {
        message.textContent = "Choose a destination after your boarding stop.";
        return;
    }

    const journeyData = {
        routeId: Number(routeSelect.value),
        boardingStopId: Number(boardingSelect.value),
        destinationStopId: Number(destinationSelect.value),
        passengerCount: passengerCount
    };

    if (tripSelect.value) {
        journeyData.tripId = Number(tripSelect.value);
    }
    if (currentInquiryId) {
        journeyData.inquiryId = currentInquiryId;
    }

    const boardingName = boardingSelect.options[boardingSelect.selectedIndex].textContent;
    const destinationName = destinationSelect.options[destinationSelect.selectedIndex].textContent;
    const tripName = tripSelect.selectedIndex > 0 ? tripSelect.options[tripSelect.selectedIndex].textContent : "Earliest Available";

    submitButton.disabled = true;
    submitButton.textContent = "Submitting...";

    try {
        message.textContent = "Submitting your journey...";

        const response = await fetch(`${API_BASE_URL}/passenger-journeys`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(journeyData)
        });

        const result = await response.json();

        if (!response.ok) {
            throw new Error(result.message || "Journey submission failed.");
        }

        currentInquiryId = null;

        message.textContent = `Journey registered successfully! Journey ID: ${result.journeyId}.`;

        confirmJourneyId.textContent = result.journeyId;
        confirmRoute.textContent = `${selectedRoute.routeCode || selectedRoute.routeName} - ${selectedRoute.originName} → ${selectedRoute.destinationName}`;
        confirmTrip.textContent = tripName;
        confirmBoarding.textContent = boardingName;
        confirmDestination.textContent = destinationName;
        confirmPassengers.textContent = passengerCount;
        updateStatusBadge(result.journeyStatus || "REGISTERED");

        waitingCard.hidden = true;
        confirmationCard.hidden = false;

        // Reset and clear booking form for clean UX
        resetBookingForm();

        await loadPassengerDemand();
        startStatusPolling(result.journeyId);

    } catch (error) {
        console.error("Journey submission error:", error);
        message.textContent = "";
        if (error.message === "No buses available at the moment. Please wait...") {
            waitingCard.hidden = false;
        } else {
            message.textContent = error.message || "Could not submit your journey.";
        }
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Confirm Journey";
    }
});

// ======================================
// FORM CLEARANCE & RESET HELPER
// ======================================

function resetBookingForm() {
    currentInquiryId = null;
    journeyForm.reset();
    routeSelect.value = "";
    populateDropdown(tripSelect, [], "Choose a scheduled departure");
    populateDropdown(boardingSelect, [], "Choose boarding stop");
    populateDropdown(destinationSelect, [], "Choose destination");
    routeDetails.hidden = true;
    busDetails.hidden = true;
    busCount.textContent = "";
    busNumbers.textContent = "";
    if (tripBusAssignment) tripBusAssignment.textContent = "";
    passengerCount = 1;
    updatePassengerCount();
    stopBusPolling();
}

const newBookingBtn = document.getElementById("newBookingBtn");
if (newBookingBtn) {
    newBookingBtn.addEventListener("click", function () {
        confirmationCard.hidden = true;
        waitingCard.hidden = true;
        message.textContent = "";
        actionFeedback.textContent = "";
        resetBookingForm();
        loadPassengerDemand();
    });
}

// Initialize travel date to today if available
if (travelDate && !travelDate.value) {
    travelDate.value = new Date().toISOString().substring(0, 10);
}

if (travelDate) {
    travelDate.addEventListener("change", async function () {
        if (routeSelect.value) {
            await loadScheduledTrips(routeSelect.value);
        }
        await loadPassengerDemand();
    });
}

// ======================================
// INITIAL PAGE LOAD
// ======================================

updatePassengerCount();
loadRoutes();
loadPassengerDemand();
