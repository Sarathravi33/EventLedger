package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.model.dto.EventRequest;
import com.eventledger.gateway.model.dto.EventResponse;
import com.eventledger.gateway.model.dto.EventSubmitResult;
import com.eventledger.gateway.model.entity.EventEntity;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Business logic for the Event Gateway's event lifecycle.
 *
 * Responsibilities:
 *   - Build and persist EventEntity from incoming EventRequest
 *   - Delegate the Account Service call to AccountServiceClient
 *   - Manage event status transitions (PENDING → PROCESSED | FAILED)
 *   - Map EventEntity → EventResponse before returning to the controller
 *
 * --- Transaction strategy ---
 * submitEvent is NOT annotated with @Transactional. This is intentional:
 * Spring Data's save() methods each run in their own short transaction, which
 * means the PENDING save is committed before the Account Service call, and the
 * FAILED save is committed even when AccountServiceUnavailableException propagates.
 * If the whole method were one transaction, a rollback on exception would
 * revert the status update and we'd lose the event's FAILED record.
 *
 * Read methods use @Transactional(readOnly = true) to optimise Hibernate's
 * flush mode and allow the JPA provider to skip dirty checking.
 *
 * --- Metadata serialisation ---
 * EventRequest.metadata is Map<String,Object>; EventEntity.metadata is TEXT.
 * ObjectMapper handles the conversion in both directions. Jackson is injected
 * as a Spring-managed bean so it carries the same configuration as the rest of
 * the application (write-dates-as-timestamps=false, non_null inclusion, etc.).
 *
 * --- Idempotency ---
 * submitEvent performs a PK lookup before any write. If the eventId already
 * exists, the existing EventEntity is returned immediately with duplicate=true
 * and no Account Service call is made. The controller maps duplicate=true → 200 OK
 * and duplicate=false → 201 Created so clients can distinguish first submission
 * from repeat submission.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        ObjectMapper objectMapper,
                        MetricsService metricsService) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Persists a new event and forwards it to the Account Service.
     *
     * Flow:
     *   0. Idempotency check — if eventId already exists, return existing event
     *      immediately (duplicate=true). No write, no Account Service call.
     *   1. Serialise optional metadata Map → JSON string.
     *   2. Save EventEntity with status PENDING (committed immediately).
     *   3. Call AccountServiceClient.applyTransaction.
     *   4a. Success → set status PROCESSED, save, return result (duplicate=false).
     *   4b. Failure → set status FAILED, save, rethrow so GlobalExceptionHandler
     *       maps AccountServiceUnavailableException to 503.
     *
     * The two-save pattern (PENDING then PROCESSED/FAILED) ensures the event is
     * always persisted regardless of whether the Account Service call succeeds,
     * which is important for the audit trail and idempotency.
     *
     * @return EventSubmitResult carrying the event and a flag the controller uses
     *         to choose between 201 Created (new) and 200 OK (duplicate)
     */
    public EventSubmitResult submitEvent(EventRequest request) {
        // Idempotency: fast PK lookup before any write. If the eventId already
        // exists (any status: PENDING, PROCESSED, or FAILED), return the stored
        // event without contacting the Account Service again.
        Optional<EventEntity> existing = eventRepository.findById(request.eventId());
        if (existing.isPresent()) {
            log.debug("Duplicate eventId {} (status={}) — returning existing event",
                    request.eventId(), existing.get().getStatus());
            metricsService.recordEventSubmitted(existing.get().getType(), "DUPLICATE");
            return new EventSubmitResult(toResponse(existing.get()), true);
        }

        String metadataJson = serializeMetadata(request.eventId(), request.metadata());

        EventEntity event = new EventEntity(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                metadataJson,
                Instant.now(),   // receivedAt: server clock at ingestion time
                EventStatus.PENDING
        );

        // Save PENDING so the event record exists before the Account Service call.
        // If the process crashes between here and the status update, the event
        // remains PENDING in the database — a visible audit trail.
        eventRepository.save(event);
        log.debug("Saved event {} with status PENDING", event.getEventId());

        Timer.Sample timerSample = metricsService.startAccountServiceCallTimer();
        try {
            accountServiceClient.applyTransaction(
                    event.getAccountId(),
                    event.getEventId(),
                    event.getType(),
                    event.getAmount(),
                    event.getCurrency(),
                    event.getEventTimestamp()
            );
            metricsService.recordAccountServiceCall(timerSample, "success");
            event.setStatus(EventStatus.PROCESSED);
            eventRepository.save(event);
            metricsService.recordEventSubmitted(event.getType(), "PROCESSED");
            log.debug("Event {} processed successfully", event.getEventId());

        } catch (AccountServiceUnavailableException e) {
            // Account Service was unreachable or the circuit breaker opened.
            // Save FAILED status so the client can see the event was received
            // but not applied, then propagate so the controller returns 503.
            metricsService.recordAccountServiceCall(timerSample, "failure");
            event.setStatus(EventStatus.FAILED);
            eventRepository.save(event);
            metricsService.recordEventSubmitted(event.getType(), "FAILED");
            log.warn("Event {} could not be applied — Account Service unavailable", event.getEventId());
            throw e;
        }

        return new EventSubmitResult(toResponse(event), false);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Retrieves a single event from the Gateway's local database by its primary key.
     *
     * Throws NoSuchElementException if the eventId does not exist.
     * GlobalExceptionHandler maps this to 404 Not Found.
     */
    @Transactional(readOnly = true)
    public EventResponse getEventById(String eventId) {
        EventEntity entity = eventRepository.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Event not found: " + eventId));
        return toResponse(entity);
    }

    /**
     * Returns all events for the given account, sorted by eventTimestamp ascending.
     *
     * Ordering by eventTimestamp (not by receivedAt or DB insertion order) is what
     * makes out-of-order delivery transparent: a late-arriving event with an older
     * timestamp appears in its correct chronological position in the list.
     *
     * Returns an empty list if the account has no events — never throws.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a Map<String,Object> to a JSON string for storage in the TEXT column.
     * Returns null when metadata is null (the column accepts null values).
     */
    private String serializeMetadata(String eventId, Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            // A Map<String,Object> built from a parsed JSON request body cannot
            // contain types that Jackson cannot serialise — this path is unreachable.
            log.warn("Unexpected metadata serialization failure for event {}", eventId);
            return null;
        }
    }

    /**
     * Maps an EventEntity to the outbound EventResponse DTO.
     *
     * Entities never cross the controller boundary — this helper is the single
     * place where the database representation is converted to the API contract.
     * Deserialises the stored JSON metadata string back to Map<String,Object>.
     */
    private EventResponse toResponse(EventEntity entity) {
        Map<String, Object> metadata = deserializeMetadata(entity.getEventId(), entity.getMetadata());
        return new EventResponse(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getEventTimestamp(),
                metadata,
                entity.getReceivedAt(),
                entity.getStatus()
        );
    }

    /**
     * Converts the stored JSON string back to Map<String,Object>.
     * Returns null when the stored value is null (event had no metadata).
     */
    private Map<String, Object> deserializeMetadata(String eventId, String metadataJson) {
        if (metadataJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            // The stored string was written by serializeMetadata — it is always valid JSON.
            // If this ever fires, it indicates data corruption.
            log.warn("Failed to deserialise stored metadata for event {}", eventId);
            return null;
        }
    }
}
