package com.eventledger.gateway.controller;

import com.eventledger.gateway.model.dto.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Custom health endpoint for the Event Gateway.
 *
 * {@code GET /health} actively probes the H2 database with {@code SELECT 1}
 * and returns a structured JSON response. This is separate from Spring Boot
 * Actuator's {@code /actuator/health} (which reports the same database state
 * through the DataSourceHealthIndicator, plus circuit breaker state from
 * Resilience4j's health indicator when register-health-indicator=true).
 *
 * --- Response codes ---
 *   200 OK                 — database is reachable; overall status is UP
 *   503 Service Unavailable — database probe failed; overall status is DOWN
 *
 * For H2 in-memory databases this endpoint virtually always returns UP while
 * the application is running. It exists to validate the health check pattern
 * and to give load balancers or orchestrators a probing target.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;
    private final String serviceName;

    public HealthController(JdbcTemplate jdbcTemplate,
                             @Value("${spring.application.name}") String serviceName) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String dbStatus = probeDatabase();
        String overallStatus = "UP".equals(dbStatus) ? "UP" : "DOWN";

        HealthResponse response = new HealthResponse(
                overallStatus, dbStatus, serviceName, Instant.now());

        HttpStatus httpStatus = "UP".equals(overallStatus)
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(httpStatus).body(response);
    }

    private String probeDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            log.warn("Database health probe failed: {}", e.getMessage());
            return "DOWN";
        }
    }
}
