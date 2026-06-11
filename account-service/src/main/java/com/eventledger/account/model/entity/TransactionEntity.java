package com.eventledger.account.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity mapped to the 'transactions' table in the Account Service's embedded H2 database.
 *
 * Records every transaction applied to an account. Each row corresponds to one
 * event that was submitted via the Event Gateway and successfully forwarded here.
 *
 * --- Idempotency ---
 * transactionId is the same value as eventId from the originating Gateway event.
 * It is the primary key of this table. Before inserting, the service checks
 * whether this transactionId already exists — if it does, the transaction is a
 * duplicate and the existing row is returned without modifying the account balance.
 * This is the Account Service's own idempotency layer, independent of the Gateway.
 *
 * --- Two timestamps ---
 * eventTimestamp: propagated from the original event. Represents when the
 *                 transaction actually occurred in the upstream system.
 *                 All transaction listings are ordered by this field so the
 *                 history appears in true chronological order even when events
 *                 arrived out of sequence.
 *
 * createdAt:      the time this row was inserted into the Account Service's
 *                 database. Used for auditing only. Never used for ordering.
 *
 * --- No @ManyToOne relationship ---
 * The account relationship is stored as a plain String (accountId) rather than
 * a JPA @ManyToOne association. This avoids lazy-loading complexity and keeps
 * the entity self-contained for query purposes.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    // Same value as the originating eventId from the Gateway.
    // Primary key — prevents duplicate transactions at the database level.
    @Id
    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    // "CREDIT" or "DEBIT"
    @Column(name = "type", nullable = false, length = 6)
    private String type;

    // DECIMAL(15,2) — consistent precision with AccountEntity.balance
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // Original event time, propagated from the Gateway's event payload.
    // This is the sort key for chronological transaction history.
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    // Time this row was inserted. For auditing; not used for ordering or business logic.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // No-arg constructor required by JPA. Not intended for use by application code.
    protected TransactionEntity() {}

    public TransactionEntity(String transactionId,
                              String accountId,
                              String type,
                              BigDecimal amount,
                              String currency,
                              Instant eventTimestamp,
                              Instant createdAt) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.createdAt = createdAt;
    }

    public String getTransactionId()     { return transactionId; }
    public String getAccountId()         { return accountId; }
    public String getType()              { return type; }
    public BigDecimal getAmount()        { return amount; }
    public String getCurrency()          { return currency; }
    public Instant getEventTimestamp()   { return eventTimestamp; }
    public Instant getCreatedAt()        { return createdAt; }
}
