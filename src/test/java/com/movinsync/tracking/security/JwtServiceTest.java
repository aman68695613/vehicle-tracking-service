package com.movinsync.tracking.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    void validateTokenAndGetClaims_invalidToken() {
        JwtService jwtService = new JwtService();
        org.springframework.test.util.ReflectionTestUtils.setField(jwtService, "secretKeyString", "movinsync-super-secret-key-that-is-at-least-32-bytes-long");
        jwtService.init();
        assertThrows(Exception.class, () -> jwtService.validateTokenAndGetClaims("invalid.token.here"));
    }
}
