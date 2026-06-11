package com.eventledger.gateway.exception;

/**
 * Thrown when the Account Service cannot be reached or returns an error.
 *
 * Used across three steps:
 *   Step 5  — defined here; the stub AccountServiceClient never throws it.
 *   Step 7  — the real RestTemplate client throws it on non-2xx responses or I/O errors.
 *   Step 10 — the Resilience4j circuit breaker fallback throws it when the circuit is open
 *             or a timeout fires, so the caller always sees this one exception type
 *             regardless of why the Account Service was unreachable.
 *
 * GlobalExceptionHandler maps this to 503 Service Unavailable (added in Step 10).
 * Until then, if somehow thrown, the generic Exception handler returns 500.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message) {
        super(message);
    }

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
