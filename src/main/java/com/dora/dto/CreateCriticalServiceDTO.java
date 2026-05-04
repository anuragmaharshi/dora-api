package com.dora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/admin/critical-services.
 */
public record CreateCriticalServiceDTO(
        @NotBlank @Size(max = 255) String name,
        String description
) {}
