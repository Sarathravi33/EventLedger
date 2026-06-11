package com.eventledger.account.service;

import com.eventledger.account.model.dto.AccountResponse;
import com.eventledger.account.model.dto.BalanceResponse;
import com.eventledger.account.model.dto.TransactionRequest;
import com.eventledger.account.model.dto.TransactionResponse;
import com.eventledger.account.model.entity.AccountEntity;
import com.eventledger.account.model.entity.TransactionEntity;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Business logic for the Account Service: account auto-creation, transaction
 * application, and balance/account-detail queries.
 *
 * --- Account auto-creation ---
 * There is no explicit "create account" endpoint. An account row is created
 * automatically the first time a transaction arrives for a new accountId.
 * If two requests for the same new accountId arrive simultaneously, the second
 * save will either merge into the first (within the same transaction) or fail
 * with a primary key conflict — an acceptable edge case for this exercise.
 *
 * --- Balance correctness under out-of-order delivery ---
 * Balance is maintained as a running total on AccountEntity:
 *   CREDIT → balance += amount
 *   DEBIT  → balance -= amount
 *
 * Addition and subtraction are commutative: the final balance is the same
 * regardless of the order in which events arrive. A late-arriving DEBIT does
 * not require recomputing the history — it simply subtracts from the current
 * running total.
 *
 * --- Idempotency ---
 * Not yet implemented in this step. A duplicate transactionId will cause a
 * primary key conflict on the transactions table. Full idempotency (graceful
 * duplicate detection via existsById) is added in Step 8.
 *
 * --- Transaction strategy ---
 * applyTransaction is @Transactional so that the transaction row insert and
 * the balance update are atomic: if either fails the other is rolled back and
 * no partial state is left in the database.
 *
 * Read methods use @Transactional(readOnly = true) to signal to Hibernate that
 * no dirty-checking or flushing is needed, which reduces overhead.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Applies a transaction to the given account and returns the new balance.
     *
     * Flow:
     *   1. Load existing account OR create a new one with balance = 0.
     *   2. Insert a new TransactionEntity row (idempotency key = transactionId).
     *   3. Compute new balance: CREDIT adds, DEBIT subtracts.
     *   4. Persist updated balance and lastUpdated on the account row.
     *   5. Return TransactionResponse with the new balance.
     *
     * The entire method runs inside one database transaction (@Transactional) so
     * the transaction insert and the balance update are committed together or
     * rolled back together.
     *
     * @param accountId the account to credit or debit
     * @param request   the transaction details forwarded from the Event Gateway
     * @return          the transactionId, accountId, new balance, and currency
     */
    @Transactional
    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {
        Instant now = Instant.now();

        // Load the existing account, or create a new one on the fly.
        // orElseGet returns a transient entity; accountRepository.save() at the
        // end will INSERT it (JPA merge on a non-existent PK) or UPDATE it.
        AccountEntity account = accountRepository.findById(accountId)
                .orElseGet(() -> {
                    log.debug("Auto-creating account {} on first transaction", accountId);
                    return new AccountEntity(accountId, BigDecimal.ZERO, request.currency(), now);
                });

        // Insert the transaction row. transactionId (= eventId from Gateway) is the
        // primary key. A duplicate transactionId causes a DB constraint violation —
        // Step 8 adds an existsById check before this point to handle duplicates gracefully.
        TransactionEntity transaction = new TransactionEntity(
                request.transactionId(),
                accountId,
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                now
        );
        transactionRepository.save(transaction);
        log.debug("Inserted transaction {} ({} {} {}) for account {}",
                request.transactionId(), request.type(),
                request.amount(), request.currency(), accountId);

        // Apply the delta. CREDIT and DEBIT are commutative — the result is the
        // same regardless of the order in which events arrived. An unknown type
        // throws IllegalArgumentException → GlobalExceptionHandler → 400.
        BigDecimal newBalance = switch (request.type()) {
            case "CREDIT" -> account.getBalance().add(request.amount());
            case "DEBIT"  -> account.getBalance().subtract(request.amount());
            default -> throw new IllegalArgumentException(
                    "Unknown transaction type: " + request.type());
        };

        account.setBalance(newBalance);
        account.setLastUpdated(now);
        accountRepository.save(account);
        log.debug("Account {} balance updated to {} {}", accountId, newBalance, request.currency());

        return new TransactionResponse(request.transactionId(), accountId, newBalance, request.currency());
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns the current balance for the given account.
     *
     * Reads the running total from the AccountEntity row — an O(1) lookup that
     * is always correct because applyTransaction keeps it in sync atomically.
     *
     * Throws NoSuchElementException if the account does not exist (no transactions
     * have ever been applied to it). GlobalExceptionHandler maps this to 404.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountId));

        return new BalanceResponse(
                account.getAccountId(),
                account.getBalance(),
                account.getCurrency()
        );
    }

    /**
     * Returns full account details including chronological transaction history.
     *
     * Transactions are ordered by eventTimestamp ascending (the original event
     * time, not the database insertion time). A late-arriving transaction with
     * an older timestamp appears in its correct chronological position in the list.
     *
     * Throws NoSuchElementException if the account does not exist.
     * GlobalExceptionHandler maps this to 404.
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountId));

        List<TransactionEntity> transactions =
                transactionRepository.findByAccountIdOrderByEventTimestampAsc(accountId);

        // Map each entity to the nested TransactionDetail record.
        // accountId is omitted from TransactionDetail because it is already
        // present at the AccountResponse level.
        List<AccountResponse.TransactionDetail> details = transactions.stream()
                .map(t -> new AccountResponse.TransactionDetail(
                        t.getTransactionId(),
                        t.getType(),
                        t.getAmount(),
                        t.getCurrency(),
                        t.getEventTimestamp()
                ))
                .toList();

        return new AccountResponse(
                account.getAccountId(),
                account.getBalance(),
                account.getCurrency(),
                account.getLastUpdated(),
                details
        );
    }
}
