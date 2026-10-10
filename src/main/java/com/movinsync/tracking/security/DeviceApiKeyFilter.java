package com.movinsync.tracking.security;

import com.movinsync.tracking.entity.Device;
import com.movinsync.tracking.service.DeviceRegistryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class DeviceApiKeyFilter extends OncePerRequestFilter {

    private static final String DEVICE_ID_HEADER = "X-Device-ID";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final DeviceRegistryService deviceRegistryService;

    public DeviceApiKeyFilter(DeviceRegistryService deviceRegistryService) {
        this.deviceRegistryService = deviceRegistryService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String deviceId = request.getHeader(DEVICE_ID_HEADER);
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (deviceId != null && apiKey != null) {
            Optional<Device> deviceOpt = deviceRegistryService.findDevice(deviceId);

            if (deviceOpt.isPresent()) {
                Device device = deviceOpt.get();
                String hashedKey = hashApiKey(apiKey);

                if (device.getApiKeyHash().equalsIgnoreCase(hashedKey)) {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            "DEVICE_" + deviceId, null, Collections.emptyList());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
