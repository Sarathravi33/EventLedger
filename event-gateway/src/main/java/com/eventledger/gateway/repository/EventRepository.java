package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * Spring Data JPA repository for EventEntity.
 *
 * Extends JpaRepository<EventEntity, String> where String is the type of
 * the primary key (eventId). This provides the following inherited methods
 * used by the service layer without any declaration here:
 *
 *   findById(String eventId)   — retrieves an event by its primary key.
 *                                Returns Optional<EventEntity>: empty if not found.
 *                                Used to detect duplicate submissions (idempotency)
 *                                and to serve GET /events/{id} responses.
 *
 *   save(EventEntity entity)   — inserts or updates a row. On a duplicate eventId
 *                                (primary key violation), the underlying INSERT
 *                                throws DataIntegrityViolationException, which
 *                                the service layer catches to handle duplicates.
 *
 *   existsById(String eventId) — lightweight existence check (SELECT COUNT(*)).
 *
 * The only custom query needed is the account-scoped listing, which requires
 * a specific ordering that cannot be expressed by an inherited method.
 */
public interface EventRepository extends JpaRepository<EventEntity, String> {

    /**
     * Returns all events for the given account, sorted chronologically by the
     * original event timestamp (not by database insertion order).
     *
     * Spring Data derives this query from the method name:
     *   SELECT e FROM EventEntity e
     *   WHERE e.accountId = :accountId
     *   ORDER BY e.eventTimestamp ASC
     *
     * Ordering by eventTimestamp (not receivedAt) is what makes out-of-order
     * delivery transparent to clients: an event that arrived late but carries
     * an earlier timestamp will appear in its correct chronological position.
     */
    List<EventEntity> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
