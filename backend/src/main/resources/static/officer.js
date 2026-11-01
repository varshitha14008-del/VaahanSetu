"use strict";

const API_BASE = "/api";

// Authentication State
let authToken = localStorage.getItem("vst_token") || null;
let currentUser = null;

// DOM Elements - Auth & Nav
const loginSection = document.getElementById("loginSection");
const authenticatedApp = document.getElementById("authenticatedApp");
const loginForm = document.getElementById("loginForm");
const loginError = document.getElementById("loginError");
const userBadge = document.getElementById("userBadge");
const userFullName = document.getElementById("userFullName");
const userRolePill = document.getElementById("userRolePill");
const logoutBtn = document.getElementById("logoutBtn");
const globalAlert = document.getElementById("globalAlert");

// Stats & Dashboard
const statActiveBuses = document.getElementById("statActiveBuses");
const statActiveRoutes = document.getElementById("statActiveRoutes");
const statTripsToday = document.getElementById("statTripsToday");
const statSessionStatus = document.getElementById("statSessionStatus");
const statSessionName = document.getElementById("statSessionName");
const dashSessionDetails = document.getElementById("dashSessionDetails");
const dashLatestAllocation = document.getElementById("dashLatestAllocation");

// Fleet
const fleetTableBody = document.getElementById("fleetTableBody");
const refreshFleetBtn = document.getElementById("refreshFleetBtn");

// Routes & Trips
const routesContainer = document.getElementById("routesContainer");
const refreshRoutesBtn = document.getElementById("refreshRoutesBtn");
const tripsTableBody = document.getElementById("tripsTableBody");
const refreshTripsBtn = document.getElementById("refreshTripsBtn");
const tripDemandDrawer = document.getElementById("tripDemandDrawer");
const drawerTripTitle = document.getElementById("drawerTripTitle");
const drawerDemandBody = document.getElementById("drawerDemandBody");
const closeDrawerBtn = document.getElementById("closeDrawerBtn");

// Allocation
const runAllocationBtn = document.getElementById("runAllocationBtn");
const allocationComparisonBox = document.getElementById("allocationComparisonBox");
const noAllocationPlaceholder = document.getElementById("noAllocationPlaceholder");
const approveProposalBtn = document.getElementById("approveProposalBtn");
const propStatusBadge = document.getElementById("propStatusBadge");
const propIdBadge = document.getElementById("propIdBadge");
const proposedAssignmentsBody = document.getElementById("proposedAssignmentsBody");
const proposedSegmentsBody = document.getElementById("proposedSegmentsBody");

const baseDemand = document.getElementById("baseDemand");
const baseCapacity = document.getElementById("baseCapacity");
const baseAccommodated = document.getElementById("baseAccommodated");
const baseUnmet = document.getElementById("baseUnmet");
const baseUnused = document.getElementById("baseUnused");

const propDemand = document.getElementById("propDemand");
const propCapacity = document.getElementById("propCapacity");
const propAccommodated = document.getElementById("propAccommodated");
const propUnmet = document.getElementById("propUnmet");
const propUnused = document.getElementById("propUnused");

// Simulation
const tabSimulationBtn = document.getElementById("tabSimulationBtn");
const simClockValue = document.getElementById("simClockValue");
const simStepBtn = document.getElementById("simStepBtn");
const simResetBtn = document.getElementById("simResetBtn");
const simLogList = document.getElementById("simLogList");

let currentActiveProposalId = null;

// ==========================================================================
// API HELPER
// ==========================================================================

async function apiFetch(endpoint, options = {}) {
    const headers = options.headers || {};
    if (authToken) {
        headers["Authorization"] = `Bearer ${authToken}`;
    }
    if (options.body && typeof options.body === "object" && !(options.body instanceof FormData)) {
        headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(options.body);
    }
    options.headers = headers;

    const response = await fetch(`${API_BASE}${endpoint}`, options);
    if (response.status === 401) {
        handleLogout(true, "Your session has expired or is invalid. Please log in again.");
        throw new Error("Session expired. Please log in again.");
    }
    return response;
}

function showAlert(message, type = "success") {
    globalAlert.textContent = message;
    globalAlert.className = `global-alert ${type}`;
    globalAlert.hidden = false;
    setTimeout(() => {
        globalAlert.hidden = true;
    }, 5000);
}

// ==========================================================================
// AUTHENTICATION
// ==========================================================================

async function checkAuth() {
    if (!authToken) {
        showLogin();
        return;
    }
    try {
        const res = await apiFetch("/auth/me");
        if (res.ok) {
            currentUser = await res.json();
            showApp();
        } else {
            showLogin("Session invalid. Please log in.");
        }
    } catch (e) {
        showLogin("Session expired. Please log in again.");
    }
}

function showLogin(errorMessage = "") {
    authToken = null;
    currentUser = null;
    localStorage.removeItem("vst_token");
    loginSection.hidden = false;
    authenticatedApp.hidden = true;
    userBadge.hidden = true;
    if (loginError) {
        loginError.textContent = errorMessage;
    }
}

function showApp() {
    loginSection.hidden = true;
    authenticatedApp.hidden = false;
    userBadge.hidden = false;
    userFullName.textContent = currentUser.fullName || currentUser.username;
    userRolePill.textContent = currentUser.role;
    userRolePill.className = `role-pill ${currentUser.role.toLowerCase()}`;

    // Admin-only controls
    const isAdmin = currentUser.role === "ADMIN";
    document.querySelectorAll(".admin-only").forEach(el => {
        el.style.display = isAdmin ? "" : "none";
    });

    loadDashboard();
}

loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    loginError.textContent = "";

    const username = document.getElementById("username").value.trim();
    const password = document.getElementById("password").value;

    try {
        const res = await fetch(`${API_BASE}/auth/login`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username, password })
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Login failed.");
        }

        authToken = data.token;
        localStorage.setItem("vst_token", authToken);
        currentUser = data;
        showApp();
    } catch (err) {
        loginError.textContent = err.message;
    }
});

function handleLogout(isExpired = false, message = "") {
    if (authToken) {
        fetch(`${API_BASE}/auth/logout`, {
            method: "POST",
            headers: { "Authorization": `Bearer ${authToken}` }
        }).catch(() => {});
    }
    showLogin(message);
    if (isExpired) {
        showAlert(message || "Your session has expired. Please log in again.", "danger");
    } else {
        showAlert("You have been logged out successfully.", "success");
    }
}

logoutBtn.addEventListener("click", () => handleLogout(false));

// ==========================================================================
// TABS NAVIGATION
// ==========================================================================

document.querySelectorAll(".tab-btn").forEach(button => {
    button.addEventListener("click", () => {
        document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
        document.querySelectorAll(".tab-content").forEach(c => c.classList.remove("active"));

        button.classList.add("active");
        const targetTab = document.getElementById(button.dataset.tab);
        if (targetTab) {
            targetTab.classList.add("active");
            onTabActivated(button.dataset.tab);
        }
    });
});

function onTabActivated(tabId) {
    if (tabId === "tabDashboard") loadDashboard();
    if (tabId === "tabFleet") loadFleet();
    if (tabId === "tabRoutes") loadRoutes();
    if (tabId === "tabTrips") loadTrips();
    if (tabId === "tabAllocation") loadLatestAllocation();
    if (tabId === "tabSimulation") loadSimulationState();
}

// ==========================================================================
// DASHBOARD OVERVIEW
// ==========================================================================

async function loadDashboard() {
    try {
        const [busesRes, routesRes, tripsRes, sessionRes] = await Promise.all([
            apiFetch("/officer/buses"),
            apiFetch("/officer/routes"),
            apiFetch("/officer/trips"),
            apiFetch("/officer/sessions/active")
        ]);

        if (busesRes.ok) {
            const buses = await busesRes.json();
            const active = buses.filter(b => b.status === "ACTIVE").length;
            statActiveBuses.textContent = `${active} / ${buses.length}`;
        }

        if (routesRes.ok) {
            const routes = await routesRes.json();
            statActiveRoutes.textContent = routes.length;
        }

        if (tripsRes.ok) {
            const trips = await tripsRes.json();
            statTripsToday.textContent = trips.length;
        }

        if (sessionRes.ok && sessionRes.status !== 204) {
            const session = await sessionRes.json();
            statSessionStatus.textContent = session.status || "ACTIVE";
            statSessionName.textContent = session.sessionName || "Active Session";
            dashSessionDetails.innerHTML = `
                <p><strong>Session ID:</strong> ${session.sessionId}</p>
                <p><strong>Name:</strong> ${session.sessionName}</p>
                <p><strong>Started At:</strong> ${new Date(session.startedAt).toLocaleString()}</p>
                <p><strong>Selected Routes:</strong> ${session.selectedRouteIds ? session.selectedRouteIds.length : 0}</p>
                <p><strong>Selected Buses:</strong> ${session.selectedBusIds ? session.selectedBusIds.length : 0}</p>
            `;
        } else {
            statSessionStatus.textContent = "None";
            statSessionName.textContent = "No session active";
            dashSessionDetails.textContent = "No active planning session in database.";
        }

        // Latest allocation overview
        const allocRes = await apiFetch("/officer/allocations/latest");
        if (allocRes.ok && allocRes.status !== 204) {
            const alloc = await allocRes.json();
            dashLatestAllocation.innerHTML = `
                <p><strong>Run ID:</strong> #${alloc.allocationRunId} (${alloc.status})</p>
                <p><strong>Baseline Unmet:</strong> ${alloc.baselineMetrics ? alloc.baselineMetrics.unmet : 0} passengers</p>
                <p><strong>Proposed Unmet:</strong> <span style="color:var(--success); font-weight:bold;">${alloc.proposedMetrics ? alloc.proposedMetrics.unmet : 0} passengers</span></p>
                <p><strong>Total Trips Evaluated:</strong> ${alloc.tripAssignments ? alloc.tripAssignments.length : 0}</p>
            `;
        } else {
            dashLatestAllocation.textContent = "No allocation runs recorded yet. Head to the Allocation Engine tab to optimize fleet assignments.";
        }

    } catch (e) {
        console.error("Dashboard load error", e);
    }
}

// ==========================================================================
// FLEET MANAGEMENT
// ==========================================================================

async function loadFleet() {
    fleetTableBody.innerHTML = `<tr><td colspan="8">Loading fleet details...</td></tr>`;
    try {
        const res = await apiFetch("/officer/buses");
        if (!res.ok) throw new Error("Could not load buses.");
        const buses = await res.json();

        fleetTableBody.innerHTML = buses.map(b => {
            const fixedRest = (b.restStart && b.restEnd) ? `${b.restStart.substring(0,5)} &ndash; ${b.restEnd.substring(0,5)}` : "None (Free)";
            const statusClass = b.status === "ACTIVE" ? "badge-active" : (b.status === "MAINTENANCE" ? "badge-maintenance" : "badge-inactive");
            return `
                <tr>
                    <td><strong>#${b.busId}</strong></td>
                    <td>${b.registrationNumber}</td>
                    <td><strong>${b.capacity}</strong> seats</td>
                    <td><span class="badge ${statusClass}">${b.status}</span></td>
                    <td>${b.currentLocation || "Depot"}</td>
                    <td>${b.restDurationMinutes} min</td>
                    <td>${fixedRest}</td>
                    <td>
                        <button class="btn btn-secondary btn-sm" onclick="toggleBusStatus(${b.busId}, '${b.status}')">
                            ${b.status === 'ACTIVE' ? 'Set Maintenance' : 'Set Active'}
                        </button>
                    </td>
                </tr>
            `;
        }).join("");
    } catch (e) {
        fleetTableBody.innerHTML = `<tr><td colspan="8" class="error-msg">${e.message}</td></tr>`;
    }
}

window.toggleBusStatus = async function(busId, currentStatus) {
    const newStatus = currentStatus === "ACTIVE" ? "MAINTENANCE" : "ACTIVE";
    try {
        const res = await apiFetch(`/officer/buses/${busId}/status`, {
            method: "PATCH",
            body: { status: newStatus }
        });
        if (!res.ok) throw new Error("Status update failed.");
        showAlert(`Bus #${busId} status changed to ${newStatus}.`);
        loadFleet();
    } catch (e) {
        showAlert(e.message, "error");
    }
};

refreshFleetBtn.addEventListener("click", loadFleet);

// ==========================================================================
// ROUTES & STOPS
// ==========================================================================

async function loadRoutes() {
    routesContainer.innerHTML = "Loading corridors...";
    try {
        const res = await apiFetch("/officer/routes");
        if (!res.ok) throw new Error("Could not load routes.");
        const routes = await res.json();

        routesContainer.innerHTML = routes.map(r => {
            const stopsList = (r.stops || []).map(s => 
                `<li><strong>Stop ${s.stopSequence}:</strong> ${s.stopName} <span class="text-muted">(+${s.scheduledOffsetMinutes || 0} min)</span></li>`
            ).join("");

            return `
                <div class="card-box" style="margin-bottom:1.5rem;">
                    <h3>${r.routeCode} &ndash; ${r.routeName}</h3>
                    <p><strong>From:</strong> ${r.originName} &nbsp;|&nbsp; <strong>To:</strong> ${r.destinationName} &nbsp;|&nbsp; <strong>Est. Duration:</strong> ${r.estimatedDurationMinutes} mins</p>
                    <h4>Ordered Route Stops</h4>
                    <ul style="padding-left:1.5rem; line-height:1.6;">${stopsList}</ul>
                </div>
            `;
        }).join("");
    } catch (e) {
        routesContainer.innerHTML = `<p class="error-msg">${e.message}</p>`;
    }
}

refreshRoutesBtn.addEventListener("click", loadRoutes);

// ==========================================================================
// TRIPS & SCHEDULE
// ==========================================================================

async function loadTrips() {
    tripsTableBody.innerHTML = `<tr><td colspan="9">Loading scheduled trips...</td></tr>`;
    try {
        const res = await apiFetch("/officer/trips");
        if (!res.ok) throw new Error("Could not load trips.");
        const trips = await res.json();

        tripsTableBody.innerHTML = trips.map(t => {
            const assigned = t.currentBusReg ? `<strong>${t.currentBusReg}</strong>` : `<span class="badge badge-inactive">Unassigned</span>`;
            const depTime = t.departureTime ? t.departureTime.substring(11, 16) : "-";
            const arrTime = t.expectedArrivalTime ? t.expectedArrivalTime.substring(11, 16) : "-";

            return `
                <tr>
                    <td><strong>#${t.tripId}</strong></td>
                    <td>${t.routeCode} (${t.routeName})</td>
                    <td>${t.serviceDate}</td>
                    <td>${depTime}</td>
                    <td>${arrTime}</td>
                    <td>${assigned}</td>
                    <td>${t.currentBusCapacity || 0} seats</td>
                    <td><span class="badge badge-active">${t.tripStatus}</span></td>
                    <td>
                        <button class="btn btn-secondary btn-sm" onclick="inspectTripDemand(${t.tripId}, '${t.routeCode} - ${depTime}')">
                            Inspect Demand &rarr;
                        </button>
                    </td>
                </tr>
            `;
        }).join("");
    } catch (e) {
        tripsTableBody.innerHTML = `<tr><td colspan="9" class="error-msg">${e.message}</td></tr>`;
    }
}

refreshTripsBtn.addEventListener("click", loadTrips);

window.inspectTripDemand = async function(tripId, title) {
    drawerTripTitle.textContent = `Segment-Wise Demand for Trip #${tripId} (${title})`;
    drawerDemandBody.innerHTML = `<tr><td colspan="7">Loading segment breakdown...</td></tr>`;
    tripDemandDrawer.hidden = false;

    try {
        const res = await apiFetch(`/officer/trips/${tripId}/demand`);
        if (!res.ok) throw new Error("Could not load segment demand.");
        const segments = await res.json();

        drawerDemandBody.innerHTML = segments.map(s => `
            <tr>
                <td><strong>${s.startStopName} &rarr; ${s.endStopName}</strong></td>
                <td>${s.registeredDemand}</td>
                <td>${s.simulatedDelta > 0 ? `+${s.simulatedDelta}` : s.simulatedDelta}</td>
                <td><strong>${s.effectiveDemand}</strong></td>
                <td>${s.currentBusCapacity}</td>
                <td><span style="color:var(--success); font-weight:bold;">${s.accommodated}</span></td>
                <td><span style="color:${s.unmetDemand > 0 ? 'var(--danger)' : 'inherit'}; font-weight:bold;">${s.unmetDemand}</span></td>
            </tr>
        `).join("");
    } catch (e) {
        drawerDemandBody.innerHTML = `<tr><td colspan="7" class="error-msg">${e.message}</td></tr>`;
    }
};

closeDrawerBtn.addEventListener("click", () => {
    tripDemandDrawer.hidden = true;
});

// ==========================================================================
// ALLOCATION ENGINE INTEGRATION
// ==========================================================================

async function loadLatestAllocation() {
    try {
        const res = await apiFetch("/officer/allocations/latest");
        if (res.ok && res.status !== 204) {
            const data = await res.json();
            renderAllocationResult(data);
        } else {
            allocationComparisonBox.hidden = true;
            noAllocationPlaceholder.hidden = false;
        }
    } catch (e) {
        allocationComparisonBox.hidden = true;
        noAllocationPlaceholder.hidden = false;
    }
}

runAllocationBtn.addEventListener("click", async () => {
    runAllocationBtn.disabled = true;
    runAllocationBtn.textContent = "Executing Allocation Engine...";

    try {
        const res = await apiFetch("/officer/allocations/run", {
            method: "POST",
            body: {}
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Allocation failed.");
        }

        showAlert(`Allocation executed successfully! Run #${data.allocationRunId} proposed.`);
        renderAllocationResult(data);
    } catch (e) {
        showAlert(e.message, "error");
    } finally {
        runAllocationBtn.disabled = false;
        runAllocationBtn.textContent = "⚙ Run Allocation Engine";
    }
});

function renderAllocationResult(alloc) {
    currentActiveProposalId = alloc.allocationRunId;
    noAllocationPlaceholder.hidden = true;
    allocationComparisonBox.hidden = false;

    propIdBadge.textContent = `Run ID: #${alloc.allocationRunId}`;
    propStatusBadge.textContent = alloc.status;
    propStatusBadge.className = `badge ${alloc.status === 'APPROVED' ? 'badge-approved' : 'badge-proposed'}`;

    if (alloc.status === "APPROVED") {
        approveProposalBtn.disabled = true;
        approveProposalBtn.textContent = "✔ Already Approved & Applied";
    } else {
        approveProposalBtn.disabled = false;
        approveProposalBtn.textContent = "✔ Approve & Apply to Live Schedule";
    }

    // Baseline metrics
    const b = alloc.baselineMetrics || {};
    baseDemand.textContent = b.demand ?? 0;
    baseCapacity.textContent = b.capacity ?? 0;
    baseAccommodated.textContent = b.accommodated ?? 0;
    baseUnmet.textContent = b.unmet ?? 0;
    baseUnused.textContent = b.unused ?? 0;

    // Proposed metrics
    const p = alloc.proposedMetrics || {};
    propDemand.textContent = p.demand ?? 0;
    propCapacity.textContent = p.capacity ?? 0;
    propAccommodated.textContent = p.accommodated ?? 0;
    propUnmet.textContent = p.unmet ?? 0;
    propUnused.textContent = p.unused ?? 0;

    // Trip outcomes
    const outcomes = alloc.tripAssignments || [];
    proposedAssignmentsBody.innerHTML = outcomes.map(o => {
        let typeBadge = "badge-active";
        if (o.assignmentType === "REASSIGNED") typeBadge = "badge-proposed";
        if (o.assignmentType === "UNASSIGNED") typeBadge = "badge-inactive";

        return `
            <tr>
                <td><strong>#${o.tripId}</strong></td>
                <td>${o.routeCode || `Route ${o.routeId}`}</td>
                <td>${o.previousBusReg ? o.previousBusReg : (o.previousBusId ? `#${o.previousBusId}` : 'None')}</td>
                <td><strong>${o.proposedBusReg ? o.proposedBusReg : (o.proposedBusId ? `#${o.proposedBusId}` : 'None')}</strong></td>
                <td><span class="badge ${typeBadge}">${o.assignmentType}</span></td>
                <td>${o.reason || '-'}</td>
            </tr>
        `;
    }).join("");

    // Segment results
    const segments = alloc.segmentResults || [];
    proposedSegmentsBody.innerHTML = segments.map(s => `
        <tr>
            <td>${s.routeCode || `Route ${s.routeId}`}</td>
            <td><strong>${s.startStop ? `${s.startStop} &rarr; ${s.endStop}` : `Stop ${s.startStopId} &rarr; ${s.endStopId}`}</strong></td>
            <td>${s.demand}</td>
            <td>${s.capacity}</td>
            <td><span style="color:var(--success); font-weight:bold;">${s.accommodated}</span></td>
            <td><span style="color:${s.unmet > 0 ? 'var(--danger)' : 'inherit'}; font-weight:bold;">${s.unmet}</span></td>
            <td>${s.unused}</td>
        </tr>
    `).join("");
}

approveProposalBtn.addEventListener("click", async () => {
    if (!currentActiveProposalId) return;

    if (!confirm(`Are you sure you want to approve allocation run #${currentActiveProposalId} and update live active bus assignments?`)) {
        return;
    }

    approveProposalBtn.disabled = true;
    approveProposalBtn.textContent = "Applying assignments to database...";

    try {
        const res = await apiFetch(`/officer/allocations/${currentActiveProposalId}/approve`, {
            method: "POST"
        });

        const data = await res.json();
        if (!res.ok) throw new Error(data.message || "Approval failed.");

        showAlert(`Proposal #${currentActiveProposalId} approved! ${data.reassignedTrips || 0} trip(s) reassigned.`);
        loadLatestAllocation();
        loadTrips();
    } catch (e) {
        showAlert(e.message, "error");
        approveProposalBtn.disabled = false;
        approveProposalBtn.textContent = "✔ Approve & Apply to Live Schedule";
    }
});

// ==========================================================================
// SIMULATION CONTROLS
// ==========================================================================

async function loadSimulationState() {
    try {
        const res = await apiFetch("/officer/sessions/active");
        if (res.ok && res.status !== 204) {
            const s = await res.json();
            const mins = s.simulationElapsedMinutes || 0;
            const hh = String(Math.floor(mins / 60)).padStart(2, '0');
            const mm = String(mins % 60).padStart(2, '0');
            simClockValue.textContent = `+${hh}:${mm}`;
        }
    } catch (e) {}
}

simStepBtn.addEventListener("click", async () => {
    simStepBtn.disabled = true;
    simStepBtn.textContent = "Advancing Simulation Clock...";

    try {
        const res = await apiFetch("/admin/simulation/step", { method: "POST" });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || "Simulation step failed.");

        const mins = data.simulationElapsedMinutes || 0;
        const hh = String(Math.floor(mins / 60)).padStart(2, '0');
        const mm = String(mins % 60).padStart(2, '0');
        simClockValue.textContent = `+${hh}:${mm}`;

        const li = document.createElement("li");
        li.textContent = `[${new Date().toLocaleTimeString()}] Clock advanced +2m (Total: +${hh}:${mm}). Injected ${data.adjustmentsGenerated} crowd deltas into segment demand.`;
        simLogList.prepend(li);

        showAlert(`Simulation clock advanced +2 minutes. New segment deltas generated.`);
    } catch (e) {
        showAlert(e.message, "error");
    } finally {
        simStepBtn.disabled = false;
        simStepBtn.textContent = "► Step Clock (+2 Minutes)";
    }
});

simResetBtn.addEventListener("click", async () => {
    if (!confirm("Reset all simulated demand deltas and clock for this session?")) return;

    try {
        const res = await apiFetch("/admin/simulation/reset", { method: "POST" });
        if (!res.ok) throw new Error("Reset failed.");

        simClockValue.textContent = "+00:00";
        const li = document.createElement("li");
        li.textContent = `[${new Date().toLocaleTimeString()}] Simulated adjustments reset to 0.`;
        simLogList.prepend(li);

        showAlert("Simulation adjustments reset.");
    } catch (e) {
        showAlert(e.message, "error");
    }
});

// Initial boot
checkAuth();
