package com.dora.dto;

import java.util.List;

/**
 * Authenticated user's profile, returned from GET /api/v1/auth/me and embedded in
 * LoginResponse. {@code tenantId} is the string representation of the UUID so the
 * frontend can use it directly in requests without UUID parsing.
 *
 * {@code mfaEnabled} is always present (AC-7): the field exists even when false so
 * the frontend can render an "enable MFA" prompt without null-checks.
 */
public record UserProfile(
        String email,
        List<String> roles,
        String tenantId,
        boolean mfaEnabled) {
}
