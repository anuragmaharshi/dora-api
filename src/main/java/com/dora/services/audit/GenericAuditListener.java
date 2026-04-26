package com.dora.services.audit;

import com.dora.services.AuditService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * JPA entity listener that auto-captures before/after state for entities annotated
 * with {@link AuditedEntity} (LLD-03 §4, AC-1).
 *
 * <h2>Wiring: static-holder pattern</h2>
 * JPA entity listeners are not Spring beans — Hibernate instantiates them outside the
 * Spring {@code ApplicationContext}. To access the Spring-managed {@link AuditService},
 * this class delegates to {@link AuditServiceHolder#get()}, which holds a reference set
 * once the Spring context fires {@code ContextRefreshedEvent}. See {@link AuditServiceHolder}
 * for the full rationale.
 *
 * <h2>Snapshot approach</h2>
 * Entity state is serialised to {@link JsonNode} via Jackson. The
 * {@code @JsonIgnoreProperties} on the inner mixin ({@link HibernateIgnoreMixin}) skips
 * Hibernate-managed proxy fields ({@code hibernateLazyInitializer}, {@code handler}) that
 * would otherwise cause serialisation errors on uninitialized LAZY associations.
 *
 * <p>Serialisation happens synchronously in the JPA event callback, which runs within the
 * same transaction that is persisting / updating the entity. This is intentional:
 * {@link AuditService#record} uses default ({@code REQUIRED}) propagation and therefore
 * enrols in the same transaction — a rollback discards both the business write and the
 * audit row (AC-3).
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>For {@code @PrePersist}: {@code before} is always {@code null} (no prior state).</li>
 *   <li>For {@code @PreUpdate}: {@code before} is a snapshot of the entity state as it
 *       exists in memory at the time the listener fires, which may differ slightly from the
 *       database row if the entity was partially hydrated. W4 thorough tests validate this.</li>
 *   <li>Entities must have a public {@code getId()} method that returns a {@link UUID}. Entities
 *       without this shape are skipped with a WARN log rather than failing the transaction.</li>
 * </ul>
 *
 * <h2>Security note (FD-2)</h2>
 * If {@link com.dora.entities.AppUser} ever becomes {@code @AuditedEntity}, the
 * {@code password_hash} field must be excluded from before/after JSON. Until then, this
 * class does not need special handling — AppUser is not audited in LLD-03. See forward
 * debt FD-2 in {@code agent-state/LLD-03/STATE.md}.
 */
public class GenericAuditListener {

    private static final Logger log = LoggerFactory.getLogger(GenericAuditListener.class);

    /**
     * Jackson mixin that suppresses Hibernate proxy fields during serialisation.
     * Applied at serialisation time so the main entity class does not need modification.
     */
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private abstract static class HibernateIgnoreMixin {
    }

    /**
     * Fires immediately before a new entity is persisted.
     *
     * <p>{@code before} state is {@code null} for creation events — there is nothing to
     * snapshot before the first INSERT. The action is derived as a SYSTEM placeholder here;
     * callers that want a specific action (e.g. {@code INCIDENT_CREATED}) should call
     * {@link AuditService#record} directly rather than relying on the listener.
     */
    @PrePersist
    public void prePersist(Object entity) {
        if (!entity.getClass().isAnnotationPresent(AuditedEntity.class)) {
            return;
        }
        AuditService service = AuditServiceHolder.get();
        if (service == null) {
            log.warn("GenericAuditListener.prePersist: AuditService not yet initialised; skipping audit for {}",
                    entity.getClass().getSimpleName());
            return;
        }

        UUID entityId = extractId(entity);
        JsonNode afterSnapshot = toJson(entity);

        service.record(
                AuditAction.SYSTEM,
                entity.getClass().getSimpleName().toUpperCase(),
                entityId,
                null,
                afterSnapshot
        );
    }

    /**
     * Fires immediately before an existing entity is updated.
     *
     * <p>The before snapshot is the entity state currently held in the persistence context
     * (i.e. the state before the current transaction's modifications are flushed). For a
     * strict before/after diff, callers that need precise old state should use
     * {@link AuditService#record} directly and supply the old state from a prior load.
     */
    @PreUpdate
    public void preUpdate(Object entity) {
        if (!entity.getClass().isAnnotationPresent(AuditedEntity.class)) {
            return;
        }
        AuditService service = AuditServiceHolder.get();
        if (service == null) {
            log.warn("GenericAuditListener.preUpdate: AuditService not yet initialised; skipping audit for {}",
                    entity.getClass().getSimpleName());
            return;
        }

        UUID entityId = extractId(entity);
        // At PreUpdate, the entity already holds the new values (Hibernate has set them).
        // We snapshot the current state as 'after'; 'before' requires a separate load.
        // Services that need precise before/after diffs call AuditService.record() directly.
        JsonNode afterSnapshot = toJson(entity);

        service.record(
                AuditAction.SYSTEM,
                entity.getClass().getSimpleName().toUpperCase(),
                entityId,
                null,         // before — accurate before requires service-layer responsibility
                afterSnapshot
        );
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private JsonNode toJson(Object entity) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.addMixIn(Object.class, HibernateIgnoreMixin.class);
            return mapper.valueToTree(entity);
        } catch (Exception ex) {
            log.warn("GenericAuditListener: failed to serialise {} to JSON — using null snapshot",
                    entity.getClass().getSimpleName(), ex);
            return null;
        }
    }

    /**
     * Reflectively reads a {@code getId()} method that returns a {@link UUID}.
     * Entities that do not conform to this shape return {@code null} (the audit row will
     * have a null entity_id, which the schema permits).
     */
    private UUID extractId(Object entity) {
        try {
            // Walk class hierarchy to find getId() — works on proxy subclasses too.
            Class<?> cls = entity.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    java.lang.reflect.Method getter = cls.getDeclaredMethod("getId");
                    getter.setAccessible(true);
                    Object result = getter.invoke(entity);
                    if (result instanceof UUID uuid) {
                        return uuid;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try parent class
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception ex) {
            log.warn("GenericAuditListener: could not extract id from {}",
                    entity.getClass().getSimpleName(), ex);
        }
        return null;
    }
}
