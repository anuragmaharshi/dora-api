package com.dora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PUT /api/v1/admin/critical-services/{id}.
 */
public record UpdateCriticalServiceDTO(
        @NotBlank @Size(max = 255) String name,
        String description
) {}
