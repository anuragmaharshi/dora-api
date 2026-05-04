package com.dora.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the {@code nca_email_config} table (V1_3_0__tenant_config.sql, LLD-04 §5).
 *
 * <p>One row per tenant (tenant_id is the PK). This table is fully mutable — the SMTP sender,
 * NCA recipient, and subject template may change at any time. It does NOT extend
 * AuditedRepository because the data itself is not immutable audit history;
 * changes are instead recorded in {@code audit_log} via {@link com.dora.services.NcaEmailService}.
 *
 * <p>This entity has no auto-generated ID: the tenant_id IS the primary key.
 * The application layer must handle "upsert" semantics (save a new row or update
 * the existing one) via JPA's merge/save behaviour.
 */
@Entity
@Table(name = "nca_email_config")
public class NcaEmailConfig {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "sender", nullable = false, length = 255)
    private String sender;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "subject_template", nullable = false, length = 500)
    private String subjectTemplate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NcaEmailConfig() {
        // JPA no-arg
    }

    public NcaEmailConfig(UUID tenantId, String sender, String recipient, String subjectTemplate) {
        this.tenantId = tenantId;
        this.sender = sender;
        this.recipient = recipient;
        this.subjectTemplate = subjectTemplate;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubjectTemplate() {
        return subjectTemplate;
    }

    public void setSubjectTemplate(String subjectTemplate) {
        this.subjectTemplate = subjectTemplate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NcaEmailConfig other)) return false;
        return tenantId != null && tenantId.equals(other.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(tenantId);
    }

    @Override
    public String toString() {
        return "NcaEmailConfig{tenantId=" + tenantId + ", sender='" + sender + "'}";
    }
}
