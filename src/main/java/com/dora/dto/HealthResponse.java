package com.dora.dto;

import java.time.Instant;

public record HealthResponse(String status, String version, Instant timestamp) {}
