package com.dora.thorough.audit;

import com.dora.services.audit.AuditedRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.NoRepositoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug documentation test for the missing {@code @NoRepositoryBean} annotation on
 * {@link AuditedRepository}.
 *
 * <h2>Bug description</h2>
 * {@link AuditedRepository} documents {@code @NoRepositoryBean} in its Javadoc but does NOT
 * apply it as an annotation on the interface declaration. As a result, Spring Data JPA's
 * component scan finds the raw {@code AuditedRepository<Object,Object>} interface and
 * attempts to create a concrete JPA proxy for it, which fails at context startup:
 * <pre>
 *   BeanCreationException: Error creating bean with name 'auditedRepository'
 *   defined in com.dora.services.audit.AuditedRepository: Not a managed type: class java.lang.Object
 * </pre>
 *
 * <h2>Impact</h2>
 * Any {@code @SpringBootTest} that uses the default component-scan of the {@code com.dora}
 * package (i.e., the full application context) fails to start. This breaks:
 * - {@code AuditServiceRoundTripSmokeTest} (AC-1 smoke)
 * - {@code AuditIntegrationTest} (which works around it via {@code @EnableJpaRepositories(basePackages = "com.dora.repositories")})
 *
 * <h2>Fix</h2>
 * Add {@code @NoRepositoryBean} on line 33 of {@code AuditedRepository.java}, immediately
 * before the {@code public interface AuditedRepository} declaration. One-line fix.
 *
 * <h2>Test status</h2>
 * This test is {@code @Disabled} because it is a documentation-only assertion (it verifies
 * the annotation is missing, which is a failing state). Once the Developer adds
 * {@code @NoRepositoryBean}, this test should be re-enabled and its assertion inverted to
 * confirm the fix.
 *
 * @see AuditedRepository
 */
@Tag("AC-6")
@DisplayName("BUG: AuditedRepository is missing @NoRepositoryBean on the interface declaration")
class AuditedRepositoryBugTest {

    @Test
    @Disabled("Documents existing production bug — re-enable and invert assertion after Developer adds @NoRepositoryBean to AuditedRepository")
    @DisplayName("FAILING (expected): AuditedRepository should carry @NoRepositoryBean but does not")
    void auditedRepository_shouldHave_noRepositoryBeanAnnotation() {
        // This assertion currently FAILS (annotation is missing).
        // When the Developer adds @NoRepositoryBean, this test should be re-enabled and passes.
        boolean hasAnnotation = AuditedRepository.class.isAnnotationPresent(NoRepositoryBean.class);
        assertThat(hasAnnotation)
                .as("AuditedRepository must be annotated with @NoRepositoryBean to prevent " +
                    "Spring Data JPA from trying to instantiate it as a raw Object-typed repository. " +
                    "Fix: add @NoRepositoryBean before 'public interface AuditedRepository' in " +
                    "src/main/java/com/dora/services/audit/AuditedRepository.java")
                .isTrue();
    }

    @Test
    @Tag("AC-6")
    @DisplayName("Verify the annotation is currently missing (demonstrates the bug — will need inversion post-fix)")
    void auditedRepository_currentlyMissing_noRepositoryBeanAnnotation() {
        // This test PASSES in the buggy state — it documents what IS currently true.
        // After the fix it will fail, reminding the Developer to also enable the companion test above.
        boolean hasAnnotation = AuditedRepository.class.isAnnotationPresent(NoRepositoryBean.class);
        assertThat(hasAnnotation)
                .as("BUG CONFIRMED: @NoRepositoryBean is missing from AuditedRepository. " +
                    "See class Javadoc for fix instructions.")
                .isFalse();
    }
}
