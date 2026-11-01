"""
Mini backend for VaahanSetu -- runs the frontend against Neon directly.
No Java, no Maven, no Spring Boot needed.

Usage:
  1. pip install psycopg2-binary flask flask-cors
  2. Set DB password env var:
       $env:DB_PASSWORD = "your-neon-password"
  3. python mini_backend.py
  4. Open http://localhost:8080 in your browser -- the frontend will work
     with live data from Neon.

This is a STAND-IN for the real Spring Boot backend. It implements just
enough endpoints to make the passenger portal work. The real backend has
more features (allocation, simulation, etc.) -- for those, you need the
actual Java backend running.
"""

import os
import json
from flask import Flask, jsonify, request
from flask_cors import CORS
import psycopg2
from psycopg2.extras import RealDictCursor

# ----------------------------------------------------------------------
# Neon connection (reads password from env var, just like Spring Boot)
# ----------------------------------------------------------------------
NEON_URL = "postgresql://neondb_owner:{}@ep-rough-mode-azjj7ke6.c-3.ap-southeast-1.aws.neon.tech/vaahansetu?sslmode=require".format(
    os.environ.get("DB_PASSWORD", "")
)

app = Flask(__name__)
CORS(app)  # allow the frontend to call us from a different origin


def get_db():
    """Open a fresh connection per request. Neon handles pooling."""
    return psycopg2.connect(NEON_URL)


# ----------------------------------------------------------------------
# Endpoints the frontend needs
# ----------------------------------------------------------------------

@app.route("/")
def home():
    return "VaahanSetu mini backend is running! Open http://localhost:8080/frontend/index.html"


@app.route("/api/routes")
def get_routes():
    """Return all active routes. Matches RouteRepository.findAllActiveRoutes."""
    with get_db() as conn:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT route_id, route_code, route_name,
                       origin_name, destination_name,
                       estimated_duration_minutes
                FROM routes
                WHERE is_active = TRUE
                ORDER BY route_code
            """)
            rows = cur.fetchall()
    # Serialise to JSON-friendly dicts
    return jsonify([dict(r) for r in rows])


@app.route("/api/routes/<int:route_id>/stops")
def get_stops(route_id):
    """Return ordered stops for a route."""
    with get_db() as conn:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT s.stop_id, s.stop_name,
                       rs.stop_sequence, rs.scheduled_offset_minutes
                FROM route_stops rs
                JOIN stops s ON s.stop_id = rs.stop_id
                WHERE rs.route_id = %s
                ORDER BY rs.stop_sequence
            """, (route_id,))
            rows = cur.fetchall()
    return jsonify([dict(r) for r in rows])


@app.route("/api/routes/<int:route_id>/buses")
def get_buses(route_id):
    """Return buses currently allocated to trips on this route."""
    with get_db() as conn:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""
                SELECT DISTINCT b.bus_id, b.registration_number, b.capacity,
                       b.status, b.current_location,
                       b.rest_duration_minutes, b.rest_start, b.rest_end,
                       t.trip_id, t.departure_time
                FROM trip_assignments ta
                JOIN scheduled_trips t ON t.trip_id = ta.trip_id
                JOIN buses b ON b.bus_id = ta.bus_id
                WHERE t.route_id = %s
                  AND ta.status = 'ACTIVE'
                ORDER BY t.departure_time
            """, (route_id,))
            rows = cur.fetchall()
    # Convert datetime/time to strings for JSON
    for r in rows:
        if r.get("departure_time"):
            r["departure_time"] = r["departure_time"].isoformat()
        if r.get("rest_start"):
            r["rest_start"] = str(r["rest_start"])
        if r.get("rest_end"):
            r["rest_end"] = str(r["rest_end"])
    return jsonify([dict(r) for r in rows])


@app.route("/api/passenger-journeys/demand")
def get_demand():
    """Return demand summary per segment for a route (or all routes)."""
    route_id = request.args.get("routeId")
    with get_db() as conn:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            if route_id:
                cur.execute("""
                    SELECT r.route_code,
                           ss.stop_name AS segment_start,
                           es.stop_name AS segment_end,
                           COALESCE(SUM(pj.passenger_count), 0) AS demand
                    FROM routes r
                    JOIN route_stops rs1 ON rs1.route_id = r.route_id
                    JOIN route_stops rs2 ON rs2.route_id = r.route_id
                                            AND rs2.stop_sequence = rs1.stop_sequence + 1
                    JOIN stops ss ON ss.stop_id = rs1.stop_id
                    JOIN stops es ON es.stop_id = rs2.stop_id
                    LEFT JOIN passenger_journeys pj
                           ON pj.trip_id IN (SELECT trip_id FROM scheduled_trips WHERE route_id = r.route_id)
                          AND pj.boarding_stop_id = rs1.stop_id
                          AND pj.journey_status NOT IN ('CANCELLED', 'EXPIRED')
                    WHERE r.route_id = %s
                    GROUP BY r.route_code, ss.stop_name, es.stop_name, rs1.stop_sequence
                    ORDER BY rs1.stop_sequence
                """, (route_id,))
            else:
                cur.execute("""
                    SELECT r.route_code,
                           ss.stop_name AS segment_start,
                           es.stop_name AS segment_end,
                           COALESCE(SUM(pj.passenger_count), 0) AS demand
                    FROM routes r
                    JOIN route_stops rs1 ON rs1.route_id = r.route_id
                    JOIN route_stops rs2 ON rs2.route_id = r.route_id
                                            AND rs2.stop_sequence = rs1.stop_sequence + 1
                    JOIN stops ss ON ss.stop_id = rs1.stop_id
                    JOIN stops es ON es.stop_id = rs2.stop_id
                    LEFT JOIN passenger_journeys pj
                           ON pj.trip_id IN (SELECT trip_id FROM scheduled_trips WHERE route_id = r.route_id)
                          AND pj.boarding_stop_id = rs1.stop_id
                          AND pj.journey_status NOT IN ('CANCELLED', 'EXPIRED')
                    GROUP BY r.route_code, ss.stop_name, es.stop_name, rs1.stop_sequence
                    ORDER BY r.route_code, rs1.stop_sequence
                """)
            rows = cur.fetchall()
    return jsonify([dict(r) for r in rows])


@app.route("/api/passenger-journeys", methods=["POST"])
def register_journey():
    """Register a new passenger journey."""
    data = request.get_json()
    if not data:
        return jsonify({"error": "Please provide journey details."}), 400

    trip_id = data.get("tripId")
    boarding_stop_id = data.get("boardingStopId")
    destination_stop_id = data.get("destinationStopId")
    passenger_count = data.get("passengerCount", 1)
    session_id = data.get("sessionId", 1)  # default to first session

    if not all([trip_id, boarding_stop_id, destination_stop_id]):
        return jsonify({"error": "Missing required fields."}), 400

    try:
        with get_db() as conn:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute("""
                    INSERT INTO passenger_journeys
                        (session_id, trip_id, boarding_stop_id, destination_stop_id,
                         passenger_count, journey_status, passenger_reference)
                    VALUES (%s, %s, %s, %s, %s, 'REGISTERED',
                            'PJ-' || upper(LEFT(MD5(random()::text), 6)))
                    RETURNING journey_id, passenger_reference
                """, (session_id, trip_id, boarding_stop_id, destination_stop_id, passenger_count))
                conn.commit()
                result = cur.fetchone()
        return jsonify({
            "journeyId": result["journey_id"],
            "passengerReference": result["passenger_reference"],
            "status": "REGISTERED",
            "message": "Journey registered successfully!"
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ----------------------------------------------------------------------
# Serve the frontend static files too (so you only need one server)
# ----------------------------------------------------------------------
from flask import send_from_directory

FRONTEND_DIR = os.path.join(os.path.dirname(__file__), "frontend")

@app.route("/frontend/<path:path>")
def serve_frontend(path):
    return send_from_directory(FRONTEND_DIR, path)


@app.route("/frontend/")
@app.route("/frontend")
def serve_frontend_index():
    return send_from_directory(FRONTEND_DIR, "index.html")


# ----------------------------------------------------------------------
# Run on port 8080 (same as the Spring Boot backend)
# ----------------------------------------------------------------------
if __name__ == "__main__":
    print("=" * 60)
    print("  VaahanSetu mini backend")
    print("  Open: http://localhost:8080/frontend/index.html")
    print("=" * 60)
    print()
    print("  Endpoints:")
    print("    GET  /                                    health check")
    print("    GET  /api/routes                          list routes")
    print("    GET  /api/routes/<id>/stops               route stops")
    print("    GET  /api/routes/<id>/buses               allocated buses")
    print("    GET  /api/passenger-journeys/demand       segment demand")
    print("    POST /api/passenger-journeys              register journey")
    print()
    print("  Press Ctrl+C to stop")
    print()
    app.run(host="0.0.0.0", port=8080, debug=True)
