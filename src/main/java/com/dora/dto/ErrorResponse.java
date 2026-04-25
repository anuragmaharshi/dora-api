package com.dora.dto;

import java.time.Instant;

/**
 * Generic error body. {@code message} is always a safe, user-facing string — no
 * stack traces, no internal state. The timestamp aids log correlation.
 */
public record ErrorResponse(String message, Instant timestamp) {
}
