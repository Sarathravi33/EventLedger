package com.eventledger.gateway.model.dto;

import java.time.Instant;

/**
 * Response body for {@code GET /health}.
 *
 * Fields:
 *   status   — overall health: "UP" when all components are healthy, "DOWN" otherwise
 *   database — result of the active H2 database probe (SELECT 1)
 *   service  — spring.application.name, so log aggregators and callers can identify the source
 *   timestamp — server time at the moment the check ran; useful for detecting stale responses
 */
public record HealthResponse(
        String status,
        String database,
        String service,
        Instant timestamp
) {}
