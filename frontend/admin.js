// VaahanSetu Administrator Portal Logic
const isLocalEnv = !window.location.hostname ||
    window.location.protocol === 'file:' ||
    window.location.hostname === 'localhost' ||
    window.location.hostname === '127.0.0.1' ||
    window.location.hostname === '0.0.0.0';

const API_BASE = window.VAAHAN_API_URL || (
    isLocalEnv ? "http://localhost:8080/api" : `${window.location.origin}/api`
);

const state = {
    token: sessionStorage.getItem('vaahan_auth_token') || '',
    user: JSON.parse(sessionStorage.getItem('vaahan_user') || 'null'),
    activeSession: null,
    simClockMinutes: 600, // Dynamic, synchronized with DB active planning session
    lastAllocationProposal: null,
    trips: [],
    buses: [],
    routes: []
};

// Request sequence tracking to guard against race conditions and stale responses
const reqSequence = {
    session: 0,
    buses: 0,
    routes: 0,
    trips: 0,
    tripDemand: 0,
    demandBreakdown: 0
};

/**
 * Sanitizes error messages to prevent leaking credentials, connection strings, or raw stack traces.
 * @param {string} msg
 * @returns {string}
 */
function sanitizeErrorMessage(msg) {
    if (!msg || typeof msg !== 'string') return '';
    if (/password|secret|token|credentials|jdbc:|postgresql:\/\//i.test(msg)) {
        return 'An internal database or server error occurred. Please contact the system administrator.';
    }
    if (msg.length > 250) {
        return msg.substring(0, 247) + '...';
    }
    return msg;
}

/**
 * Formats caught errors into clear, administrator-friendly messages.
 * @param {Error|any} err
 * @param {string} fallback
 * @returns {string}
 */
function formatErrorMessage(err, fallback = 'An unexpected error occurred.') {
    if (!err) return fallback;
    if (err.isTimeout || err.name === 'TimeoutError') {
        return err.message;
    }
    if (err.isNetworkError || err.name === 'NetworkError' || err.message === 'Failed to fetch') {
        return `Unable to connect to backend server (${API_BASE}). The server may be waking up (Render cold start) or offline. Please check your connection or retry in a moment.`;
    }
    if (err.message) {
        return sanitizeErrorMessage(err.message);
    }
    return fallback;
}

/**
 * Extracts and sanitizes error information from an HTTP error response.
 * @param {Response} res
 * @param {string} fallback
 * @returns {Promise<string>}
 */
async function parseErrorResponse(res, fallback = `Server returned HTTP ${res.status}`) {
    let serverMsg = '';
    try {
        const data = await res.json();
        if (data && typeof data.message === 'string' && data.message.trim()) {
            serverMsg = sanitizeErrorMessage(data.message.trim());
        } else if (data && typeof data.error === 'string' && data.error.trim()) {
            serverMsg = sanitizeErrorMessage(data.error.trim());
        }
    } catch (_) {
        // Response is not JSON (e.g. gateway timeout HTML)
    }

    if (res.status === 401) {
        return 'Session expired or unauthorized. Please log in again.';
    }
    if (res.status === 403) {
        return 'Access denied: Administrator privileges required.';
    }
    if (res.status === 404) {
        return serverMsg ? `Resource not found: ${serverMsg}` : 'Requested resource was not found on the server (HTTP 404).';
    }
    if (res.status === 409) {
        return serverMsg ? `Conflict: ${serverMsg}` : 'Conflict occurred with current server state (HTTP 409).';
    }
    if (res.status === 502 || res.status === 503 || res.status === 504) {
        return `Server is temporarily unavailable (HTTP ${res.status}). It may be waking up from a cold start. Please retry in a few moments.`;
    }
    if (res.status >= 500) {
        return serverMsg ? `Server error (HTTP ${res.status}): ${serverMsg}` : `Server error (HTTP ${res.status}). Please check backend logs or retry.`;
    }
    return serverMsg ? `Error (HTTP ${res.status}): ${serverMsg}` : `${fallback} (HTTP ${res.status})`;
}

/**
 * Executes a fetch request with an AbortController timeout.
 * @param {string} url - Request URL
 * @param {RequestInit} [options={}] - Fetch options
 * @param {number} [timeoutMs=60000] - Timeout in milliseconds (default 60s)
 * @returns {Promise<Response>}
 */
async function fetchWithTimeout(url, options = {}, timeoutMs = 60000) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => {
        controller.abort();
    }, timeoutMs);

    // If caller provided an external signal, propagate abort
    const callerSignal = options.signal;
    let abortHandler = null;
    if (callerSignal) {
        if (callerSignal.aborted) {
            clearTimeout(timeoutId);
            controller.abort();
        } else {
            abortHandler = () => controller.abort();
            callerSignal.addEventListener('abort', abortHandler, { once: true });
        }
    }

    try {
        const response = await fetch(url, {
            ...options,
            signal: controller.signal
        });
        return response;
    } catch (err) {
        if (err.name === 'AbortError' || controller.signal.aborted) {
            const timeoutSeconds = Math.round(timeoutMs / 1000);
            const timeoutError = new Error(`Request timed out after ${timeoutSeconds}s. The server may be waking up (cold start) or processing heavy load. Please retry in a moment.`);
            timeoutError.name = 'TimeoutError';
            timeoutError.isTimeout = true;
            throw timeoutError;
        }
        if (err.message === 'Failed to fetch' || err.name === 'TypeError') {
            const netError = new Error(`Unable to connect to the backend server. Please verify network connectivity and that the server is online.`);
            netError.name = 'NetworkError';
            netError.isNetworkError = true;
            throw netError;
        }
        throw err;
    } finally {
        clearTimeout(timeoutId);
        if (callerSignal && abortHandler) {
            callerSignal.removeEventListener('abort', abortHandler);
        }
    }
}

function formatMinutesToTime(mins) {
    const h = Math.floor(mins / 60) % 24;
    const m = mins % 60;
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(h)}:${pad(m)}:00`;
}

// Authentication Helpers
function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (state.token) {
        headers['Authorization'] = `Bearer ${state.token}`;
    }
    return headers;
}

function checkAuth() {
    const loginSection = document.getElementById('loginSection');
    const adminDashboard = document.getElementById('adminDashboard');
    const logoutBtn = document.getElementById('logoutBtn');

    if (!state.token || !state.user || (state.user.role && state.user.role.toUpperCase() !== 'ADMIN')) {
        if (loginSection) loginSection.hidden = false;
        if (adminDashboard) adminDashboard.hidden = true;
        if (logoutBtn) logoutBtn.hidden = true;
        return;
    }

    showDashboard();
}

function showDashboard() {
    const loginSection = document.getElementById('loginSection');
    const adminDashboard = document.getElementById('adminDashboard');
    const logoutBtn = document.getElementById('logoutBtn');

    if (loginSection) loginSection.hidden = true;
    if (adminDashboard) adminDashboard.hidden = false;
    if (logoutBtn) logoutBtn.hidden = false;

    // Update user profile display if present
    const userRoleEl = document.getElementById('userRole');
    const userAvatarEl = document.getElementById('userAvatar');
    if (userRoleEl) userRoleEl.textContent = state.user?.role || 'ADMIN';
    if (userAvatarEl) userAvatarEl.textContent = (state.user?.username || 'A').substring(0, 1).toUpperCase();

    // Setup Navigation Tabs
    initTabs();

    // Initial Data Fetch
    initDashboard();
}

// Login Form Handling
const adminLoginForm = document.getElementById('adminLoginForm');
if (adminLoginForm) {
    adminLoginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('adminUsername')?.value?.trim();
        const password = document.getElementById('adminPassword')?.value;
        const feedback = document.getElementById('loginFeedback');
        const submitBtn = document.getElementById('loginSubmitBtn');

        if (!username || !password) {
            if (feedback) {
                feedback.textContent = 'Please enter both username and password.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        if (feedback) {
            feedback.textContent = 'Authenticating with server...';
            feedback.className = 'feedback-msg text-info';
        }
        if (submitBtn) submitBtn.disabled = true;

        try {
            const res = await fetchWithTimeout(`${API_BASE}/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            }, 60000);

            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                const errMsg = data.message || `Login failed (HTTP ${res.status})`;
                throw new Error(errMsg);
            }

            const role = (data.role || '').toUpperCase();
            if (role && role !== 'ADMIN') {
                throw new Error('Access denied: Administrator privileges required.');
            }

            const token = data.token || data.accessToken || data.jwt;
            const user = {
                userId: data.userId,
                username: data.username || username,
                fullName: data.fullName,
                role: role || 'ADMIN'
            };

            sessionStorage.setItem('vaahan_auth_token', token);
            sessionStorage.setItem('vaahan_user', JSON.stringify(user));
            state.token = token;
            state.user = user;

            if (feedback) feedback.textContent = '';
            showDashboard();
        } catch (err) {
            if (feedback) {
                const userMsg = formatErrorMessage(err, 'Login failed');
                feedback.textContent = userMsg.startsWith('Login failed') ? userMsg : `Login failed: ${userMsg}`;
                feedback.className = 'feedback-msg text-danger';
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    });
}

// Setup Logout
const logoutBtn = document.getElementById('logoutBtn');
if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
        sessionStorage.removeItem('vaahan_auth_token');
        sessionStorage.removeItem('vaahan_user');
        state.token = '';
        state.user = null;
        const loginSection = document.getElementById('loginSection');
        const adminDashboard = document.getElementById('adminDashboard');
        if (loginSection) loginSection.hidden = false;
        if (adminDashboard) adminDashboard.hidden = true;
        logoutBtn.hidden = true;
        const feedback = document.getElementById('loginFeedback');
        if (feedback) {
            feedback.textContent = 'Signed out successfully.';
            feedback.className = 'feedback-msg text-info';
        }
    });
}

function initTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn, .nav-tab');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => {
        btn.onclick = () => {
            tabBtns.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            const target = btn.getAttribute('data-tab');
            const targetSection = document.getElementById(target);
            if (targetSection) targetSection.classList.add('active');
        };
    });
}

// Initial Data Load - Decoupled to allow independent loading and partial availability
async function initDashboard() {
    try {
        await fetchActiveSession();
    } catch (e) {
        console.warn('Active session sync failed during init:', e);
    }

    // Load buses, routes, and trips in parallel using Promise.allSettled
    // so a slow or failing endpoint does not block or cancel the others
    await Promise.allSettled([
        fetchBuses(),
        fetchRoutes(),
        fetchTrips()
    ]);
    renderSessionConfig();
}

// Fetch Active Session Clock & Details
async function fetchActiveSession() {
    const reqId = ++reqSequence.session;
    try {
        const res = await fetchWithTimeout(`${API_BASE}/officer/sessions/active`, {
            headers: getAuthHeaders()
        }, 60000);
        if (res.ok) {
            const session = await res.json();
            if (reqId !== reqSequence.session) return;
            state.activeSession = session;
            if (session && session.startedAt) {
                try {
                    const dateStr = session.startedAt.substring(0, 10);
                    const dateFilter = document.getElementById('tripDateFilter');
                    if (dateFilter && !dateFilter.value) {
                        dateFilter.value = dateStr;
                    }
                    const statDateEl = document.getElementById('statServiceDate');
                    if (statDateEl) statDateEl.textContent = dateStr;
                } catch (e) {
                    console.warn('Could not parse session startedAt:', e);
                }
            }
        }
    } catch (err) {
        console.warn('Could not sync active session:', err);
    }
}

// Fetch Buses
async function fetchBuses() {
    const reqId = ++reqSequence.buses;
    const tbody = document.querySelector('#busesTable tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="5" class="text-center">Loading fleet data...</td></tr>';
    try {
        const res = await fetchWithTimeout(`${API_BASE}/officer/buses`, {
            headers: getAuthHeaders()
        }, 60000);
        if (!res.ok) {
            const errMsg = await parseErrorResponse(res, 'Failed to fetch buses');
            throw new Error(errMsg);
        }
        const buses = await res.json();
        if (reqId !== reqSequence.buses) return;
        state.buses = buses;
        const statBusEl = document.getElementById('statBusCount');
        if (statBusEl) statBusEl.textContent = buses.length;

        if (!tbody) return;
        if (buses.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center">No buses registered.</td></tr>';
            return;
        }

        tbody.innerHTML = buses.map(bus => {
            const hasRestTimes = bus.restStart && bus.restEnd;
            const startStr = hasRestTimes ? String(bus.restStart).substring(0, 5) : '';
            const endStr = hasRestTimes ? String(bus.restEnd).substring(0, 5) : '';
            const durStr = bus.restDurationMinutes ? ` (${bus.restDurationMinutes}m)` : '';
            const restWindow = hasRestTimes
                ? `${startStr} – ${endStr}${durStr}`
                : ((bus.restWindowStart && bus.restWindowEnd) ? `${bus.restWindowStart} – ${bus.restWindowEnd}` : 'None assigned');

            const isAvail = bus.available !== false;
            const statusBadge = isAvail
                ? '<span class="status-badge badge-active">Available</span>'
                : `<span class="status-badge badge-resting" title="${bus.availabilityReason || 'Resting / Assigned'}">${bus.availabilityReason ? bus.availabilityReason : 'Unavailable'}</span>`;

            return `
                <tr>
                    <td><strong>#${bus.busId}</strong></td>
                    <td>${bus.registrationNumber || '-'}</td>
                    <td><strong>${bus.capacity}</strong> seats</td>
                    <td>${restWindow}</td>
                    <td>${statusBadge}</td>
                </tr>
            `;
        }).join('');
    } catch (err) {
        if (reqId !== reqSequence.buses) return;
        const userMsg = formatErrorMessage(err, 'Error loading buses');
        if (tbody) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="5" class="text-center text-danger" style="padding: 1.25rem;">
                        <p style="margin-bottom: 0.5rem;"><strong>Failed to load fleet data:</strong> ${userMsg}</p>
                        <button type="button" class="action-btn-small" onclick="fetchBuses()">&#x21bb; Retry Fleet</button>
                    </td>
                </tr>
            `;
        }
    }
}

// Fetch Routes
async function fetchRoutes() {
    const reqId = ++reqSequence.routes;
    const routesContainer = document.getElementById('sessionRoutesList');
    try {
        const res = await fetchWithTimeout(`${API_BASE}/officer/routes`, {
            headers: getAuthHeaders()
        }, 60000);
        if (!res.ok) {
            const errMsg = await parseErrorResponse(res, 'Failed to fetch routes');
            throw new Error(errMsg);
        }
        const routes = await res.json();
        if (reqId !== reqSequence.routes) return;
        state.routes = routes;
        const statRouteEl = document.getElementById('statRouteCount');
        if (statRouteEl) statRouteEl.textContent = routes.length;
        renderSessionConfig();
    } catch (err) {
        if (reqId !== reqSequence.routes) return;
        console.error('Failed to fetch routes:', err);
        const userMsg = formatErrorMessage(err, 'Failed to load routes');
        if (routesContainer) {
            routesContainer.innerHTML = `
                <div class="text-danger" style="padding: 0.75rem 0;">
                    <p style="margin-bottom: 0.5rem;"><strong>Error loading routes:</strong> ${userMsg}</p>
                    <button type="button" class="action-btn-small" onclick="fetchRoutes()">&#x21bb; Retry Routes</button>
                </div>
            `;
        }
    }
}

// Fetch Trips
async function fetchTrips() {
    const reqId = ++reqSequence.trips;
    const filterEl = document.getElementById('tripDateFilter');
    let dateInput = filterEl ? filterEl.value : '';
    if (!dateInput && state.activeSession && state.activeSession.startedAt) {
        dateInput = state.activeSession.startedAt.substring(0, 10);
        if (filterEl) filterEl.value = dateInput;
    }
    const statDateEl = document.getElementById('statServiceDate');
    if (statDateEl && dateInput) statDateEl.textContent = dateInput;

    const tbody = document.querySelector('#tripsTable tbody');
    const demandTripSelect = document.getElementById('demandTripSelect');

    if (tbody) tbody.innerHTML = '<tr><td colspan="8" class="text-center">Loading scheduled departures...</td></tr>';

    try {
        const url = dateInput ? `${API_BASE}/officer/trips?date=${encodeURIComponent(dateInput)}` : `${API_BASE}/officer/trips`;
        const res = await fetchWithTimeout(url, {
            headers: getAuthHeaders()
        }, 60000);
        if (!res.ok) {
            const errMsg = await parseErrorResponse(res, 'Failed to fetch departures');
            throw new Error(errMsg);
        }
        const trips = await res.json();
        if (reqId !== reqSequence.trips) return;
        state.trips = trips;
        const statTripEl = document.getElementById('statTripCount');
        if (statTripEl) statTripEl.textContent = trips.length;

        // Populate Demand Trip select
        if (demandTripSelect) {
            const previousSelected = demandTripSelect.value;
            demandTripSelect.innerHTML = '<option value="">Select trip</option>' +
                trips.map(t => {
                    const timeLabel = t.departureTime ? (t.departureTime.length > 16 ? t.departureTime.substring(11, 16) : t.departureTime) : 'Dep';
                    return `<option value="${t.tripId}">Trip #${t.tripId} (${t.routeCode || 'Route'} @ ${timeLabel})</option>`;
                }).join('');
            if (previousSelected && trips.some(t => String(t.tripId) === String(previousSelected))) {
                demandTripSelect.value = previousSelected;
            } else if (trips.length > 0) {
                demandTripSelect.selectedIndex = 1;
                loadDemandBreakdown(trips[0].tripId);
            }
        }

        if (!tbody) return;
        if (trips.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-center">No trips scheduled for this date.</td></tr>';
            return;
        }

        tbody.innerHTML = trips.map(t => {
            const busReg = t.currentBusRegistration || t.assignedBusNumber;
            const busCap = t.currentBusCapacity || t.busCapacity;
            const assignedBus = busReg ? `Bus #${busReg}` : '<span class="text-muted">Unassigned</span>';
            const cap = busCap ? `${busCap} seats` : '-';
            const depLabel = t.departureTime ? (t.departureTime.length > 16 ? t.departureTime.substring(11, 16) : t.departureTime) : '-';
            const arrLabel = t.expectedArrivalTime ? (t.expectedArrivalTime.length > 16 ? t.expectedArrivalTime.substring(11, 16) : t.expectedArrivalTime) : (t.arrivalTime || '-');
            const booked = t.bookedPassengerCount != null ? t.bookedPassengerCount : (t.demand || 0);
            const inq = t.activeInquiryCount != null ? t.activeInquiryCount : 0;
            const totalD = t.totalDemand != null ? t.totalDemand : (booked + inq);
            const peakDemand = t.peakSegmentDemand != null ? t.peakSegmentDemand : (t.demand || 0);

            let demandBadge = '';
            if (busCap && busCap > 0) {
                const occ = Math.round((booked / busCap) * 100);
                if (occ > 100) {
                    demandBadge = `<span class="status-badge badge-resting" style="margin-left: 6px;">Overcap (${occ}%)</span>`;
                } else if (occ >= 80) {
                    demandBadge = `<span class="status-badge badge-changed" style="margin-left: 6px;">${occ}%</span>`;
                } else {
                    demandBadge = `<span class="status-badge badge-active" style="margin-left: 6px;">${occ}%</span>`;
                }
            }
            const inqText = inq > 0 ? ` <span style="font-size:12px; color:#64748b;">(+${inq} inq)</span>` : '';
            const peakInfo = `<div style="font-size:11px; color:#64748b; font-weight:normal;">Peak Seg: ${peakDemand} pax</div>`;
            const demandDisplay = `<div><strong>${booked} booked</strong>${inqText}${demandBadge}</div>${peakInfo}`;

            return `
                <tr>
                    <td><strong>#${t.tripId}</strong></td>
                    <td><span class="route-tag">${t.routeCode || t.routeId}</span></td>
                    <td>${depLabel}</td>
                    <td>${arrLabel}</td>
                    <td>${assignedBus}</td>
                    <td>${cap}</td>
                    <td>${demandDisplay}</td>
                    <td>
                        <button type="button" class="action-btn-small" onclick="loadTripDemand(${t.tripId})">View Demand</button>
                    </td>
                </tr>
            `;
        }).join('');
    } catch (err) {
        if (reqId !== reqSequence.trips) return;
        const userMsg = formatErrorMessage(err, 'Error loading trips');
        if (tbody) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="8" class="text-center text-danger" style="padding: 1.25rem;">
                        <p style="margin-bottom: 0.5rem;"><strong>Failed to load scheduled departures:</strong> ${userMsg}</p>
                        <button type="button" class="action-btn-small" onclick="fetchTrips()">&#x21bb; Retry Departures</button>
                    </td>
                </tr>
            `;
        }
    }
}

// Load Segment Demand for Trip Modal/Panel
async function loadTripDemand(tripId) {
    const reqId = ++reqSequence.tripDemand;
    const panel = document.getElementById('tripDemandPanel');
    const details = document.getElementById('tripDemandDetails') || document.getElementById('demandDetails');
    if (!details) return;

    if (panel) panel.hidden = false;
    details.innerHTML = '<div class="text-muted">Loading segment demand...</div>';

    // Switch to Trips tab
    document.querySelectorAll('.tab-btn, .nav-tab').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    const tripsBtn = document.querySelector('[data-tab="tab-trips"]');
    if (tripsBtn) tripsBtn.classList.add('active');
    const tripsSection = document.getElementById('tab-trips');
    if (tripsSection) tripsSection.classList.add('active');

    try {
        const res = await fetchWithTimeout(`${API_BASE}/officer/trips/${tripId}/demand`, {
            headers: getAuthHeaders()
        }, 60000);
        if (!res.ok) {
            const errMsg = await parseErrorResponse(res, `Failed to load demand for Trip #${tripId}`);
            throw new Error(errMsg);
        }
        const demand = await res.json();
        if (reqId !== reqSequence.tripDemand) return;

        if (!demand || demand.length === 0) {
            details.innerHTML = `<p class="text-muted">No segment demand registered for Trip #${tripId}.</p>`;
            return;
        }

        const routeTitle = demand[0]?.routeCode || 'Route';
        details.innerHTML = `<h4>Demand Profile: Trip #${tripId} (${routeTitle})</h4>` +
            demand.map(d => {
                const existingD = d.existingDemand ?? ((d.registeredDemand ?? 0) + (d.searchIntentDemand ?? d.searchDemand ?? 0)) ?? (d.baseDemand ?? 0);
                const simAdj = d.simulatedAdjustment ?? 0;
                const effD = d.effectiveDemand ?? d.adjustedDemand ?? Math.max(0, existingD + simAdj);
                const adjLabel = simAdj !== 0 ? (simAdj > 0 ? `+${simAdj}` : `${simAdj}`) : '0';
                const cap = d.currentCapacity ? `${d.currentCapacity} seats` : (d.currentBus && d.currentBus !== 'UNASSIGNED' ? d.currentBus : 'None');
                return `
                    <div class="segment-card">
                        <div class="segment-name">${d.segmentIndex || '#'}. ${d.startStopName || 'Start'} &rarr; ${d.endStopName || 'End'}</div>
                        <div class="segment-metrics">
                            <span>Bookings / Inquiries: <strong>${existingD}</strong></span>
                            <span>Admin Adjustments: <strong>${adjLabel}</strong></span>
                            <span>Total Segment Demand: <strong>${effD} pax</strong></span>
                            <span>Current Bus Capacity: <strong>${cap}</strong></span>
                        </div>
                    </div>
                `;
            }).join('');

        if (panel) {
            panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    } catch (err) {
        if (reqId !== reqSequence.tripDemand) return;
        const userMsg = formatErrorMessage(err, `Failed to load demand for Trip #${tripId}`);
        details.innerHTML = `
            <div class="text-danger" style="padding: 1rem 0;">
                <p style="margin-bottom: 0.5rem;"><strong>Error:</strong> ${userMsg}</p>
                <button type="button" class="action-btn-small" onclick="loadTripDemand(${tripId})">&#x21bb; Retry Loading Demand</button>
            </div>
        `;
    }
}

// Demand Management Controls (Single Source of Truth)
async function loadDemandBreakdown(tripId) {
    const reqId = ++reqSequence.demandBreakdown;
    const liveContainer = document.getElementById('demandLiveBreakdown');
    if (!liveContainer || !tripId) return;

    liveContainer.innerHTML = '<p class="text-muted">Loading trip demand segments...</p>';
    try {
        const res = await fetchWithTimeout(`${API_BASE}/officer/trips/${tripId}/demand`, {
            headers: getAuthHeaders()
        }, 60000);
        if (!res.ok) {
            const errMsg = await parseErrorResponse(res, `Failed to load breakdown for Trip #${tripId}`);
            throw new Error(errMsg);
        }
        const segments = await res.json();
        if (reqId !== reqSequence.demandBreakdown) return;

        if (!segments || segments.length === 0) {
            liveContainer.innerHTML = `<p class="text-muted">No segment demand registered for Trip #${tripId}.</p>`;
            return;
        }

        liveContainer.innerHTML = segments.map(s => {
            const existingD = s.existingDemand ?? ((s.registeredDemand ?? 0) + (s.searchIntentDemand ?? s.searchDemand ?? 0)) ?? (s.baseDemand ?? 0);
            const simAdj = s.simulatedAdjustment ?? 0;
            const effD = s.effectiveDemand ?? s.adjustedDemand ?? Math.max(0, existingD + simAdj);
            const adjLabel = simAdj !== 0 ? (simAdj > 0 ? `+${simAdj}` : `${simAdj}`) : '0';
            const cap = s.currentCapacity ? `${s.currentCapacity} seats` : (s.currentBus || 'None');
            return `
                <div class="segment-card" style="margin-bottom: 8px;">
                    <div class="segment-title">Segment ${s.segmentIndex || '#'}: ${s.startStopName || 'Start'} &rarr; ${s.endStopName || 'End'}</div>
                    <div class="segment-stats">
                        <span>Bookings / Inquiries: <strong>${existingD}</strong></span>
                        <span>Admin Adjustments: <strong>${adjLabel}</strong></span>
                        <span>Total Segment Demand: <strong>${effD} pax</strong></span>
                        <span>Current Bus Capacity: <strong>${cap}</strong></span>
                    </div>
                </div>
            `;
        }).join('');
    } catch (err) {
        if (reqId !== reqSequence.demandBreakdown) return;
        const userMsg = formatErrorMessage(err, `Failed to load breakdown for Trip #${tripId}`);
        liveContainer.innerHTML = `
            <div class="text-danger" style="padding: 0.75rem 0;">
                <p style="margin-bottom: 0.5rem;"><strong>Error:</strong> ${userMsg}</p>
                <button type="button" class="action-btn-small" onclick="loadDemandBreakdown(${tripId})">&#x21bb; Retry Breakdown</button>
            </div>
        `;
    }
}

const demandTripSelect = document.getElementById('demandTripSelect');
if (demandTripSelect) {
    demandTripSelect.addEventListener('change', (e) => {
        const tripId = e.target.value;
        if (tripId) {
            loadDemandBreakdown(tripId);
        }
    });
}

document.querySelectorAll('.quick-delta').forEach(btn => {
    btn.addEventListener('click', () => {
        const delta = btn.getAttribute('data-delta');
        const input = document.getElementById('demandDelta');
        if (input) input.value = delta;
    });
});

const applyDemandAdjustmentBtn = document.getElementById('applyDemandAdjustmentBtn');
if (applyDemandAdjustmentBtn) {
    applyDemandAdjustmentBtn.addEventListener('click', async () => {
        const tripId = document.getElementById('demandTripSelect')?.value;
        const delta = parseInt(document.getElementById('demandDelta')?.value, 10);
        const feedback = document.getElementById('demandAdjustmentFeedback');

        if (!tripId) {
            if (feedback) {
                feedback.textContent = 'Please select a target trip first.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }
        if (isNaN(delta) || delta === 0) {
            if (feedback) {
                feedback.textContent = 'Please enter a non-zero adjustment delta.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        if (feedback) {
            feedback.textContent = 'Saving adjustment to common demand repository...';
            feedback.className = 'feedback-msg text-info';
        }
        applyDemandAdjustmentBtn.disabled = true;

        try {
            const res = await fetchWithTimeout(`${API_BASE}/admin/demand/adjust`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    tripId: parseInt(tripId, 10),
                    demandDelta: delta
                })
            }, 60000);

            if (!res.ok) {
                const errMsg = await parseErrorResponse(res, 'Failed to apply adjustment');
                throw new Error(errMsg);
            }

            if (feedback) {
                feedback.textContent = `Successfully saved ${delta > 0 ? '+' : ''}${delta} demand adjustment to Trip #${tripId}. Synchronized across system!`;
                feedback.className = 'feedback-msg text-success';
            }

            // Refresh trips, breakdown, and buses immediately
            await fetchTrips();
            loadDemandBreakdown(tripId);
            const panel = document.getElementById('tripDemandPanel');
            if (panel && !panel.hidden) {
                window.loadTripDemand(tripId);
            }
        } catch (err) {
            if (feedback) {
                const userMsg = formatErrorMessage(err, 'Failed to apply adjustment');
                feedback.textContent = `Failed to apply adjustment: ${userMsg}`;
                feedback.className = 'feedback-msg text-danger';
            }
        } finally {
            applyDemandAdjustmentBtn.disabled = false;
        }
    });
}

// Allocation Engine Controls
const runAllocationBtn = document.getElementById('runAllocationBtn');
if (runAllocationBtn) {
    runAllocationBtn.addEventListener('click', async () => {
        const loading = document.getElementById('allocationLoading');
        const panel = document.getElementById('allocationResultsPanel');
        const tbody = document.querySelector('#allocationTable tbody');
        const feedback = document.getElementById('approvalFeedback');
        if (feedback) feedback.textContent = '';

        if (loading) loading.hidden = false;
        if (panel) panel.hidden = true;
        runAllocationBtn.disabled = true;

        try {
            const dateInput = document.getElementById('tripDateFilter')?.value
                || (state.activeSession?.startedAt ? state.activeSession.startedAt.substring(0, 10) : '');
            const sessionId = state.activeSession?.sessionId || 1;

            const res = await fetchWithTimeout(`${API_BASE}/officer/allocations`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    sessionId: sessionId,
                    serviceDate: dateInput
                })
            }, 90000); // 90s for optimization solve + cold start

            if (!res.ok) {
                const errMsg = await parseErrorResponse(res, 'Allocation computation failed');
                throw new Error(errMsg);
            }
            const proposal = await res.json();
            state.lastAllocationProposal = proposal;

            if (panel) panel.hidden = false;

            // Section 1: Demand Analysis Cards
            const da = proposal.demandAnalysis || {};
            const badge = document.getElementById('allocStatusBadge');
            if (badge) {
                badge.textContent = proposal.status || 'PROPOSED';
                badge.className = 'metric-value badge-proposed';
            }
            const tripsCount = document.getElementById('allocTripsCount');
            if (tripsCount) tripsCount.textContent = da.tripsConsidered ?? da.totalTripsEvaluated ?? proposal.assignments?.length ?? 0;
            const peakDemandEl = document.getElementById('allocPeakDemand');
            if (peakDemandEl) peakDemandEl.textContent = `${da.totalPeakDemand ?? 0} pax`;
            const totalDemand = document.getElementById('allocTotalDemand');
            if (totalDemand) {
                const segs = da.totalDemandSegments ?? da.totalDemand ?? 0;
                totalDemand.textContent = `${segs} pax-seg`;
            }
            const baselineUnmet = document.getElementById('allocBaselineUnmet');
            if (baselineUnmet) {
                const bu = proposal.baselineUnmetDemand ?? da.baselineUnmetDemand ?? proposal.baselineMetrics?.unmet ?? 0;
                baselineUnmet.textContent = `${bu} pax-seg`;
            }
            const proposedUnmet = document.getElementById('allocProposedUnmet');
            if (proposedUnmet) {
                const pu = proposal.proposedUnmetDemand ?? da.proposedUnmetDemand ?? proposal.proposedMetrics?.unmet ?? 0;
                proposedUnmet.textContent = `${pu} pax-seg`;
            }
            const accommodated = document.getElementById('allocAccommodated');
            if (accommodated) {
                const acc = da.accommodatedDemandSegments ?? da.accommodatedDemand ?? proposal.proposedMetrics?.accommodated ?? 0;
                accommodated.textContent = `${acc} pax-seg`;
            }

            // Section 2: Fleet Availability Cards
            const fa = proposal.fleetAvailability || {};
            const fleetTotal = document.getElementById('fleetTotalCount');
            if (fleetTotal) fleetTotal.textContent = fa.totalFleet ?? fa.totalFleetBuses ?? state.buses.length;
            const fleetAvail = document.getElementById('fleetAvailableCount');
            const eligCount = fa.busesEligible ?? proposal.busesConsidered ?? 0;
            const assignCount = fa.busesAssigned ?? proposal.busesAssigned ?? 0;
            if (fleetAvail) fleetAvail.textContent = `${assignCount} assigned (${eligCount} active)`;
            const fleetResting = document.getElementById('fleetRestingCount');
            const unassignedCount = fa.busesUnassigned ?? proposal.busesLeftUnassigned ?? 0;
            const unavailCount = fa.busesUnavailable ?? ((fa.totalFleet || 0) - eligCount);
            if (fleetResting) {
                fleetResting.textContent = unavailCount > 0 
                    ? `${unassignedCount} idle (${unavailCount} maintenance)` 
                    : `${unassignedCount} idle`;
            }

            // Section 3: Proposed Bus Assignments Table with Plain-English Explanations
            if (tbody) {
                if (!proposal.assignments || proposal.assignments.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="text-center">No assignments generated.</td></tr>';
                } else {
                    tbody.innerHTML = proposal.assignments.map(a => {
                        const isChanged = a.previousBusId !== a.proposedBusId;
                        const changeTag = isChanged 
                            ? '<span class="status-badge badge-changed" style="margin-left: 6px;">Reassigned</span>'
                            : '<span class="status-badge badge-same" style="margin-left: 6px;">Retained</span>';

                        const prevReg = a.previousBusRegistration || (a.previousBusId ? 'Bus #' + a.previousBusId : 'None');
                        const propReg = a.proposedBusRegistration || (a.proposedBusId ? 'Bus #' + a.proposedBusId : 'Unassigned');

                        const prevCap = a.baselineCapacity ?? a.previousCapacity ?? 0;
                        const propCap = a.proposedCapacity ?? 0;
                        let peakD = a.peakDemand ?? a.demand ?? 0;
                        if (!peakD && a.reason) {
                            const m = a.reason.match(/(?:for|load of|is)\s+(\d+)\s+peak\s+passengers/i) || a.reason.match(/Peak demand is\s+(\d+)\s+passengers/i);
                            if (m) peakD = parseInt(m[1], 10);
                        }

                        const occPct = a.occupancy ?? (propCap > 0 ? Math.round((peakD / propCap) * 100) : 0);
                        let occBadge = '';
                        if (occPct > 100) {
                            occBadge = `<span class="status-badge badge-resting">${occPct}% (Over)</span>`;
                        } else if (occPct >= 80) {
                            occBadge = `<span class="status-badge badge-changed">${occPct}%</span>`;
                        } else {
                            occBadge = `<span class="status-badge badge-active">${occPct}%</span>`;
                        }

                        const depLabel = a.departureTime ? (a.departureTime.length > 16 ? a.departureTime.substring(11, 16) : a.departureTime) : '-';
                        const arrLabel = a.arrivalTime ? (a.arrivalTime.length > 16 ? a.arrivalTime.substring(11, 16) : a.arrivalTime) : '-';
                        const explanationText = a.reason || a.explanation || a.outcome || 'Assigned based on passenger demand and fleet availability.';

                        return `
                            <tr>
                                <td>
                                    <strong>#${a.tripId}</strong>
                                    <span class="route-tag" style="margin-left: 4px;">${a.routeCode || ''}</span>
                                    ${changeTag}
                                </td>
                                <td>${depLabel} &rarr; ${arrLabel}</td>
                                <td><strong>${peakD} pax</strong></td>
                                <td>${prevReg} (${prevCap}s)</td>
                                <td><strong>${propReg} (${propCap}s)</strong></td>
                                <td>${occBadge}</td>
                                <td>
                                    <div style="font-size: 12px; line-height: 1.45; color: #1e293b; max-width: 380px;">
                                        ${explanationText}
                                    </div>
                                </td>
                            </tr>
                        `;
                    }).join('');
                }
            }

            // Section 4: Operational State Comparison
            const compText = document.getElementById('comparisonSummaryText');
            if (compText) {
                const bUnmet = proposal.baselineUnmetDemand ?? 0;
                const pUnmet = proposal.proposedUnmetDemand ?? 0;
                const diff = bUnmet - pUnmet;
                const pct = bUnmet > 0 ? Math.round((diff / bUnmet) * 100) : 100;
                compText.innerHTML = `
                    <strong>Operational Impact:</strong> Baseline unmet demand reduced from 
                    <strong class="text-danger">${bUnmet} pax</strong> to 
                    <strong class="text-success">${pUnmet} pax</strong> 
                    (${pct}% unmet demand eliminated). Right-sized fleet allocation eliminates bottlenecks while strictly respecting 30-min turnarounds and driver rest windows.
                `;
            }
        } catch (err) {
            const userMsg = formatErrorMessage(err, 'Allocation computation failed');
            alert(`Allocation computation failed: ${userMsg}`);
        } finally {
            if (loading) loading.hidden = true;
            runAllocationBtn.disabled = false;
        }
    });
}

const approveAllocationBtn = document.getElementById('approveAllocationBtn');
if (approveAllocationBtn) {
    approveAllocationBtn.addEventListener('click', async () => {
        if (!state.lastAllocationProposal || !state.lastAllocationProposal.allocationRunId) {
            alert('No active allocation proposal to approve. Please run allocation first.');
            return;
        }

        const feedback = document.getElementById('approvalFeedback');
        if (feedback) {
            feedback.textContent = 'Persisting approved allocation to database...';
            feedback.className = 'feedback-msg text-info';
        }
        approveAllocationBtn.disabled = true;

        try {
            const res = await fetchWithTimeout(`${API_BASE}/officer/allocations/approve`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    allocationRunId: state.lastAllocationProposal.allocationRunId
                })
            }, 60000);

            if (!res.ok) {
                const errMsg = await parseErrorResponse(res, 'Approval failed');
                throw new Error(errMsg);
            }
            const result = await res.json();

            if (feedback) {
                feedback.textContent = `Allocation #${state.lastAllocationProposal.allocationRunId} successfully APPROVED and persisted into operational schedule!`;
                feedback.className = 'feedback-msg text-success';
            }
            const badge = document.getElementById('allocStatusBadge');
            if (badge) {
                badge.textContent = 'APPROVED';
                badge.className = 'metric-value badge-approved';
            }

            // Refresh trips and buses so approved assignments reflect immediately
            fetchTrips();
            fetchBuses();
        } catch (err) {
            if (feedback) {
                const userMsg = formatErrorMessage(err, 'Approval failed');
                feedback.textContent = `Approval failed: ${userMsg}`;
                feedback.className = 'feedback-msg text-danger';
            }
        } finally {
            approveAllocationBtn.disabled = false;
        }
    });
}

// Button triggers
const refreshBusesBtn = document.getElementById('refreshBusesBtn');
if (refreshBusesBtn) refreshBusesBtn.addEventListener('click', () => fetchBuses());

const refreshTripsBtn = document.getElementById('refreshTripsBtn');
if (refreshTripsBtn) refreshTripsBtn.addEventListener('click', () => fetchTrips());

const tripDateFilter = document.getElementById('tripDateFilter');
if (tripDateFilter) tripDateFilter.addEventListener('change', () => fetchTrips());

// Session Configuration (SRS F4 / U2)
function renderSessionConfig() {
    const routesContainer = document.getElementById('sessionRoutesList');

    const selectedRoutes = state.activeSession?.selectedRouteIds || [];
    const hasExplicitRouteSelection = state.activeSession && Array.isArray(state.activeSession.selectedRouteIds);

    if (routesContainer) {
        if (!state.routes || state.routes.length === 0) {
            routesContainer.innerHTML = `
                <div style="padding: 8px 0;">
                    <p class="text-muted" style="margin-bottom: 6px;">No routes loaded.</p>
                    <button type="button" class="action-btn-small" onclick="fetchRoutes()">&#x21bb; Reload Routes</button>
                </div>
            `;
        } else {
            routesContainer.innerHTML = state.routes.map(r => {
                const isChecked = hasExplicitRouteSelection
                    ? selectedRoutes.includes(r.routeId)
                    : true;
                const rCode = r.routeCode || r.routeNumber || ('Route #' + r.routeId);
                const rOrigin = r.originName || r.origin || 'Origin';
                const rDest = r.destinationName || r.destination || 'Destination';
                return `
                <div class="checkbox-item" style="padding: 6px 0;">
                    <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
                        <input type="checkbox" class="session-route-check" value="${r.routeId}" ${isChecked ? 'checked' : ''}>
                        <span><strong>${rCode}</strong>: ${rOrigin} &rarr; ${rDest}</span>
                    </label>
                </div>
            `;
            }).join('');
        }
    }
}

const saveSessionConfigBtn = document.getElementById('saveSessionConfigBtn');
if (saveSessionConfigBtn) {
    saveSessionConfigBtn.addEventListener('click', async () => {
        const feedback = document.getElementById('sessionConfigFeedback');
        if (feedback) {
            feedback.textContent = 'Saving session configuration...';
            feedback.className = 'feedback-msg text-info';
        }

        const selectedRoutes = Array.from(document.querySelectorAll('.session-route-check:checked'))
            .map(cb => parseInt(cb.value, 10));

        if (selectedRoutes.length === 0) {
            if (feedback) {
                feedback.textContent = 'Please select at least one route for the session.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        const sessionId = state.activeSession?.sessionId || 1;
        saveSessionConfigBtn.disabled = true;

        try {
            const res = await fetchWithTimeout(`${API_BASE}/admin/sessions/${sessionId}/selection`, {
                method: 'PUT',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    routeIds: selectedRoutes
                })
            }, 60000);

            if (!res.ok) {
                const errMsg = await parseErrorResponse(res, 'Failed to save session configuration');
                throw new Error(errMsg);
            }
            if (feedback) {
                feedback.textContent = `Session configuration saved (${selectedRoutes.length} route(s) active). Synchronizing departures...`;
                feedback.className = 'feedback-msg text-success';
            }

            // Immediately refresh active session, trips, and buses so unselected routes (e.g. Route 2) vanish immediately!
            await fetchActiveSession();
            await Promise.allSettled([fetchTrips(), fetchBuses()]);

            if (feedback) {
                feedback.textContent = `Session configuration saved (${selectedRoutes.length} route(s) active). Departures and fleet updated!`;
                feedback.className = 'feedback-msg text-success';
            }
        } catch (err) {
            if (feedback) {
                const userMsg = formatErrorMessage(err, 'Failed to save session configuration');
                feedback.textContent = `Failed to save session configuration: ${userMsg}`;
                feedback.className = 'feedback-msg text-danger';
            }
        } finally {
            saveSessionConfigBtn.disabled = false;
        }
    });
}

const refreshSessionConfigBtn = document.getElementById('refreshSessionConfigBtn');
if (refreshSessionConfigBtn) {
    refreshSessionConfigBtn.addEventListener('click', async () => {
        const feedback = document.getElementById('sessionConfigFeedback');
        if (feedback) {
            feedback.textContent = 'Refreshing session data...';
            feedback.className = 'feedback-msg text-info';
        }
        refreshSessionConfigBtn.disabled = true;
        try {
            await Promise.allSettled([fetchRoutes(), fetchBuses()]);
            renderSessionConfig();
            if (feedback) {
                feedback.textContent = 'Session data refreshed.';
                feedback.className = 'feedback-msg text-info';
            }
        } finally {
            refreshSessionConfigBtn.disabled = false;
        }
    });
}

// Globally expose read-only and drilldown functions for inline HTML event triggers
window.fetchBuses = fetchBuses;
window.fetchTrips = fetchTrips;
window.fetchRoutes = fetchRoutes;
window.fetchActiveSession = fetchActiveSession;
window.loadTripDemand = loadTripDemand;
window.loadDemandBreakdown = loadDemandBreakdown;

// Initialize on page load
checkAuth();

