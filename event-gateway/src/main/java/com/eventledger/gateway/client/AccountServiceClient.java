package com.eventledger.gateway.client;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Client for the Account Service's internal transaction API.
 *
 * This class is the single point of contact between the Event Gateway and the
 * Account Service. All resiliency logic (circuit breaker, timeout, retry) will
 * be applied here in Step 10 — nowhere else in the Gateway.
 *
 * --- Step 5: Stub ---
 * This is a no-op implementation that always returns successfully. It exists so
 * that the Gateway's own API (save, retrieve, list events) can be built and tested
 * in complete isolation from the Account Service.
 *
 * --- Step 7: Real implementation ---
 * This class will be rewritten to make a real HTTP POST to the Account Service
 * using RestTemplate. The method signature stays the same; only the body changes.
 *
 * --- Step 10: Circuit breaker ---
 * A Resilience4j @CircuitBreaker annotation will be added to applyTransaction.
 * The fallback method will throw AccountServiceUnavailableException, which
 * GlobalExceptionHandler maps to 503 Service Unavailable.
 */
@Component
public class AccountServiceClient {

    /**
     * Applies a transaction to the given account in the Account Service.
     *
     * @param accountId       the account to credit or debit
     * @param transactionId   the originating eventId — used for idempotency in the Account Service
     * @param type            "CREDIT" or "DEBIT"
     * @param amount          amount to apply (always positive)
     * @param currency        ISO 4217 currency code
     * @param eventTimestamp  original event time, propagated for chronological ordering
     *
     * @throws AccountServiceUnavailableException if the Account Service cannot be reached
     *         (not thrown by this stub; thrown by the real implementation in Step 7)
     */
    public void applyTransaction(String accountId,
                                  String transactionId,
                                  String type,
                                  BigDecimal amount,
                                  String currency,
                                  Instant eventTimestamp) {
        // Stub — no-op. Always returns normally.
        // The real HTTP call to POST /accounts/{accountId}/transactions is wired in Step 7.
    }
}
