package com.dora.incidents.domain;

import com.dora.services.audit.AuditedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Incident} entities.
 *
 * <p>Extends {@link AuditedRepository} to inherit the hard-delete guard (NFR-005 / LLD-03).
 *
 * <p>EntityGraph on findById ensures attachments, affectedServices, and ictAssets are
 * loaded in the same query as the incident — no LazyInitializationException risk when
 * the service builds the full IncidentResponse outside the JPA context.
 */
@Repository
public interface IncidentRepository extends AuditedRepository<Incident, UUID> {

    /**
     * Finds an incident with the affectedServices set eagerly loaded in one query.
     *
     * <p>Only {@code affectedServices} is loaded via the EntityGraph. Hibernate can
     * safely join a single {@code @ManyToMany Set} without the MultipleBagFetchException
     * that would result from joining two {@code @OneToMany List} collections simultaneously.
     *
     * <p>{@code attachments} and {@code ictAssets} are intentionally excluded: the service
     * layer loads them via separate {@code findByIncidentId*} queries, which is cheaper
     * than a Cartesian-join fetch across three collections.
     */
    @EntityGraph(attributePaths = {"affectedServices"})
    @Query("SELECT i FROM Incident i WHERE i.id = :id")
    Optional<Incident> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Paginated list for GET /api/v1/incidents.
     * Ordered by createdAt DESC (latest first) per idx_incident_created_at.
     * Rich filter (status, tenant, date range) is deferred to LLD-14.
     */
    Page<Incident> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /**
     * Counts incidents created on or after {@code startOfDay} with the given incidentId prefix.
     * Used by IncidentIdGenerator to compute the NNNN sequence number without a DB sequence.
     */
    @Query("SELECT COUNT(i) FROM Incident i WHERE i.detectionDatetime >= :startOfDay")
    long countByDetectionDatetimeOnOrAfter(@Param("startOfDay") Instant startOfDay);

    Optional<Incident> findByIncidentId(String incidentId);
}
