package com.dora.services;

import com.dora.dto.LoginResponse;
import com.dora.dto.UserProfile;
import com.dora.entities.AppUser;
import com.dora.repositories.AppUserRepository;
import com.dora.security.DevJwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for authentication and user-profile retrieval.
 *
 * Transactional boundary: login and getUserProfile each open a read-only transaction.
 * JWT issuance happens outside the transaction (no DB write, pure crypto).
 *
 * BadCredentialsException is used for all authentication failures — not found,
 * wrong password, inactive — so the caller cannot distinguish which condition failed
 * (prevents user-enumeration attacks).
 */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DevJwtService jwtService;

    public AuthService(AppUserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       DevJwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Validates credentials and issues a new JWT.
     *
     * Throws {@link BadCredentialsException} for any of: user not found, wrong
     * password, or {@code active=false}.  The exception message is intentionally
     * generic — the controller converts it to a 401 with "Invalid credentials".
     */
    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.isActive()) {
            // Inactive accounts are rejected with the same generic message to avoid
            // leaking account status to callers.
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // Roles are EAGER-loaded on the user, so this executes inside the same transaction.
        String token = jwtService.issue(user);
        return new LoginResponse(token, jwtService.expiresAt(), toProfile(user));
    }

    /**
     * Loads the current user's profile by email (taken from SecurityContext by controller).
     * Used by GET /me and POST /refresh.
     */
    @Transactional(readOnly = true)
    public UserProfile getUserProfile(String email) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return toProfile(user);
    }

    /**
     * Stateless token refresh: load current user by email, issue a new JWT.
     * Decision B-1: no refresh-token table — this is a re-issue of an access token.
     */
    @Transactional(readOnly = true)
    public LoginResponse refresh(String email) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!user.isActive()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String token = jwtService.issue(user);
        return new LoginResponse(token, jwtService.expiresAt(), toProfile(user));
    }

    private UserProfile toProfile(AppUser user) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .sorted()
                .toList();
        return new UserProfile(
                user.getEmail(),
                roles,
                user.getTenant().getId().toString(),
                user.isMfaEnabled());
    }
}
