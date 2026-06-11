package com.eventledger.account.model.dto;

import java.math.BigDecimal;

/**
 * Outbound DTO returned by GET /accounts/{accountId}/balance.
 *
 * Returns the minimum information needed to answer a balance query.
 * For full account details including transaction history, see AccountResponse
 * (returned by GET /accounts/{accountId}).
 *
 * balance = SUM of all CREDIT amounts − SUM of all DEBIT amounts.
 * This is correct regardless of the order in which transactions were applied.
 */
public record BalanceResponse(
        String accountId,
        BigDecimal balance,
        String currency
) {}
