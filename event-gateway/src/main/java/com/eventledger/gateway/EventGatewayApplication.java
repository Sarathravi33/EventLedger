package com.eventledger.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Event Gateway service.
 *
 * Responsibilities of this service:
 * - Receive and validate incoming transaction events from external clients
 * - Enforce idempotency: the same eventId must never produce duplicate records or balance changes
 * - Persist events in its own embedded H2 database (isolated from Account Service)
 * - Forward valid events to the Account Service via HTTP (with circuit breaker protection)
 * - Serve event read queries from local data, independently of Account Service availability
 *
 * Runs on port 8080 (configured in application.yml).
 */
@SpringBootApplication
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
