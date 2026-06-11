package com.eventledger.gateway.model.dto;

/**
 * Internal result type returned by {@link com.eventledger.gateway.service.EventService#submitEvent}.
 *
 * Carries the event response alongside a flag that tells the controller whether
 * the event was newly created or already existed. The controller uses the flag
 * to choose the correct HTTP status code:
 *
 *   duplicate = false → 201 Created  (first submission)
 *   duplicate = true  → 200 OK       (repeat submission of an existing eventId)
 *
 * The JSON response body is identical in both cases — the client always receives
 * an {@link EventResponse}. The only observable difference is the status code,
 * which allows clients to distinguish "accepted and applied" from "already seen".
 *
 * This type never leaves the service-controller boundary. It is not serialised
 * to JSON and is not part of the public API contract.
 */
public record EventSubmitResult(EventResponse event, boolean duplicate) {}
