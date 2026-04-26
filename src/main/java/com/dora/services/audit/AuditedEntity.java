package com.dora.services.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that activates {@link GenericAuditListener} on a JPA entity.
 *
 * <p>Apply to any entity class whose lifecycle events should automatically produce
 * audit rows via {@code AuditService.record(...)}:
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "incident")
 * @EntityListeners(GenericAuditListener.class)
 * @AuditedEntity
 * public class Incident { ... }
 * }</pre>
 *
 * <p>No parameters in v1. Future versions may add {@code entityTypeOverride()} to
 * customise the {@code entity_type} value written to {@code audit_log}.
 *
 * <p>Constraint: entities annotated with {@code @AuditedEntity} must extend
 * {@link AuditedRepository} in their repository tier — never plain {@code JpaRepository}.
 * This is enforced at runtime by the repository base (AC-6, NFR-005).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedEntity {
}
