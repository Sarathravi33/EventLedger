package com.eventledger.account.controller;

import com.eventledger.account.model.dto.AccountResponse;
import com.eventledger.account.model.dto.BalanceResponse;
import com.eventledger.account.model.dto.TransactionRequest;
import com.eventledger.account.model.dto.TransactionResponse;
import com.eventledger.account.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Account Service's account and transaction endpoints.
 *
 * Responsibilities of this class:
 *   - Bind HTTP request → method parameter (Spring MVC)
 *   - Delegate all business logic to AccountService
 *   - Map the service result → ResponseEntity with the correct HTTP status
 *
 * This controller contains no business logic, no try/catch blocks, and no
 * direct repository access. All error conditions are handled by GlobalExceptionHandler.
 *
 * --- Caller ---
 * This is an internal API. The only caller in normal operation is the Event
 * Gateway's AccountServiceClient. External clients should not call this directly.
 * No request-body Bean Validation (@Valid) is applied here because the Gateway
 * has already validated the event before forwarding it.
 *
 * --- Response codes ---
 *   POST /accounts/{id}/transactions
 *       201 Created          — transaction applied, new balance returned
 *       200 OK               — duplicate transactionId (added in Step 8)
 *       400 Bad Request      — unknown transaction type (defensive)
 *   GET  /accounts/{id}/balance
 *       200 OK               — balance returned
 *       404 Not Found        — account does not exist
 *   GET  /accounts/{id}
 *       200 OK               — full account details + transaction history
 *       404 Not Found        — account does not exist
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Applies a transaction to the given account.
     *
     * The account is auto-created if it does not yet exist — there is no
     * separate account-creation endpoint.
     *
     * Returns 201 Created with the new balance after the transaction is applied.
     * Returns 200 OK for a duplicate transactionId once Step 8 is implemented.
     */
    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @RequestBody TransactionRequest request) {
        TransactionResponse response = accountService.applyTransaction(accountId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Returns the current balance for the given account.
     *
     * The balance is maintained as a running total updated on every transaction,
     * so this is an O(1) read — no aggregation query is needed at read time.
     *
     * Returns 404 if the accountId has never had a transaction applied to it.
     */
    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        BalanceResponse response = accountService.getBalance(accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns full account details including complete transaction history.
     *
     * Transactions in the response are ordered by eventTimestamp ascending
     * (the original event time). A late-arriving transaction appears in its
     * correct chronological position — out-of-order delivery is transparent.
     *
     * Returns 404 if the accountId has never had a transaction applied to it.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountId) {
        AccountResponse response = accountService.getAccount(accountId);
        return ResponseEntity.ok(response);
    }
}
