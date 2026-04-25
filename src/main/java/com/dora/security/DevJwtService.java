package com.dora.security;

import com.dora.entities.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * HMAC-SHA256 JWT issuer and validator for the local dev environment.
 *
 * <p><b>Production swap note (LLD-16):</b> this service stands in for Amazon Cognito.
 * The token shape deliberately mirrors Cognito's structure:
 * {@code sub} = user email, {@code roles} = string array, {@code tenant_id} = UUID string,
 * {@code iat}/{@code exp} standard claims. When LLD-16 integrates Cognito, replace
 * this service with a Cognito public-key JWKS validator — downstream code that reads
 * {@code Claims} will not need to change.
 *
 * <p><b>MFA note (B-3):</b> {@code mfa_enabled} is stored in {@code app_user} and
 * returned in {@code UserProfile}, but MFA is not enforced here.  Enforcement lands
 * with Cognito in LLD-16.  The TOTP hook point is: after password check in
 * {@code AuthService.login}, check {@code user.isMfaEnabled()} and call a TOTP
 * verifier before issuing the token.
 *
 * <p><b>Clock-skew (B-2):</b> 60 seconds tolerance is configured via
 * {@code dora.jwt.clock-skew-seconds}. Documented in {@code application.yml}.
 *
 * <p>Secret and TTL come from environment variables — never hardcoded.
 * See {@code .env.example} at the workspace root.
 */
@Service
public class DevJwtService {

    private final SecretKey signingKey;
    private final long ttlMinutes;
    private final long clockSkewSeconds;

    public DevJwtService(
            @Value("${dora.jwt.secret}") String secret,
            @Value("${dora.jwt.ttl-minutes:60}") long ttlMinutes,
            @Value("${dora.jwt.clock-skew-seconds:60}") long clockSkewSeconds) {

        // Keys.hmacShaKeyFor requires ≥256 bits (32 bytes) for HS256.
        // The startup-time failure here is intentional: misconfiguration must be loud.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * Issues a new HMAC-SHA256 signed JWT for the given user.
     *
     * @param user the authenticated user — roles and tenant_id are sourced from here
     * @return signed compact JWT string
     */
    public String issue(AppUser user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlMinutes * 60);

        List<String> roleCodes = user.getRoles().stream()
            .map(r -> r.getCode())
            .sorted()  // deterministic order for tests
            .toList();

        return Jwts.builder()
            .subject(user.getEmail())
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claim("roles", roleCodes)
            .claim("tenant_id", user.getTenant().getId().toString())
            .signWith(signingKey)
            .compact();
    }

    /**
     * Validates and parses a compact JWT string.
     *
     * <p>Clock-skew of {@code clockSkewSeconds} is applied so minor time drift between
     * client and server does not cause spurious 401s (B-2).
     *
     * @param token compact JWT
     * @return parsed {@link Claims} — caller reads {@code sub}, {@code roles}, {@code tenant_id}
     * @throws JwtException if the token is invalid, tampered, or expired (beyond skew)
     */
    public Claims verify(String token) {
        return Jwts.parser()
            .clockSkewSeconds(clockSkewSeconds)
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * Returns the token expiry {@link Instant} for a freshly issued token,
     * useful when constructing {@link com.dora.dto.LoginResponse}.
     */
    public Instant expiresAt() {
        return Instant.now().plusSeconds(ttlMinutes * 60);
    }
}
