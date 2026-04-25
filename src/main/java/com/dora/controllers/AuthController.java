package com.dora.controllers;

import com.dora.dto.ErrorResponse;
import com.dora.dto.LoginRequest;
import com.dora.dto.LoginResponse;
import com.dora.dto.UserProfile;
import com.dora.security.CustomUserDetails;
import com.dora.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Authentication endpoints. All paths are under /api/v1/auth.
 *
 * POST /login is permit-all in SecurityConfig — no @PreAuthorize needed.
 * /me, /logout, /refresh all require an authenticated principal (enforced by
 * SecurityConfig's anyRequest().authenticated() + the JWT filter).
 *
 * Error mapping: BadCredentialsException → 401 with a generic body. The message
 * is deliberately vague so callers cannot distinguish "user not found" from
 * "wrong password" (user-enumeration prevention).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticate with email + password and receive a signed JWT.
     * Public endpoint — no authentication required.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request.email(), request.password());
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the authenticated caller's profile (roles, tenantId, mfaEnabled).
     * The principal is injected by Spring Security from the JWT filter's result.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserProfile> me(@AuthenticationPrincipal CustomUserDetails principal) {
        UserProfile profile = authService.getUserProfile(principal.getUsername());
        return ResponseEntity.ok(profile);
    }

    /**
     * Stateless logout — 204 No Content. The client is responsible for discarding the
     * token from sessionStorage. No server-side state to clear (B-1: no refresh table).
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /**
     * Issues a new JWT for the currently authenticated caller (stateless re-issue, B-1).
     * The caller presents their still-valid token; we load fresh user data from DB and
     * return a new token with a fresh expiry. This effectively extends the session without
     * a separate refresh-token table.
     */
    @PostMapping("/refresh")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LoginResponse> refresh(@AuthenticationPrincipal CustomUserDetails principal) {
        LoginResponse response = authService.refresh(principal.getUsername());
        return ResponseEntity.ok(response);
    }

    /**
     * Converts authentication failures to 401 with a safe, generic message.
     * No stack trace, no internal details in the response body.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        // "Invalid credentials" is deliberate — same message regardless of whether the
        // user doesn't exist, password is wrong, or the account is inactive.
        ErrorResponse body = new ErrorResponse("Invalid credentials", Instant.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
