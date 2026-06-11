package com.eventledger.account.model.dto;

import java.math.BigDecimal;

/**
 * Outbound DTO returned by POST /accounts/{accountId}/transactions.
 *
 * Confirms the transaction was applied (or already existed — idempotent case)
 * and reports the account's current balance after the operation.
 *
 * The Event Gateway uses newBalance for informational purposes only.
 * The authoritative balance for external clients is always retrieved via
 * GET /accounts/{accountId}/balance.
 */
public record TransactionResponse(
        String transactionId,
        String accountId,
        BigDecimal newBalance,
        String currency
) {}
