package com.eventledger.account.repository;

import com.eventledger.account.model.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for AccountEntity.
 *
 * Extends JpaRepository<AccountEntity, String> where String is the type of
 * the primary key (accountId). All operations needed by the service layer
 * are covered by inherited methods — no custom queries are required:
 *
 *   findById(String accountId)     — retrieve an account, or empty if it does
 *                                    not exist yet (first transaction creates it).
 *
 *   save(AccountEntity account)    — insert a new account or update an existing
 *                                    one (balance, lastUpdated) after a transaction.
 *
 *   existsById(String accountId)   — lightweight check before querying the full entity.
 *
 * No ordering or aggregation queries live here — those belong to TransactionRepository
 * because balance and transaction history are derived from the transactions table.
 */
public interface AccountRepository extends JpaRepository<AccountEntity, String> {
    // All required methods inherited from JpaRepository.
}
