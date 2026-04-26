package com.dora.thorough.audit;

import com.dora.services.audit.AuditedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.NoRepositoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test confirming {@link AuditedRepository} carries {@code @NoRepositoryBean}.
 *
 * <p>Without the annotation Spring Data JPA's component scan finds the raw
 * {@code AuditedRepository<Object,Object>} interface and attempts to create a JPA proxy for
 * it, failing at context startup with "Not a managed type: class java.lang.Object".
 * Fix was applied in dora-api PR #9 ({@code fix/LLD-03-audit-bugs}).
 */
@Tag("AC-6")
@DisplayName("AuditedRepository carries @NoRepositoryBean — Spring Data JPA context-start regression")
class AuditedRepositoryBugTest {

    @Test
    @DisplayName("AuditedRepository has @NoRepositoryBean so Spring Data JPA does not instantiate it")
    void auditedRepository_hasNoRepositoryBeanAnnotation() {
        assertThat(AuditedRepository.class.isAnnotationPresent(NoRepositoryBean.class))
                .as("AuditedRepository must be annotated with @NoRepositoryBean to prevent " +
                    "Spring Data JPA from instantiating it as a raw Object-typed repository")
                .isTrue();
    }
}
