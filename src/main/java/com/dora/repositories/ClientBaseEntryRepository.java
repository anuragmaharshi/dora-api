package com.dora.repositories;

import com.dora.entities.ClientBaseEntry;
import com.dora.services.audit.AuditedRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ClientBaseEntry} entities.
 *
 * <p>Append-only by design (D-LLD04-1). Extends {@link AuditedRepository} to ensure the
 * hard-delete guard is active, which prevents accidental history erasure.
 */
@Repository
public interface ClientBaseEntryRepository extends AuditedRepository<ClientBaseEntry, UUID> {

    /** Returns all entries for a tenant, most recent effective date first. */
    List<ClientBaseEntry> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);

    /**
     * Returns the single entry in effect at {@code asOf} for the given tenant.
     *
     * <p>This is the denominator query for LLD-07 C1 threshold: the entry with the
     * latest effective_from that is still ≤ the incident's occurrence time.
     *
     * @param tenantId the tenant
     * @param asOf     the point in time to evaluate
     * @return the effective entry, or empty if no entry exists before {@code asOf}
     */
    Optional<ClientBaseEntry> findTopByTenantIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            UUID tenantId, Instant asOf);
}
