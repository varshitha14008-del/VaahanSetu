
"use strict";

const API_BASE_URL = "/api";

const journeyForm = document.getElementById("journeyForm");
const routeSelect = document.getElementById("route");
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
const busCount = document.getElementById("busCount");
const busNumbers = document.getElementById("busNumbers");

const confirmationCard = document.getElementById("confirmationCard");
const waitingCard = document.getElementById("waitingCard");
const demandList = document.getElementById("demandList");

const confirmJourneyId = document.getElementById("confirmJourneyId");
const confirmRoute = document.getElementById("confirmRoute");
const confirmBoarding = document.getElementById("confirmBoarding");
const confirmDestination = document.getElementById("confirmDestination");
const confirmPassengers = document.getElementById("confirmPassengers");
const confirmStatus = document.getElementById("confirmStatus");

let passengerCount = 1;
let routes = [];

let busPollingTimer = null;
let currentBusRouteId = null;

let statusPollingTimer = null;
let activeJourneyId = null;


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
    }
});

decreasePassengers.addEventListener("click", function () {
    if (passengerCount > 1) {
        passengerCount--;
        updatePassengerCount();
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
            throw new Error("Could not load routes.");
        }

        routes = await response.json();

        if (!Array.isArray(routes)) {
            throw new Error("Invalid routes response.");
        }

        populateDropdown(
            routeSelect,
            routes.map(function (route) {
                return {
                    value: String(route.routeId),
                    label: route.routeName
                };
            }),
            "Choose a route"
        );

        message.textContent = routes.length > 0
            ? "Routes loaded successfully."
            : "No routes are currently available.";

        await loadPassengerDemand();

    } catch (error) {
        console.error("Route loading error:", error);

        message.textContent =
            "Could not load routes. Make sure the backend is running.";
    }
}


// ======================================
// PASSENGER DEMAND
// ======================================

async function loadPassengerDemand() {
    const selectedRouteId = routeSelect.value;

    demandList.replaceChildren();
    demandList.hidden = true;

    if (!selectedRouteId) {
        return;
    }

    try {
        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys/demand`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load passenger demand.");
        }

        const demand = await response.json();

        if (routeSelect.value !== selectedRouteId) return;

        const route = routes.find(function (item) {
            return String(item.routeId) === String(selectedRouteId);
        });

        let count = 0;
        if (Array.isArray(demand)) {
            const item = demand.find(function (d) {
                return String(d.routeId) === String(selectedRouteId);
            });
            count = item && item.passengerCount != null ? Number(item.passengerCount) : 0;
        } else if (demand && typeof demand === "object") {
            const raw = demand[selectedRouteId];
            count = raw != null && typeof raw === "object" ? Number(raw.passengerCount || 0) : Number(raw || 0);
        }

        const card = document.createElement("div");
        card.className = "demand-item";

        const heading = document.createElement("h3");
        heading.textContent = route
            ? `${route.routeCode} - ${route.routeName}`
            : `Route ${selectedRouteId}`;

        const passengerText = document.createElement("p");
        passengerText.textContent =
            `${count} registered passenger(s)`;

        card.appendChild(heading);
        card.appendChild(passengerText);

        demandList.appendChild(card);
        demandList.hidden = false;

    } catch (error) {
        if (routeSelect.value !== selectedRouteId) return;

        console.error("Passenger demand error:", error);

        demandList.textContent =
            "Could not load passenger demand.";

        demandList.hidden = false;
    }
}


// ======================================
// LOAD ROUTE STOPS
// ======================================

async function loadStops(routeId) {
    populateDropdown(
        boardingSelect,
        [],
        "Loading stops..."
    );

    populateDropdown(
        destinationSelect,
        [],
        "Choose destination"
    );

    try {
        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/stops`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load stops.");
        }

        const stops = await response.json();

        if (String(routeSelect.value) !== String(routeId)) {
            return;
        }

        const selectedRoute = routes.find(function (route) {
            return String(route.routeId) === String(routeId);
        });

        if (selectedRoute) {
            selectedRoute.stops = stops;
        }

        const stopOptions = stops.map(function (stop) {
            return {
                value: String(stop.stopId),
                label: stop.stopName
            };
        });

        populateDropdown(
            boardingSelect,
            stopOptions,
            "Choose boarding stop"
        );

        populateDropdown(
            destinationSelect,
            [],
            "Choose destination"
        );

        message.textContent = "Stops loaded successfully.";

    } catch (error) {
        console.error("Stop loading error:", error);

        if (String(routeSelect.value) !== String(routeId)) {
            return;
        }

        populateDropdown(
            boardingSelect,
            [],
            "Stops unavailable"
        );

        populateDropdown(
            destinationSelect,
            [],
            "Stops unavailable"
        );

        message.textContent =
            "Could not load stops from the backend.";
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
            throw new Error("Could not load bus details.");
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
            busCount.textContent =
                "No buses available at the moment. Please wait...";

            busNumbers.textContent = "";
            return;
        }

        busCount.textContent =
            `${buses.length} bus(es) allocated`;

        busNumbers.textContent = buses.map(function (bus) {
            return (
                `Registration Number: ${bus.registrationNumber}\n` +
                `Actual Capacity: ${bus.capacity}`
            );
        }).join("\n\n");

    } catch (error) {
        console.error("Bus loading error:", error);

        if (
            String(routeSelect.value) === String(routeId) &&
            currentBusRouteId === String(routeId)
        ) {
            busDetails.hidden = false;

            busCount.textContent =
                "Unable to check bus availability. Please wait...";

            busNumbers.textContent = "";
        }
    }
}


// ======================================
// AUTOMATIC BUS POLLING
// ======================================

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
        if (currentBusRouteId !== String(routeId)) {
            return;
        }

        await loadAllocatedBuses(routeId);

        if (currentBusRouteId === String(routeId)) {
            busPollingTimer = setTimeout(refreshBuses, 5000);
        }
    }

    refreshBuses();
}


// ======================================
// ROUTE CHANGE EVENT
// ======================================

routeSelect.addEventListener("change", async function () {
    message.textContent = "";

    confirmationCard.hidden = true;
    waitingCard.hidden = true;

    loadPassengerDemand();

    if (!routeSelect.value) {
        stopBusPolling();

        routeDetails.hidden = true;
        busDetails.hidden = true;

        busCount.textContent = "";
        busNumbers.textContent = "";

        populateDropdown(
            boardingSelect,
            [],
            "Choose boarding stop"
        );

        populateDropdown(
            destinationSelect,
            [],
            "Choose destination"
        );

        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute) {
        message.textContent = "Selected route not found.";
        return;
    }

    routeCode.textContent =
        selectedRoute.routeCode ||
        selectedRoute.routeName ||
        "-";

    routeOrigin.textContent =
        selectedRoute.originName || "-";

    routeDestination.textContent =
        selectedRoute.destinationName || "-";

    routeDuration.textContent =
        selectedRoute.estimatedDurationMinutes
            ? selectedRoute.estimatedDurationMinutes + " minutes"
            : "Not available";

    routeDetails.hidden = false;

    startBusPolling(routeSelect.value);

    await loadStops(routeSelect.value);
});


// ======================================
// BOARDING STOP CHANGE
// ======================================

boardingSelect.addEventListener("change", function () {
    populateDropdown(
        destinationSelect,
        [],
        "Choose destination"
    );

    if (!boardingSelect.value) {
        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (
        !selectedRoute ||
        !Array.isArray(selectedRoute.stops)
    ) {
        return;
    }

    const boardingPosition = selectedRoute.stops.findIndex(
        function (stop) {
            return String(stop.stopId) === boardingSelect.value;
        }
    );

    if (boardingPosition < 0) {
        return;
    }

    const laterStops = selectedRoute.stops
        .slice(boardingPosition + 1)
        .map(function (stop) {
            return {
                value: String(stop.stopId),
                label: stop.stopName
            };
        });

    populateDropdown(
        destinationSelect,
        laterStops,
        "Choose destination"
    );
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

        if (!response.ok) {
            throw new Error("Could not check journey status.");
        }

        const journey = await response.json();

        if (String(activeJourneyId) !== String(journeyId)) {
            return;
        }

        confirmStatus.textContent = journey.journeyStatus;

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

        if (String(activeJourneyId) === String(journeyId)) {
            statusPollingTimer = setTimeout(function () {
                checkJourneyStatus(journeyId);
            }, 4000);
        }
    }
}


// ======================================
// JOURNEY STATUS POLLING
// ======================================

function startStatusPolling(journeyId) {
    if (statusPollingTimer !== null) {
        clearTimeout(statusPollingTimer);
    }

    activeJourneyId = String(journeyId);
    statusPollingTimer = null;

    checkJourneyStatus(journeyId);
}


// ======================================
// JOURNEY FORM SUBMISSION
// ======================================

journeyForm.addEventListener("submit", async function (event) {
    event.preventDefault();

    const submitButton = journeyForm.querySelector(
        'button[type="submit"]'
    );

    if (submitButton.disabled) {
        return;
    }

    confirmationCard.hidden = true;
    waitingCard.hidden = true;

    if (
        !routeSelect.value ||
        !boardingSelect.value ||
        !destinationSelect.value
    ) {
        message.textContent =
            "Please select a route, boarding stop and destination.";

        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (
        !selectedRoute ||
        !Array.isArray(selectedRoute.stops)
    ) {
        message.textContent = "Please select a valid route.";
        return;
    }

    const boardingIndex = selectedRoute.stops.findIndex(
        function (stop) {
            return String(stop.stopId) === boardingSelect.value;
        }
    );

    const destinationIndex = selectedRoute.stops.findIndex(
        function (stop) {
            return String(stop.stopId) === destinationSelect.value;
        }
    );

    if (
        boardingIndex < 0 ||
        destinationIndex <= boardingIndex
    ) {
        message.textContent =
            "Choose a destination after your boarding stop.";

        return;
    }

    const journeyData = {
        routeId: Number(routeSelect.value),
        boardingStopId: Number(boardingSelect.value),
        destinationStopId: Number(destinationSelect.value),
        passengerCount: passengerCount
    };

    const boardingName =
        boardingSelect.options[
            boardingSelect.selectedIndex
        ].textContent;

    const destinationName =
        destinationSelect.options[
            destinationSelect.selectedIndex
        ].textContent;

    submitButton.disabled = true;
    submitButton.textContent = "Submitting...";

    try {
        message.textContent = "Submitting your journey...";

        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys`,
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(journeyData)
            }
        );

        const result = await response.json();

        if (!response.ok) {
            throw new Error(
                result.message || "Journey submission failed."
            );
        }

        message.textContent =
            `Journey registered successfully! Journey ID: ${result.journeyId}. Status: ${result.journeyStatus}.`;

        confirmJourneyId.textContent = result.journeyId;

        confirmRoute.textContent =
            `${selectedRoute.routeCode} - ${selectedRoute.routeName}`;

        confirmBoarding.textContent = boardingName;
        confirmDestination.textContent = destinationName;
        confirmPassengers.textContent = passengerCount;
        confirmStatus.textContent = result.journeyStatus;

        waitingCard.hidden = true;
        confirmationCard.hidden = false;

        await loadPassengerDemand();

        startStatusPolling(result.journeyId);

    } catch (error) {
        console.error("Journey submission error:", error);

        message.textContent = "";

        if (
            error.message ===
            "No buses available at the moment. Please wait..."
        ) {
            waitingCard.hidden = false;
        } else {
            message.textContent =
                error.message || "Could not submit your journey.";
        }

    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Confirm Journey";
    }
});


// ======================================
// INITIAL PAGE LOAD
// ======================================

updatePassengerCount();
loadRoutes();
loadPassengerDemand();