package com.movinsync.tracking.security;

import com.movinsync.tracking.entity.Device;
import com.movinsync.tracking.service.DeviceRegistryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceApiKeyFilterTest {

    private DeviceRegistryService registryService;
    private DeviceApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        registryService = mock(DeviceRegistryService.class);
        filter = new DeviceApiKeyFilter(registryService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String hash(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void doFilterInternal_validKey_setsAuthentication() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Device-ID")).thenReturn("device-123");
        when(req.getHeader("X-API-Key")).thenReturn("secret");

        Device device = new Device();
        device.setDeviceId("device-123");
        device.setApiKeyHash(hash("secret"));
        when(registryService.findDevice("device-123")).thenReturn(Optional.of(device));

        filter.doFilterInternal(req, res, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("DEVICE_device-123", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(chain).doFilter(req, res);
    }

    @Test
    void doFilterInternal_invalidKey_doesNotAuthenticate() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Device-ID")).thenReturn("device-123");
        when(req.getHeader("X-API-Key")).thenReturn("wrong");

        Device device = new Device();
        device.setDeviceId("device-123");
        device.setApiKeyHash(hash("secret"));
        when(registryService.findDevice("device-123")).thenReturn(Optional.of(device));

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(req, res);
    }
}
