package com.movinsync.tracking.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Service for stateless JWT validation.
 */
@Service
public class JwtService {

    // For case study purposes, a hardcoded secret. In production, load from properties/vault.
    private static final String SECRET_KEY_STRING = "movinsync-super-secret-key-that-is-at-least-32-bytes-long";
    private final SecretKey key;

    public JwtService() {
        this.key = Keys.hmacShaKeyFor(SECRET_KEY_STRING.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates a JWT token and returns the subject (e.g. userId) if valid.
     * Throws JwtException if invalid.
     */
    public String validateTokenAndGetSubject(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
