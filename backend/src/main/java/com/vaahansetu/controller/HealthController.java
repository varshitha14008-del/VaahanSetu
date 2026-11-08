package com.vaahansetu.controller;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check endpoints for orchestration, load balancers, and monitoring.
 *
 * GET /api/health/liveness  - Lightweight probe indicating Spring Boot application is alive (no DB query).
 * GET /api/health/readiness - Probe verifying active database connection capability (HTTP 200 on UP, 503 on DOWN).
 */
@RestController
@RequestMapping("/api/health")
@CrossOrigin(origins = "*")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Liveness probe: returns HTTP 200 when the application process is running.
     * Does NOT query PostgreSQL/Neon and does not consume database resources.
     */
    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> getLiveness() {
        return ResponseEntity.ok(Map.of(
                "status", "UP"
        ));
    }

    /**
     * Readiness probe: checks whether the application can obtain and execute on a database connection.
     * Returns HTTP 200 when database is responsive.
     * Returns HTTP 503 when database is unavailable, timed out, or connection fails.
     * Sanitizes output so credentials, connection strings, SQL errors, or stack traces are never exposed.
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> getReadiness() {
        if (dataSource == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "database", "DOWN",
                            "message", "Database DataSource is not configured."
                    ));
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(3);
            try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    return ResponseEntity.ok(Map.of(
                            "status", "UP",
                            "database", "UP"
                    ));
                }
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "database", "DOWN",
                            "message", "Database check returned unexpected result."
                    ));
        } catch (Exception ex) {
            logger.warn("Readiness database check failed: {}", ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "database", "DOWN",
                            "message", "Database service is currently unavailable."
                    ));
        }
    }
}
