package com.dora.dto;

import java.time.Instant;

/**
 * Successful login / refresh payload. {@code expiresAt} lets the frontend schedule
 * a proactive refresh before the token is rejected (avoids a 401 mid-session).
 */
public record LoginResponse(
        String token,
        Instant expiresAt,
        UserProfile user) {
}
