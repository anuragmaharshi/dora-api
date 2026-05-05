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

    // ── LLD-04: Tenant Configuration admin actions ──────────────────────────

    /** Tenant-level fields (legalName, LEI, NCA info, jurisdiction) were updated. */
    TENANT_CONFIG_UPDATED,

    /** A new critical service was created. */
    CRITICAL_SERVICE_CREATED,

    /** A critical service name or description was updated. */
    CRITICAL_SERVICE_UPDATED,

    /** A critical service was archived (active → false). */
    CRITICAL_SERVICE_ARCHIVED,

    /** A new client base count entry was appended. */
    CLIENT_BASE_ENTRY_ADDED,

    /** NCA email configuration (sender, recipient, subject template) was updated or created. */
    NCA_EMAIL_CONFIG_UPDATED,

    /**
     * A PLATFORM_ADMIN request was blocked by PlatformAdminFirewallFilter because the
     * path is not in the allowed whitelist (BR-011, NFR-009, LLD-04 AC-6).
     */
    PLATFORM_ADMIN_BLOCKED_PATH,

    /**
     * Catch-all for non-entity / automated system events.
     * actor_id may be NULL for this action type.
     */
    SYSTEM
}
