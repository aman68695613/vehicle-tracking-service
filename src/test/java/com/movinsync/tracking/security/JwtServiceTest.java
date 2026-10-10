package com.movinsync.tracking.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService();

    @Test
    void validateTokenAndGetSubject_invalidToken() {
        assertThrows(Exception.class, () -> jwtService.validateTokenAndGetSubject("invalid.token.here"));
    }
}
