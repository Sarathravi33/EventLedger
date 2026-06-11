package com.eventledger.gateway.client;

import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Client for the Account Service's internal transaction API.
 *
 * This class is the single point of contact between the Event Gateway and the
 * Account Service. All resiliency logic lives here — no circuit breaker concerns
 * anywhere else in the Gateway.
 *
 * --- Wire contract ---
 * POST {baseUrl}/accounts/{accountId}/transactions
 *   Request body:  TransactionRequestBody (JSON) — transactionId, type, amount, currency, eventTimestamp
 *   Success:       2xx response — returns normally
 *   Any failure:   throws AccountServiceUnavailableException (via fallback)
 *
 * --- Circuit breaker (@CircuitBreaker) ---
 * The "accountService" circuit breaker is configured in application.yml under
 * resilience4j.circuitbreaker.instances.accountService.
 *
 * States:
 *   CLOSED     — calls go through normally; failures are recorded.
 *   OPEN       — calls short-circuit immediately (no HTTP call); fallback fires,
 *                throws AccountServiceUnavailableException → 503 to the client.
 *   HALF_OPEN  — a limited number of probe calls are allowed through to test
 *                whether the Account Service has recovered.
 *
 * The fallback method (applyTransactionFallback) is the single place where ALL
 * failure types are converted to AccountServiceUnavailableException:
 *   - ResourceAccessException  — connect/read timeout or connection refused
 *   - HttpStatusCodeException  — non-2xx HTTP response from Account Service
 *   - CallNotPermittedException — circuit is OPEN, call was not attempted
 *
 * --- Timeouts (RestTemplateConfig) ---
 * connectTimeout: 2 s, readTimeout: 3 s — configured on the RestTemplate bean.
 * Together with the circuit breaker: worst-case latency to the caller is one
 * 3-second read timeout, after which the circuit opens and further calls
 * fail instantly without waiting.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AccountServiceClient(RestTemplate restTemplate,
                                 @Value("${account-service.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * Applies a transaction to the given account in the Account Service.
     *
     * The {@code @CircuitBreaker} proxy intercepts any exception thrown by this
     * method and routes it to {@link #applyTransactionFallback}. When the circuit
     * is OPEN the proxy short-circuits without calling this method at all — the
     * fallback is invoked directly with a {@code CallNotPermittedException}.
     *
     * @param accountId       account to credit or debit
     * @param transactionId   eventId from the Gateway — idempotency key in the Account Service
     * @param type            "CREDIT" or "DEBIT"
     * @param amount          amount to apply (always positive)
     * @param currency        ISO 4217 currency code
     * @param eventTimestamp  original event time, propagated for chronological ordering
     *
     * @throws AccountServiceUnavailableException always — routed through the fallback
     */
    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public void applyTransaction(String accountId,
                                  String transactionId,
                                  String type,
                                  BigDecimal amount,
                                  String currency,
                                  Instant eventTimestamp) {
        String url = baseUrl + "/accounts/" + accountId + "/transactions";
        TransactionRequestBody body = new TransactionRequestBody(
                transactionId, type, amount, currency, eventTimestamp);

        log.debug("POST {} transactionId={} type={} amount={} {}",
                url, transactionId, type, amount, currency);

        restTemplate.postForEntity(url, body, Void.class);
        log.debug("Account Service accepted transaction {}", transactionId);
    }

    /**
     * Fallback invoked by the circuit breaker whenever {@link #applyTransaction} fails.
     *
     * Called in two situations:
     *   1. The call was attempted and threw an exception (network error, HTTP error,
     *      timeout). The circuit breaker records the failure; once the failure rate
     *      threshold is exceeded the circuit opens.
     *   2. The circuit is OPEN and the call was not attempted.
     *      {@code t} is {@code CallNotPermittedException} in this case.
     *
     * The method signature must exactly match {@link #applyTransaction} plus a final
     * {@code Throwable} parameter — Resilience4j matches the fallback by parameter types.
     *
     * Always throws {@link AccountServiceUnavailableException} so that
     * {@code EventService} always sees a single, well-known exception type regardless
     * of the failure mode. {@code GlobalExceptionHandler} maps this to 503.
     */
    private void applyTransactionFallback(String accountId,
                                           String transactionId,
                                           String type,
                                           BigDecimal amount,
                                           String currency,
                                           Instant eventTimestamp,
                                           Throwable t) {
        log.warn("Account Service call failed for transaction {} on account {} [{}]: {}",
                transactionId, accountId, t.getClass().getSimpleName(), t.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service unavailable: " + t.getMessage(), t);
    }

    /**
     * JSON body sent to POST /accounts/{accountId}/transactions.
     *
     * Field names match the Account Service's TransactionRequest exactly so Jackson
     * serialises to the correct JSON shape. Private — implementation detail only.
     */
    private record TransactionRequestBody(
            String transactionId,
            String type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp
    ) {}
}
