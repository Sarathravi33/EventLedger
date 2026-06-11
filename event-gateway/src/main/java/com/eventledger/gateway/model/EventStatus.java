package com.eventledger.gateway.model;

/**
 * Lifecycle state of an event within the Event Gateway.
 *
 * Transitions:
 *   PENDING   → PROCESSED : Account Service accepted the transaction.
 *   PENDING   → FAILED    : Account Service was unreachable (circuit breaker open,
 *                           timeout) or returned a non-2xx response.
 *
 * Events in FAILED state are stored in the Gateway's database but their
 * transaction was never applied to the account balance.
 */
public enum EventStatus {
    PENDING,
    PROCESSED,
    FAILED
}
