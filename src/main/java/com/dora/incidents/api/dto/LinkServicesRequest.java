package com.dora.incidents.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/incidents/{id}/services (LLD-05 §4, AC-4).
 *
 * <p>Each UUID must reference an active critical_service row for the incident's tenant.
 * Inactive or unknown IDs cause a 422 (service validation in IncidentService.linkServices).
 */
public record LinkServicesRequest(

        @NotEmpty(message = "at least one serviceId is required")
        List<UUID> serviceIds

) {}
