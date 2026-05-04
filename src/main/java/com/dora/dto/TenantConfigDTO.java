package com.dora.dto;

import java.util.UUID;

/**
 * Read-only view of the tenant configuration returned by GET /api/v1/admin/tenant.
 *
 * <p>All fields except legalName and id may be null (not all tenants are fully configured).
 */
public record TenantConfigDTO(
        UUID id,
        String legalName,
        String lei,
        String ncaName,
        String ncaEmail,
        String jurisdictionIso,
        UUID primaryComplianceContactId
) {}
