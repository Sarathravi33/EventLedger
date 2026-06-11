package com.eventledger.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Account Service.
 *
 * Responsibilities of this service:
 * - Manage account state: balances and transaction history
 * - Apply incoming transactions (CREDIT / DEBIT) sent by the Event Gateway
 * - Compute balance as SUM(CREDITs) - SUM(DEBITs), which is correct regardless of arrival order
 * - Enforce its own idempotency check on transactionId as a second line of defence
 * - Persist all data in its own embedded H2 database (isolated from Event Gateway)
 *
 * This service is internal — it is only called by the Event Gateway, never by external clients.
 * Runs on port 8081 (configured in application.yml).
 */
@SpringBootApplication
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
