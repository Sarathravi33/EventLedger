package com.eventledger.gateway.model.entity;

import com.eventledger.gateway.model.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity mapped to the 'events' table in the Event Gateway's embedded H2 database.
 *
 * Represents a single transaction event received from an external client.
 * This entity exists only inside the Gateway — the Account Service never reads it.
 *
 * --- Idempotency ---
 * eventId is the client-supplied identifier and serves as the primary key.
 * Attempting to insert a row with a duplicate eventId will throw a
 * DataIntegrityViolationException, which the service layer catches to detect
 * and handle duplicate submissions without calling the Account Service.
 *
 * --- Two timestamps ---
 * eventTimestamp: the time the event originally occurred, as reported by the
 *                 upstream system. This is the authoritative ordering field.
 *                 Out-of-order delivery means this value may predate a previously
 *                 received event. All event listings are sorted by this field.
 *
 * receivedAt:     the server clock time when the Gateway first received the event.
 *                 Used for auditing and diagnostics only. Never used for ordering.
 *
 * --- metadata ---
 * Stored as a raw JSON string (TEXT column). The service layer is responsible
 * for serializing Map<String,Object> → String on write and deserializing on read.
 */
@Entity
@Table(name = "events")
public class EventEntity {

    // Client-supplied unique identifier. Primary key — drives idempotency.
    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    // "CREDIT" or "DEBIT" — validated before persistence, stored as a plain string.
    @Column(name = "type", nullable = false, length = 6)
    private String type;

    // DECIMAL(15,2) — supports values up to 9,999,999,999,999.99
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    // ISO 4217 currency code, e.g. "USD"
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // Original event time from the upstream system. Used for chronological ordering.
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    // Optional supplementary context, stored as a JSON string.
    // Null when the client did not include a metadata field in the request.
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    // Server-side arrival time. Recorded once at ingestion; never changes.
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // PENDING → PROCESSED on Account Service success.
    // PENDING → FAILED on Account Service failure or circuit breaker open.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private EventStatus status;

    // No-arg constructor required by JPA. Not intended for use by application code.
    protected EventEntity() {}

    public EventEntity(String eventId,
                       String accountId,
                       String type,
                       BigDecimal amount,
                       String currency,
                       Instant eventTimestamp,
                       String metadata,
                       Instant receivedAt,
                       EventStatus status) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.receivedAt = receivedAt;
        this.status = status;
    }

    public String getEventId()           { return eventId; }
    public String getAccountId()         { return accountId; }
    public String getType()              { return type; }
    public BigDecimal getAmount()        { return amount; }
    public String getCurrency()          { return currency; }
    public Instant getEventTimestamp()   { return eventTimestamp; }
    public String getMetadata()          { return metadata; }
    public Instant getReceivedAt()       { return receivedAt; }
    public EventStatus getStatus()       { return status; }

    // status is the only mutable field — updated as the event progresses
    // from PENDING to PROCESSED or FAILED after the Account Service call.
    public void setStatus(EventStatus status) { this.status = status; }
}
