package com.dora.services.audit;

/**
 * Exhaustive set of auditable actions for DORA compliance (LLD-03 §4).
 *
 * <p>Starter set agreed with dev-lead surrogate ruling (2026-04-25). Downstream LLDs
 * add values; existing values must never be renamed — audit rows store the name() string
 * and renaming would break historical queries.
 *
 * <p>Values are persisted as-is (VARCHAR(80)) in {@code audit_log.action}.
 */
public enum AuditAction {

    /** An incident record was created (LLD-05). */
    INCIDENT_CREATED,

    /** An incident record was updated (status, severity, description, etc.) (LLD-05). */
    INCIDENT_UPDATED,

    /** An incident was closed / resolved (LLD-05). */
    INCIDENT_CLOSED,

    /** A classification was overridden by a privileged role (LLD-07). */
    CLASSIFICATION_OVERRIDDEN,

    /** A NCA/regulatory report was submitted (LLD-10). */
    REPORT_SUBMITTED,

    /** A system-generated deadline alert was dispatched (LLD-09). */
    DEADLINE_ALERT_SENT,

    /** Client notification details were updated on an incident (LLD-05). */
    CLIENT_NOTIFICATION_UPDATED,

    /**
     * Catch-all for non-entity / automated system events.
     * actor_id may be NULL for this action type.
     */
    SYSTEM
}
