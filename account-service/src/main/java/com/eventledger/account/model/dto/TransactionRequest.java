package com.eventledger.account.model.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbound DTO for POST /accounts/{accountId}/transactions.
 *
 * This is the internal HTTP contract between the Event Gateway and the Account
 * Service. External clients never call this endpoint directly.
 *
 * --- transactionId ---
 * Carries the same value as eventId from the originating Gateway event.
 * The Account Service stores this as the transaction's primary key and uses it
 * for idempotency: if a row with this transactionId already exists, the request
 * is a duplicate and the existing result is returned without re-applying.
 *
 * --- eventTimestamp ---
 * Propagated from the original event payload so the Account Service can store
 * the true event time (not the time of this HTTP call) against each transaction.
 * This is what makes chronological ordering of transaction history correct even
 * when events arrive out of sequence.
 */
public record TransactionRequest(
        String transactionId,    // = eventId from the originating Gateway event
        String type,             // "CREDIT" or "DEBIT"
        BigDecimal amount,
        String currency,
        Instant eventTimestamp   // original event time, not the time of this HTTP call
) {}
