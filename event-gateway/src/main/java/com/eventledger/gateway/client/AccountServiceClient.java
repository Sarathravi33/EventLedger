package com.eventledger.gateway.client;

import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Client for the Account Service's internal transaction API.
 *
 * This class is the single point of contact between the Event Gateway and the
 * Account Service. All resiliency logic (circuit breaker, timeout) is added here
 * in Step 10 — nowhere else in the Gateway.
 *
 * --- Wire contract ---
 * POST {baseUrl}/accounts/{accountId}/transactions
 *   Request body:  TransactionRequestBody (JSON) — transactionId, type, amount, currency, eventTimestamp
 *   Success:       2xx response — transaction applied; response body not used by the Gateway
 *   Failure:       any non-2xx or network error → throws AccountServiceUnavailableException
 *
 * --- Why the Gateway defines its own request body record ---
 * The two services share no code. The Account Service's TransactionRequest lives
 * in its own module and is not on the Gateway's classpath. A private record here
 * mirrors the same JSON shape without creating a shared dependency.
 *
 * --- Why RestTemplateBuilder is used (RestTemplateConfig) ---
 * A plain {@code new RestTemplate()} creates its own default ObjectMapper and
 * ignores application.yml Jackson settings. The builder-produced RestTemplate
 * inherits write-dates-as-timestamps=false so Instant fields round-trip correctly.
 *
 * --- Step 10: Circuit breaker ---
 * A Resilience4j @CircuitBreaker annotation will wrap applyTransaction.
 * The fallback method will throw AccountServiceUnavailableException so that
 * EventService always sees a single, well-known exception type regardless of
 * whether the failure was a network error, HTTP error, or open circuit.
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
     * Builds the request URL from the configured base URL and the accountId path
     * variable, then POSTs the transaction body. Returns normally on any 2xx response.
     *
     * @param accountId       account to credit or debit
     * @param transactionId   eventId from the Gateway — idempotency key in the Account Service
     * @param type            "CREDIT" or "DEBIT"
     * @param amount          amount to apply (always positive)
     * @param currency        ISO 4217 currency code
     * @param eventTimestamp  original event time, propagated for chronological ordering
     *
     * @throws AccountServiceUnavailableException if the Account Service cannot be reached
     *         or returns a non-2xx response
     */
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

        try {
            restTemplate.postForEntity(url, body, Void.class);
            log.debug("Account Service accepted transaction {}", transactionId);

        } catch (ResourceAccessException e) {
            // Network-level failure: connection refused, host unreachable, read timeout.
            // Thrown before any HTTP response is received.
            log.warn("Account Service unreachable at {}: {}", baseUrl, e.getMessage());
            throw new AccountServiceUnavailableException(
                    "Account Service unreachable: " + e.getMessage(), e);

        } catch (HttpStatusCodeException e) {
            // Account Service responded with a non-2xx status (4xx or 5xx).
            // Any non-success response is treated as unavailable from the Gateway's
            // perspective — the caller (EventService) should mark the event FAILED.
            log.warn("Account Service returned {} for transaction {}: {}",
                    e.getStatusCode(), transactionId, e.getResponseBodyAsString());
            throw new AccountServiceUnavailableException(
                    "Account Service returned " + e.getStatusCode()
                    + " for transaction " + transactionId, e);
        }
    }

    /**
     * JSON body sent to POST /accounts/{accountId}/transactions.
     *
     * Field names are kept identical to the Account Service's TransactionRequest record
     * so Jackson serialises to the exact JSON shape the Account Service expects.
     * This record is private — it is an implementation detail of the HTTP call and
     * has no meaning outside this class.
     */
    private record TransactionRequestBody(
            String transactionId,
            String type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp
    ) {}
}
