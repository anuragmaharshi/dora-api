package com.dora.repositories;

import com.dora.entities.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Login lookup by email. The {@code idx_app_user_email} index makes this O(log n).
     * Called on every login attempt and JWT filter validation path.
     */
    Optional<AppUser> findByEmail(String email);
}
