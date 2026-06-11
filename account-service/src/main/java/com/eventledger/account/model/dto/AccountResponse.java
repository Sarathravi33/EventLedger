package com.eventledger.account.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO returned by GET /accounts/{accountId}.
 *
 * Includes the account's current balance alongside a complete transaction
 * history ordered chronologically by eventTimestamp.
 *
 * --- Chronological ordering ---
 * Because events may arrive out of order, the transaction list is sorted by
 * eventTimestamp (the original event time), not by the database insertion order.
 * A transaction with an older eventTimestamp that arrived late will appear in
 * its correct chronological position in the list.
 *
 * --- Nested TransactionDetail ---
 * TransactionDetail is declared as a nested record because it only ever appears
 * as part of an account response — there is no use case for it as a standalone
 * type. Nesting it here keeps related types co-located and avoids an extra file.
 *
 * accountId is omitted from TransactionDetail because it is already present
 * at the parent AccountResponse level.
 */
public record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        Instant lastUpdated,
        List<TransactionDetail> transactions
) {

    /**
     * A single entry in an account's transaction history.
     *
     * eventTimestamp is the original event time, not the insertion time.
     * Sorting by this field produces a chronologically correct history.
     */
    public record TransactionDetail(
            String transactionId,
            String type,             // "CREDIT" or "DEBIT"
            BigDecimal amount,
            String currency,
            Instant eventTimestamp   // chronological position of this transaction
    ) {}
}
