package com.dora.incidents.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the {@code ict_asset} table created in V1_4_0__incident_core.sql (LLD-05 §5).
 *
 * <p>ict_asset carries {@code incident_id} as a direct FK column — there is no separate
 * join table. This is the BLOCKER-2 resolution recorded in agent-state/LLD-05/STATE.md:
 * the original LLD mentioned an {@code incident_asset_link} table, but dev-lead dropped it.
 *
 * <p>asset_type is free-form (no DB CHECK constraint) — the taxonomy is extensible.
 * Application validation in IncidentService ensures non-empty before persisting.
 */
@Entity
@Table(name = "ict_asset")
public class IctAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private Incident incident;

    @Column(name = "name", nullable = false, length = 200, updatable = false)
    private String name;

    @Column(name = "asset_type", nullable = false, length = 100, updatable = false)
    private String assetType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IctAsset() {
        // JPA no-arg
    }

    public IctAsset(Incident incident, String name, String assetType) {
        this.incident = incident;
        this.name = name;
        this.assetType = assetType;
    }

    public UUID getId() { return id; }
    public Incident getIncident() { return incident; }
    public String getName() { return name; }
    public String getAssetType() { return assetType; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IctAsset other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "IctAsset{id=" + id + ", name='" + name + "', type='" + assetType + "'}";
    }
}
