package com.dora.repositories;

import com.dora.entities.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Read / insert access to {@code audit_log}.
 *
 * <p>This repository deliberately does NOT extend {@link com.dora.services.audit.AuditedRepository}
 * — the audit table itself is exempt from the soft-delete guard. The DB trigger
 * ({@code audit_log_no_mutation}) enforces immutability at the database layer. Adding
 * an {@code AuditedRepository} guard on top would be circular: the audit log cannot be
 * audited via itself.
 *
 * <p>The {@code findByEntityTypeAndEntityIdOrderByCreatedAtDesc} method maps to the
 * {@code idx_audit_log_entity} composite index (entity_type, entity_id, created_at DESC),
 * which was created specifically for this query pattern (LLD-03 §5).
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Returns audit rows for a specific entity, newest first.
     *
     * <p>Maps to index {@code idx_audit_log_entity} — no full-table scan.
     *
     * @param entityType the entity type discriminator (e.g. "INCIDENT", "CLASSIFICATION")
     * @param entityId   the entity's UUID
     * @param pageable   page and sort; caller should pass an unsorted {@link Pageable} since
     *                   the method name already mandates {@code ORDER BY created_at DESC}
     * @return page of audit rows, newest first
     */
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, UUID entityId, Pageable pageable);
}
