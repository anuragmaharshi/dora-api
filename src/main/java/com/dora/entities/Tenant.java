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
 * Maps the {@code tenant} table created in V1_1_0__auth_tables.sql.
 * Extended in V1_3_0__tenant_config.sql (LLD-04) with NCA and jurisdiction fields.
 * Soft-delete only — tenants are never hard-deleted (LLD-02 §5 retention rules).
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "legal_name", nullable = false, unique = true, length = 255)
    private String legalName;

    @Column(name = "lei", unique = true, length = 20)
    private String lei;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // LLD-04 §5 extension columns — all nullable (not all tenants are fully configured on day 1)

    @Column(name = "nca_name", length = 255)
    private String ncaName;

    @Column(name = "nca_email", length = 255)
    private String ncaEmail;

    /** ISO 3166-1 alpha-2 country code, e.g. "FR", "DE", "IE". */
    @Column(name = "jurisdiction_iso", length = 2)
    private String jurisdictionIso;

    /** FK → app_user(id) — the user designated as primary compliance contact. */
    @Column(name = "primary_compliance_contact_id")
    private UUID primaryComplianceContactId;

    protected Tenant() {
        // JPA requires a no-arg constructor; package-private to discourage direct use.
    }

    public Tenant(String legalName, String lei) {
        this.legalName = legalName;
        this.lei = lei;
    }

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getLei() {
        return lei;
    }

    public void setLei(String lei) {
        this.lei = lei;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getNcaName() {
        return ncaName;
    }

    public void setNcaName(String ncaName) {
        this.ncaName = ncaName;
    }

    public String getNcaEmail() {
        return ncaEmail;
    }

    public void setNcaEmail(String ncaEmail) {
        this.ncaEmail = ncaEmail;
    }

    public String getJurisdictionIso() {
        return jurisdictionIso;
    }

    public void setJurisdictionIso(String jurisdictionIso) {
        this.jurisdictionIso = jurisdictionIso;
    }

    public UUID getPrimaryComplianceContactId() {
        return primaryComplianceContactId;
    }

    public void setPrimaryComplianceContactId(UUID primaryComplianceContactId) {
        this.primaryComplianceContactId = primaryComplianceContactId;
    }

    // equals/hashCode on surrogate PK only — standard JPA practice
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tenant other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Tenant{id=" + id + ", legalName='" + legalName + "'}";
    }
}
