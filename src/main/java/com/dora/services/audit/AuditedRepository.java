package com.dora.services.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository interface for all audited entities (LLD-03 §4, AC-6, NFR-005).
 *
 * <p>{@code @NoRepositoryBean} tells Spring Data JPA not to attempt to create a concrete
 * proxy for this interface. Without it, the component scan treats {@code AuditedRepository}
 * itself as a repository definition (with raw {@code Object} type parameters), and context
 * startup fails with "Not a managed type: class java.lang.Object".
 *
 * <p>All repositories for entities marked {@link AuditedEntity} must extend this interface
 * instead of {@link JpaRepository} directly. Overriding the four delete methods as default
 * methods that throw {@link UnsupportedOperationException} provides application-layer
 * defence-in-depth: even if a service accidentally calls a delete method, the call is
 * rejected before reaching the database.
 *
 * <p>The primary enforcement is at the database layer: the {@code audit_log_no_mutation}
 * trigger rejects UPDATE / DELETE on {@code audit_log}, and role grants prevent the
 * application role from issuing DELETE on audited tables (AC-2 / AC-7). This interface
 * is the second line of defence (belt and braces).
 *
 * <p>Archive / deactivation logic (soft-delete via an {@code active} or {@code archived}
 * flag) replaces hard delete. Each entity that needs archival exposes an explicit
 * {@code archive()} method on its service — there is no generic archive shortcut here
 * because the business rules vary by entity type.
 *
 * @param <T>  the entity type
 * @param <ID> the primary key type
 */
public interface AuditedRepository<T, ID> extends JpaRepository<T, ID> {

    // Hard-delete methods — all prohibited. The message is intentionally stable:
    // it appears in exception logs and could be asserted in integration tests.
    // "use archive" is the canonical instruction per NFR-005.

    @Override
    default void delete(T entity) {
        throw new UnsupportedOperationException(
                "Hard delete is prohibited — use archive (NFR-005).");
    }

    @Override
    default void deleteById(ID id) {
        throw new UnsupportedOperationException(
                "Hard delete is prohibited — use archive (NFR-005).");
    }

    @Override
    default void deleteAll(Iterable<? extends T> entities) {
        throw new UnsupportedOperationException(
                "Hard delete is prohibited — use archive (NFR-005).");
    }

    @Override
    default void deleteAllInBatch() {
        throw new UnsupportedOperationException(
                "Hard delete is prohibited — use archive (NFR-005).");
    }
}
