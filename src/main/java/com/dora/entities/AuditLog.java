package com.dora.entities;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the {@code audit_log} table (LLD-03 §5).
 *
 * <p>Append-only by design: the DB trigger {@code audit_log_no_mutation} rejects any
 * UPDATE or DELETE at the database level (AC-2). This entity therefore has no
 * {@code @PreUpdate} / {@code @PreRemove} hooks — the trigger is authoritative.
 *
 * <p>JSONB columns ({@code before_state}, {@code after_state}, {@code context}) are mapped
 * via Hibernate 6's native {@code @JdbcTypeCode(SqlTypes.JSON)} on {@link JsonNode} fields.
 * No additional UserType or custom converter is needed when using Hibernate 6.4+ with the
 * PostgreSQL dialect — the driver handles JSONB serialisation via the ObjectMapper configured
 * in {@link com.dora.config.JacksonConfig}.
 *
 * <p>Column name note: LLD-03 §5 names them {@code before} and {@code after}, but those are
 * PostgreSQL reserved words. The migration uses {@code before_state} / {@code after_state}.
 * The {@code @Column(name = "...")} annotations here match the migration exactly.
 *
 * <p>equals/hashCode by surrogate PK ({@code id}) only — consistent with every other entity
 * in this codebase.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    // NULL only for SYSTEM action (actor_id has nullable FK to app_user)
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    // Denormalised: survives user deactivation and provides a human-readable identity
    // even after the app_user row is soft-deleted.
    @Column(name = "actor_username", nullable = false, updatable = false, length = 100)
    private String actorUsername;

    // Stores the AuditAction enum value as its name() string.
    @Column(name = "action", nullable = false, updatable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 50)
    private String entityType;

    // NULL for non-entity events (e.g. DEADLINE_ALERT_SENT, SYSTEM).
    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    // Snapshot of the entity before the change. NULL on creation events.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false, columnDefinition = "jsonb")
    private JsonNode beforeState;

    // Snapshot of the entity after the change. NULL on deletion events.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false, columnDefinition = "jsonb")
    private JsonNode afterState;

    // Request forensics: {request_id, remote_ip, user_agent} only (DECIDE Q-2).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", updatable = false, columnDefinition = "jsonb")
    private JsonNode context;

    // Set by DB DEFAULT now(); populated after insert via GenerationType.UUID/JPA flush.
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // JPA no-arg constructor
    }

    // Public constructor — AuditService is the only intended caller; package layout
    // puts the service and entity in different packages so package-private is insufficient.
    public AuditLog(UUID tenantId,
             UUID actorId,
             String actorUsername,
             String action,
             String entityType,
             UUID entityId,
             JsonNode beforeState,
             JsonNode afterState,
             JsonNode context) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.actorUsername = actorUsername;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.context = context;
        // createdAt is set by DB DEFAULT; leave null until first flush.
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public UUID getActorId() { return actorId; }

    public String getActorUsername() { return actorUsername; }

    public String getAction() { return action; }

    public String getEntityType() { return entityType; }

    public UUID getEntityId() { return entityId; }

    public JsonNode getBeforeState() { return beforeState; }

    public JsonNode getAfterState() { return afterState; }

    public JsonNode getContext() { return context; }

    public Instant getCreatedAt() { return createdAt; }

    // equals/hashCode on surrogate PK only — consistent with AppUser, Tenant, AppRole pattern.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLog other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuditLog{id=" + id + ", action='" + action + "', entityType='" + entityType
                + "', entityId=" + entityId + ", createdAt=" + createdAt + "}";
    }
}
