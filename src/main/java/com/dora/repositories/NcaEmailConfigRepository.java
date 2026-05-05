package com.dora.repositories;

import com.dora.entities.NcaEmailConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for {@link NcaEmailConfig} entities.
 *
 * <p>Does NOT extend {@link com.dora.services.audit.AuditedRepository} — this table is
 * fully mutable by design (configuration changes are expected). Hard-delete prevention is
 * handled by the service layer, which never calls delete() and instead uses upsert semantics.
 * All mutations are recorded in {@code audit_log} by {@link com.dora.services.NcaEmailService}.
 */
@Repository
public interface NcaEmailConfigRepository extends JpaRepository<NcaEmailConfig, UUID> {
}
