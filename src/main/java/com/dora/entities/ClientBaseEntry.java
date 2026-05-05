package com.dora.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the {@code client_base_entry} table (V1_3_0__tenant_config.sql, LLD-04 §5).
 *
 * <p>This table is append-only by design (D-LLD04-1): each new count is inserted with an
 * effective-from date, preserving the history. The current count for any classification
 * run is derived by selecting the row with the latest effective_from ≤ the incident date
 * (see {@link com.dora.services.ClientBaseService#countAsOf(UUID, Instant)}).
 *
 * <p>Hard deletes are prohibited — AuditedRepository enforces this at the application layer,
 * and the DB-level CHECK (client_count >= 0) ensures data integrity.
 */
@Entity
@Table(name = "client_base_entry")
public class ClientBaseEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "client_count", nullable = false)
    private long clientCount;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** FK → app_user(id) — the admin who set this count value. */
    @Column(name = "set_by", nullable = false, updatable = false)
    private UUID setBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ClientBaseEntry() {
        // JPA no-arg
    }

    public ClientBaseEntry(UUID tenantId, long clientCount, Instant effectiveFrom, UUID setBy) {
        this.tenantId = tenantId;
        this.clientCount = clientCount;
        this.effectiveFrom = effectiveFrom;
        this.setBy = setBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public long getClientCount() {
        return clientCount;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public UUID getSetBy() {
        return setBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClientBaseEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ClientBaseEntry{id=" + id + ", clientCount=" + clientCount
                + ", effectiveFrom=" + effectiveFrom + "}";
    }
}
