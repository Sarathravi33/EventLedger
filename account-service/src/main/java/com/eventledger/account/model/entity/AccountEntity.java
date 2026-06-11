package com.eventledger.account.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity mapped to the 'accounts' table in the Account Service's embedded H2 database.
 *
 * Tracks the current state of a single account. Accounts are created automatically
 * on the first transaction — there is no separate account-creation endpoint.
 *
 * --- Balance correctness under out-of-order delivery ---
 * balance is a running total updated on every transaction:
 *   CREDIT → balance = balance + amount
 *   DEBIT  → balance = balance - amount
 *
 * Because addition and subtraction are commutative (the result is the same
 * regardless of the order operations are applied), the balance is always correct
 * even when events arrive out of chronological sequence. There is no need to
 * recompute the balance from scratch when a late-arriving event is processed.
 *
 * lastUpdated is refreshed on every transaction. It reflects the time of the
 * most recent update, not the most recent event timestamp.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private String accountId;

    // Net balance: running total of all CREDITs minus all DEBITs.
    // Correct regardless of transaction arrival order.
    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    // ISO 4217 currency code, e.g. "USD". Set from the first transaction.
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // Server time of the most recent transaction applied to this account.
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // No-arg constructor required by JPA. Not intended for use by application code.
    protected AccountEntity() {}

    public AccountEntity(String accountId, BigDecimal balance, String currency, Instant lastUpdated) {
        this.accountId = accountId;
        this.balance = balance;
        this.currency = currency;
        this.lastUpdated = lastUpdated;
    }

    public String getAccountId()       { return accountId; }
    public BigDecimal getBalance()     { return balance; }
    public String getCurrency()        { return currency; }
    public Instant getLastUpdated()    { return lastUpdated; }

    // balance and lastUpdated are updated together on every transaction.
    public void setBalance(BigDecimal balance)       { this.balance = balance; }
    public void setLastUpdated(Instant lastUpdated)  { this.lastUpdated = lastUpdated; }
}
