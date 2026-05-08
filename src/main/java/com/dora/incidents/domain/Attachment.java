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
 * Maps the {@code attachment} table created in V1_4_0__incident_core.sql (LLD-05 §5).
 *
 * <p>Status lifecycle: PENDING → READY (on /complete) or PENDING → FAILED (on storage error).
 * Status values must match the CHECK constraint in the migration (PENDING, READY, FAILED).
 *
 * <p>The s3Key is assigned by the service before persisting. Format:
 * {@code incidents/<incidentId>/<attachmentId>/<filename>}.
 *
 * <p>Hard-delete is blocked via REVOKE DELETE ON attachment FROM dora (migration).
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // LAZY: Attachment → Incident back-reference; we rarely need the full incident from attachment side
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private Incident incident;

    @Column(name = "filename", nullable = false, length = 500, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 200, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "s3_key", nullable = false, length = 500, updatable = false)
    private String s3Key;

    // Mutable — transitions PENDING→READY or PENDING→FAILED
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Attachment() {
        // JPA no-arg
    }

    public Attachment(Incident incident,
                      String filename,
                      String contentType,
                      long sizeBytes,
                      String s3Key,
                      UUID uploadedBy) {
        this.incident = incident;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.s3Key = s3Key;
        this.uploadedBy = uploadedBy;
    }

    public void markReady() {
        this.status = "READY";
    }

    public void markFailed() {
        this.status = "FAILED";
    }

    public UUID getId() { return id; }
    public Incident getIncident() { return incident; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getS3Key() { return s3Key; }
    public String getStatus() { return status; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Attachment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Attachment{id=" + id + ", filename='" + filename + "', status='" + status + "'}";
    }
}
