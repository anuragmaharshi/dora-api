package com.dora.services.audit;

import com.dora.services.AuditService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Static-holder bridge between the Spring application context and JPA entity listeners.
 *
 * <h2>Why a static holder?</h2>
 * JPA entity listeners (e.g. {@link GenericAuditListener}) are instantiated by the JPA
 * provider (Hibernate), not by Spring. Hibernate creates listener instances itself and has
 * no knowledge of the Spring {@code ApplicationContext}. This means:
 * <ol>
 *   <li>You cannot inject {@code @Autowired} or {@code @Component} dependencies into a
 *       JPA entity listener via Spring's normal constructor / field injection.</li>
 *   <li>The listener is created before the Spring context finishes wiring its beans,
 *       so even static initializer injection would race.</li>
 * </ol>
 *
 * <h2>Solution: static holder set from a Spring {@code @Configuration}</h2>
 * This class is a Spring-managed {@code @Configuration} bean. When the {@code ApplicationContext}
 * finishes starting (i.e. after all beans are wired), the {@link #onContextRefreshed} method
 * receives a {@link ContextRefreshedEvent} and sets the static {@code auditService} field.
 * From that point on, {@link GenericAuditListener} can obtain the live {@link AuditService}
 * instance via {@link #get()} without going through Spring.
 *
 * <h2>Thread safety</h2>
 * The field is {@code volatile} so that publication of the reference from the
 * {@code ContextRefreshedEvent} thread is visible to the JPA event threads (JPQL / Hibernate
 * flush). In practice, Spring's startup happens before any JPA operations, so this is a
 * belt-and-braces measure.
 *
 * <h2>Test isolation</h2>
 * Each {@code @SpringBootTest} context re-fires {@code ContextRefreshedEvent} and re-sets
 * the field. Slice tests that do not start a full context should either not trigger JPA
 * listeners or mock the holder via {@link #set(AuditService)}.
 */
@Configuration
public class AuditServiceHolder {

    private static volatile AuditService auditService;

    /** Called by {@link GenericAuditListener} to obtain the live {@link AuditService}. */
    public static AuditService get() {
        return auditService;
    }

    /**
     * Allows tests (and the context-refresh listener) to inject the service reference.
     * Package-private visibility keeps it out of accidental production use.
     */
    public static void set(AuditService service) {
        auditService = service;
    }

    /**
     * Wires the {@link AuditService} Spring bean into the static holder once the
     * {@code ApplicationContext} has fully started. This fires after all beans are
     * post-processed, so {@code service} is fully initialised.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        AuditService service = event.getApplicationContext().getBean(AuditService.class);
        auditService = service;
    }
}
