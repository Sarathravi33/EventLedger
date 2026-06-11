package com.eventledger.gateway.model.dto;

import com.eventledger.gateway.model.EventStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Outbound DTO returned by all event endpoints on the Gateway:
 *   POST /events          — 201 Created on new event, 200 OK on duplicate
 *   GET  /events/{id}     — 200 OK or 404 Not Found
 *   GET  /events?account= — 200 OK with a list of these
 *
 * --- Entity boundary ---
 * EventEntity never crosses the controller boundary. The service layer maps
 * EventEntity → EventResponse before handing the result to the controller.
 * This decouples the API contract from the database schema: adding or renaming
 * a column in EventEntity does not automatically change the API response shape.
 *
 * --- metadata ---
 * Deserialized from the stored JSON string back to Map<String,Object> by the
 * service layer. Null when the original event had no metadata.
 * The application.yml setting
 *   spring.jackson.default-property-inclusion=non_null
 * ensures null fields (including metadata) are omitted from the JSON response.
 *
 * --- status ---
 * Reflects the event's current lifecycle state. Clients can use this to
 * determine whether the transaction was successfully applied (PROCESSED),
 * is still in progress (PENDING), or failed to reach the Account Service (FAILED).
 */
public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,   // null when original event had no metadata
        Instant receivedAt,
        EventStatus status
) {}
