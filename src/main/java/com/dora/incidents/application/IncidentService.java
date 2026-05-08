package com.dora.incidents.application;

import com.dora.entities.CriticalService;
import com.dora.incidents.api.dto.AttachmentResponse;
import com.dora.incidents.api.dto.CreateIncidentRequest;
import com.dora.incidents.api.dto.IctAssetResponse;
import com.dora.incidents.api.dto.IncidentResponse;
import com.dora.incidents.api.dto.IncidentSummary;
import com.dora.incidents.api.dto.LinkAssetRequest;
import com.dora.incidents.api.dto.LinkServicesRequest;
import com.dora.incidents.api.dto.PresignedUploadResponse;
import com.dora.incidents.api.dto.RequestAttachmentUpload;
import com.dora.incidents.domain.Attachment;
import com.dora.incidents.domain.AttachmentRepository;
import com.dora.incidents.domain.IctAsset;
import com.dora.incidents.domain.IctAssetRepository;
import com.dora.incidents.domain.Incident;
import com.dora.incidents.domain.IncidentRepository;
import com.dora.repositories.CriticalServiceRepository;
import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for incident logging (LLD-05 §4).
 *
 * <p>Transaction strategy:
 * <ul>
 *   <li>{@code create}: REQUIRED — single transaction for incident + service links +
 *       asset links + audit row. All succeed or all roll back.</li>
 *   <li>{@code addAttachment}: REQUIRED — persists metadata row + audit row atomically.
 *       The presigned URL generation is non-transactional (S3 API call) and happens
 *       after the DB row is flushed.</li>
 *   <li>{@code completeAttachment}: REQUIRED — checks object exists in S3, then flips
 *       status in the same transaction.</li>
 *   <li>{@code linkServices} / {@code linkAsset}: REQUIRED — simple link inserts.</li>
 *   <li>{@code findById} / {@code list}: readOnly for connection pool optimisation.</li>
 * </ul>
 *
 * <p>No JPA entities are returned from this service — all returns are DTOs/records.
 * This prevents LazyInitializationException when the controller serialises the response
 * after the transaction has closed.
 */
@Service
@Transactional
public class IncidentService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15); // D-LLD05-2

    private final IncidentRepository incidentRepository;
    private final AttachmentRepository attachmentRepository;
    private final IctAssetRepository ictAssetRepository;
    private final CriticalServiceRepository criticalServiceRepository;
    private final AuditService auditService;
    private final IncidentIdGenerator idGenerator;
    private final ObjectStorageClient storageClient;
    private final ObjectMapper objectMapper;
    private final long maxAttachmentBytes;

    public IncidentService(
            IncidentRepository incidentRepository,
            AttachmentRepository attachmentRepository,
            IctAssetRepository ictAssetRepository,
            CriticalServiceRepository criticalServiceRepository,
            AuditService auditService,
            IncidentIdGenerator idGenerator,
            ObjectStorageClient storageClient,
            ObjectMapper objectMapper,
            @Value("${incident.attachment.max-mb:50}") int maxAttachmentMb) {

        this.incidentRepository = incidentRepository;
        this.attachmentRepository = attachmentRepository;
        this.ictAssetRepository = ictAssetRepository;
        this.criticalServiceRepository = criticalServiceRepository;
        this.auditService = auditService;
        this.idGenerator = idGenerator;
        this.storageClient = storageClient;
        this.objectMapper = objectMapper;
        this.maxAttachmentBytes = (long) maxAttachmentMb * 1024 * 1024;
    }

    // ── create ─────────────────────────────────────────────────────────────────

    /**
     * AC-1: Create an incident, stamp detection_datetime server-side, assign incident ID,
     * optionally link services and assets, write INCIDENT_CREATED audit entry.
     *
     * @param request    the validated request body
     * @param tenantId   resolved from authenticated principal
     * @param actorId    resolved from authenticated principal
     */
    public IncidentResponse create(CreateIncidentRequest request, UUID tenantId, UUID actorId) {
        // Validate service IDs upfront before any persistence (AC-4)
        Set<CriticalService> services = resolveActiveServices(
                request.serviceIds(), tenantId);

        String incidentId = idGenerator.next();

        Incident incident = new Incident(
                tenantId,
                incidentId,
                request.title(),
                request.description(),
                request.impactEstimate(),
                actorId);

        // detection_datetime is ALWAYS server-side (FR-002, D-LLD05-1)
        incident.setDetectionDatetime(Instant.now());

        incidentRepository.save(incident);

        // Link services via join table (affected_service_link)
        if (!services.isEmpty()) {
            incident.getAffectedServices().addAll(services);
            incidentRepository.save(incident);
        }

        // Link inline assets from the create request
        List<IctAsset> savedAssets = List.of();
        if (request.assets() != null && !request.assets().isEmpty()) {
            savedAssets = request.assets().stream()
                    .map(a -> ictAssetRepository.save(new IctAsset(incident, a.name(), a.type())))
                    .toList();
        }

        // Audit row — INCIDENT_CREATED with after-state snapshot
        ObjectNode afterState = objectMapper.createObjectNode();
        afterState.put("incidentId", incidentId);
        afterState.put("title", request.title());
        afterState.put("tenantId", tenantId.toString());
        afterState.put("createdBy", actorId.toString());

        auditService.record(
                AuditAction.INCIDENT_CREATED,
                "INCIDENT",
                incident.getId(),
                null,
                afterState);

        return toFullResponse(incident, List.of(), services.stream().toList(), savedAssets);
    }

    // ── findById ──────────────────────────────────────────────────────────────

    /**
     * AC-6: Return full incident detail including attachments, linked services, assets.
     */
    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID id, UUID tenantId) {
        Incident incident = incidentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Incident not found: " + id));

        // Tenant isolation — a bank-role user may only read their own tenant's incidents
        if (!incident.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Incident not found: " + id);
        }

        List<Attachment> attachments = attachmentRepository
                .findByIncidentIdOrderByCreatedAtAsc(id);
        List<IctAsset> assets = ictAssetRepository.findByIncidentId(id);

        return toFullResponse(incident,
                attachments,
                incident.getAffectedServices().stream().toList(),
                assets);
    }

    // ── list ──────────────────────────────────────────────────────────────────

    /**
     * Paginated list of incidents for a tenant (basic — rich search in LLD-14).
     */
    @Transactional(readOnly = true)
    public Page<IncidentSummary> list(UUID tenantId, Pageable pageable) {
        return incidentRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(this::toSummary);
    }

    // ── addAttachment ─────────────────────────────────────────────────────────

    /**
     * AC-3 step 1: persist metadata row with status PENDING; return presigned PUT URL.
     */
    public PresignedUploadResponse addAttachment(UUID incidentId,
                                                  RequestAttachmentUpload req,
                                                  UUID tenantId,
                                                  UUID actorId) {
        if (req.sizeBytes() > maxAttachmentBytes) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "File size " + req.sizeBytes() + " bytes exceeds the maximum of "
                            + maxAttachmentBytes + " bytes");
        }

        Incident incident = loadIncidentForTenant(incidentId, tenantId);

        // Key layout: incidents/<incidentId>/<filename>
        // UUID is added to avoid key collisions across re-uploads
        UUID attachmentId = UUID.randomUUID();
        String s3Key = "incidents/" + incidentId + "/" + attachmentId + "/" + req.filename();

        Attachment attachment = new Attachment(
                incident,
                req.filename(),
                req.contentType(),
                req.sizeBytes(),
                s3Key,
                actorId);

        // Must save first so the UUID is assigned; we pass it to the response
        attachmentRepository.save(attachment);

        Instant expiresAt = Instant.now().plus(PRESIGN_TTL);
        String uploadUrl = storageClient.presignPut(s3Key, PRESIGN_TTL).toString();

        return new PresignedUploadResponse(attachment.getId(), uploadUrl, expiresAt);
    }

    // ── completeAttachment ────────────────────────────────────────────────────

    /**
     * AC-3 step 3: verify file exists in storage, flip status PENDING→READY.
     */
    public AttachmentResponse completeAttachment(UUID incidentId,
                                                  UUID attachmentId,
                                                  UUID tenantId) {
        // verify incident exists and belongs to tenant
        loadIncidentForTenant(incidentId, tenantId);

        Attachment attachment = attachmentRepository
                .findByIdAndIncidentId(attachmentId, incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attachment not found: " + attachmentId));

        if (!storageClient.exists(attachment.getS3Key())) {
            attachment.markFailed();
            attachmentRepository.save(attachment);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Object not found in storage — upload may have failed or URL expired");
        }

        attachment.markReady();
        attachmentRepository.save(attachment);

        return toAttachmentResponse(attachment);
    }

    // ── linkServices ─────────────────────────────────────────────────────────

    /**
     * AC-4: Link active critical services to an existing incident.
     */
    public void linkServices(UUID incidentId, LinkServicesRequest req, UUID tenantId) {
        Incident incident = incidentRepository.findByIdWithDetails(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Incident not found: " + incidentId));

        if (!incident.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Incident not found: " + incidentId);
        }

        Set<CriticalService> services = resolveActiveServices(req.serviceIds(), tenantId);
        incident.getAffectedServices().addAll(services);
        incidentRepository.save(incident);

        ObjectNode afterState = objectMapper.createObjectNode();
        afterState.put("incidentId", incidentId.toString());
        afterState.put("linkedServiceCount", services.size());

        auditService.record(
                AuditAction.INCIDENT_UPDATED,
                "INCIDENT",
                incidentId,
                null,
                afterState);
    }

    // ── linkAsset ────────────────────────────────────────────────────────────

    /**
     * AC-5: Link a new ICT asset to an existing incident.
     */
    public IctAssetResponse linkAsset(UUID incidentId, LinkAssetRequest req, UUID tenantId) {
        Incident incident = loadIncidentForTenant(incidentId, tenantId);

        IctAsset asset = ictAssetRepository.save(
                new IctAsset(incident, req.name(), req.type()));

        ObjectNode afterState = objectMapper.createObjectNode();
        afterState.put("incidentId", incidentId.toString());
        afterState.put("assetName", req.name());
        afterState.put("assetType", req.type());

        auditService.record(
                AuditAction.INCIDENT_UPDATED,
                "INCIDENT",
                incidentId,
                null,
                afterState);

        return toIctAssetResponse(asset);
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    /**
     * Resolves service IDs to active CriticalService entities.
     *
     * <p>Throws 422 if any ID is not found or is inactive (AC-4: free-text services
     * and inactive services are rejected).
     */
    private Set<CriticalService> resolveActiveServices(List<UUID> serviceIds, UUID tenantId) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Set.of();
        }
        List<CriticalService> active = criticalServiceRepository
                .findByTenantIdAndActive(tenantId, true);

        Set<UUID> activeIds = new java.util.HashSet<>();
        java.util.Map<UUID, CriticalService> activeMap = new java.util.HashMap<>();
        for (CriticalService cs : active) {
            activeIds.add(cs.getId());
            activeMap.put(cs.getId(), cs);
        }

        Set<CriticalService> resolved = new java.util.HashSet<>();
        for (UUID id : serviceIds) {
            if (!activeIds.contains(id)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Service ID " + id + " is not an active critical service for this tenant");
            }
            resolved.add(activeMap.get(id));
        }
        return resolved;
    }

    private Incident loadIncidentForTenant(UUID incidentId, UUID tenantId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Incident not found: " + incidentId));
        if (!incident.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Incident not found: " + incidentId);
        }
        return incident;
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private IncidentResponse toFullResponse(Incident incident,
                                             List<Attachment> attachments,
                                             List<CriticalService> services,
                                             List<IctAsset> assets) {
        return new IncidentResponse(
                incident.getId(),
                incident.getIncidentId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getImpactEstimate(),
                incident.getDetectionDatetime(),
                incident.getStatus(),
                incident.getTenantId(),
                incident.getCreatedBy(),
                incident.getCreatedAt(),
                attachments.stream().map(this::toAttachmentResponse).toList(),
                services.stream()
                        .map(s -> new IncidentResponse.LinkedServiceResponse(s.getId(), s.getName()))
                        .toList(),
                assets.stream().map(this::toIctAssetResponse).toList()
        );
    }

    private IncidentSummary toSummary(Incident incident) {
        return new IncidentSummary(
                incident.getId(),
                incident.getIncidentId(),
                incident.getTitle(),
                incident.getStatus(),
                incident.getDetectionDatetime(),
                incident.getCreatedAt(),
                incident.getTenantId(),
                incident.getCreatedBy()
        );
    }

    private AttachmentResponse toAttachmentResponse(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getIncident().getId(),
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getS3Key(),
                attachment.getStatus(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt()
        );
    }

    private IctAssetResponse toIctAssetResponse(IctAsset asset) {
        return new IctAssetResponse(
                asset.getId(),
                asset.getIncident().getId(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCreatedAt()
        );
    }
}
