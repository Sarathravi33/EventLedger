package com.eventledger.gateway.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Inbound DTO for POST /events.
 *
 * Represents the raw JSON payload submitted by the client. Declared as a Java
 * record, which makes it immutable by construction — once deserialized, the
 * payload cannot be altered as it passes through the service layers.
 *
 * --- No validation annotations here ---
 * Validation constraints (@NotBlank, @Positive, @Pattern, etc.) are added in
 * Step 4 when spring-boot-starter-validation is introduced. Keeping this step
 * free of validation logic makes the data model easier to read in isolation.
 *
 * --- Instant deserialization ---
 * eventTimestamp arrives as an ISO-8601 string (e.g. "2026-05-15T14:02:11Z").
 * Jackson's JavaTimeModule, auto-configured by Spring Boot, converts it to
 * Instant automatically. The application.yml setting
 *   spring.jackson.serialization.write-dates-as-timestamps=false
 * ensures Instants are serialized back to ISO-8601 strings (not epoch numbers)
 * in all outbound responses.
 *
 * --- metadata ---
 * Optional field. Null when the client omits it. Jackson maps arbitrary JSON
 * objects to Map<String,Object> using LinkedHashMap for objects and ArrayList
 * for arrays as nested value types.
 */
public record EventRequest(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata    // optional; null when not provided
) {}
