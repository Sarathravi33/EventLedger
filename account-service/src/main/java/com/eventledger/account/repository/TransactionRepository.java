package com.eventledger.account.repository;

import com.eventledger.account.model.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for TransactionEntity.
 *
 * Extends JpaRepository<TransactionEntity, String> where String is the type of
 * the primary key (transactionId). Inherited methods used by the service layer:
 *
 *   save(TransactionEntity tx)         — inserts a new transaction row.
 *
 *   existsById(String transactionId)   — idempotency check before applying a
 *                                        transaction. transactionId is the primary
 *                                        key, so this is a fast PK lookup.
 *                                        If true, the transaction was already
 *                                        applied and must not be re-applied.
 *
 * Two custom queries are declared below because they cannot be expressed as
 * simple derived method names (ordering and aggregation respectively).
 */
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    /**
     * Returns all transactions for the given account, sorted chronologically
     * by the original event timestamp (not by database insertion order).
     *
     * Spring Data derives this query from the method name:
     *   SELECT t FROM TransactionEntity t
     *   WHERE t.accountId = :accountId
     *   ORDER BY t.eventTimestamp ASC
     *
     * This ordering is what makes out-of-order delivery correct in listings:
     * a transaction with an older eventTimestamp that arrived late will appear
     * in its true chronological position, not at the end of the list.
     */
    List<TransactionEntity> findByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Sums the transaction amounts for a given account and type (CREDIT or DEBIT).
     * Used by the service layer to compute the account balance:
     *   balance = sumAmountByAccountIdAndType(id, "CREDIT")
     *           − sumAmountByAccountIdAndType(id, "DEBIT")
     *
     * COALESCE(..., 0) ensures the query returns BigDecimal.ZERO instead of null
     * when no transactions of the requested type exist for the account yet.
     *
     * This approach keeps balance computation correct regardless of transaction
     * arrival order — it is a commutative SUM over the full transaction set,
     * not a running total that depends on insertion sequence.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t " +
           "WHERE t.accountId = :accountId AND t.type = :type")
    BigDecimal sumAmountByAccountIdAndType(@Param("accountId") String accountId,
                                           @Param("type") String type);
}
