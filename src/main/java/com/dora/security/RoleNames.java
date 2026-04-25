package com.dora.security;

/**
 * Authoritative role code constants for {@code @PreAuthorize} expressions.
 *
 * Every subsequent LLD's {@code @PreAuthorize} must reference these constants —
 * never inline literal strings.  String constants map 1:1 to {@code app_role.code}
 * in the DB and to claims in the JWT {@code roles} array.
 *
 * See LLD-02 §11 (Role Matrix) for the full permission table.
 * See {@code dora-docs/ROLE-MATRIX.md} for the published snapshot.
 */
public final class RoleNames {

    // Service-company operator: platform config only. Zero access to bank incident data.
    // BR-011 / NFR-009: must be BLOCKED from all incident endpoints.
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    // Bank operations analyst: incident read/write, create/update-status, ops dashboard.
    public static final String OPS_ANALYST = "OPS_ANALYST";

    // Bank incident manager: full incident write, classification override, PIR write, ops dashboard.
    public static final String INCIDENT_MANAGER = "INCIDENT_MANAGER";

    // Bank compliance officer: incident read/write (notes, client-notif), report submission,
    // compliance dashboard.
    public static final String COMPLIANCE_OFFICER = "COMPLIANCE_OFFICER";

    // Bank CISO: incident override/sign-off, classification override, PIR sign-off,
    // compliance dashboard.
    public static final String CISO = "CISO";

    // Bank board member: read-only on major incidents and summary; board dashboard.
    public static final String BOARD_VIEWER = "BOARD_VIEWER";

    // Internal system account: automated / unit-test calls only; not assignable to humans.
    // B-4: system@dora.local is seeded but SYSTEM role is not used in LLD-02 @PreAuthorize.
    public static final String SYSTEM = "SYSTEM";

    private RoleNames() {
        // Utility class — no instances
    }
}
