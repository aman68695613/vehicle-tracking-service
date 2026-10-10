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

    @org.springframework.beans.factory.annotation.Value("${jwt.secret:movinsync-super-secret-key-that-is-at-least-32-bytes-long}")
    private String secretKeyString;
    
    private SecretKey key;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.key = new javax.crypto.spec.SecretKeySpec(secretKeyString.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Validates a JWT token and returns the claims if valid.
     * Throws JwtException if invalid.
     */
    public Claims validateTokenAndGetClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
