package com.dora.incidents.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Maps the {@code incident} table created in V1_4_0__incident_core.sql (LLD-05 §5).
 *
 * <p>detection_datetime is marked {@code updatable = false} at the JPA layer.
 * The DB-level trigger {@code incident_detection_immutable} provides a second line
 * of defence (FR-002). Both guards are intentional and independent: the JPA guard
 * catches accidental updates in tests / services before they reach the DB.
 *
 * <p>Hard-delete is blocked by:
 * <ol>
 *   <li>IncidentRepository extending AuditedRepository (UnsupportedOperationException)</li>
 *   <li>REVOKE DELETE ON incident FROM dora (in migration)</li>
 * </ol>
 *
 * <p>Collections are LAZY — the service layer loads them explicitly when building the
 * full IncidentResponse to avoid N+1 queries and LazyInitializationException outside
 * the transaction.
 *
 * <p>AffectedServices are loaded via a join table (affected_service_link); rather than
 * mapping the join entity explicitly, we use @ManyToMany with the join table annotation
 * because the join table has only metadata columns (no additional FKs or payload columns).
 */
@Entity
@Table(name = "incident")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "incident_id", nullable = false, unique = true, length = 20, updatable = false)
    private String incidentId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "impact_estimate", columnDefinition = "TEXT")
    private String impactEstimate;

    // updatable=false: JPA-level guard for FR-002. DB trigger is the second guard.
    @Column(name = "detection_datetime", nullable = false, updatable = false)
    private Instant detectionDatetime;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "status", nullable = false, length = 30)
    private String status = "DETECTED";

    // LAZY: only loaded when IncidentService explicitly fetches for the full response
    @OneToMany(mappedBy = "incident", fetch = FetchType.LAZY)
    private List<Attachment> attachments = new ArrayList<>();

    // LAZY: join table affected_service_link → critical_service
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "affected_service_link",
            joinColumns = @JoinColumn(name = "incident_id"),
            inverseJoinColumns = @JoinColumn(name = "critical_service_id")
    )
    private Set<com.dora.entities.CriticalService> affectedServices = new HashSet<>();

    // LAZY: ict_asset rows carry incident_id FK directly (BLOCKER-2 resolution)
    @OneToMany(mappedBy = "incident", fetch = FetchType.LAZY)
    private List<IctAsset> ictAssets = new ArrayList<>();

    protected Incident() {
        // JPA no-arg
    }

    public Incident(UUID tenantId,
                    String incidentId,
                    String title,
                    String description,
                    String impactEstimate,
                    UUID createdBy) {
        this.tenantId = tenantId;
        this.incidentId = incidentId;
        this.title = title;
        this.description = description;
        this.impactEstimate = impactEstimate;
        this.createdBy = createdBy;
        // detection_datetime is set by the service immediately after construction via setDetectionDatetime
    }

    /**
     * Sets the authoritative detection timestamp. May only be called once during creation;
     * the column is updatable=false so Hibernate will not include it in UPDATE statements.
     */
    public void setDetectionDatetime(Instant detectionDatetime) {
        this.detectionDatetime = detectionDatetime;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getIncidentId() { return incidentId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getImpactEstimate() { return impactEstimate; }
    public Instant getDetectionDatetime() { return detectionDatetime; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public List<Attachment> getAttachments() { return attachments; }
    public Set<com.dora.entities.CriticalService> getAffectedServices() { return affectedServices; }
    public List<IctAsset> getIctAssets() { return ictAssets; }

    // Mutable fields — updated via PUT /incidents/{id} (INCIDENT_MANAGER only).
    // detection_datetime, tenant_id, incident_id, created_by are immutable per FR-002.
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setImpactEstimate(String impactEstimate) { this.impactEstimate = impactEstimate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Incident other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Incident{id=" + id + ", incidentId='" + incidentId + "', status='" + status + "'}";
    }
}
