package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final com.movinsync.tracking.service.DeviceRegistryService deviceService;
    private final com.movinsync.tracking.repository.DeviceRepository deviceRepository;

    public DeviceController(com.movinsync.tracking.service.DeviceRegistryService deviceService,
                            com.movinsync.tracking.repository.DeviceRepository deviceRepository) {
        this.deviceService = deviceService;
        this.deviceRepository = deviceRepository;
    }

    /** Safe DTO — never exposes apiKeyHash or vehicle back-reference */
    public record DeviceDTO(Long id, String deviceId, Long vehicleId) {}

    /** Request body for creating a new device — accepts plain text API key */
    public record CreateDeviceRequest(String deviceId, String apiKey, Long vehicleId) {}

    private DeviceDTO toDTO(com.movinsync.tracking.entity.Device d) {
        Long vehicleId = d.getVehicle() != null ? d.getVehicle().getId() : null;
        return new DeviceDTO(d.getId(), d.getDeviceId(), vehicleId);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DeviceDTO>> getDevices() {
        List<DeviceDTO> dtos = deviceRepository.findAll().stream().map(this::toDTO).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createDevice(@RequestBody CreateDeviceRequest request) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return ResponseEntity.badRequest().body("deviceId is required");
        }
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return ResponseEntity.badRequest().body("apiKey is required");
        }
        if (request.vehicleId() == null) {
            return ResponseEntity.badRequest().body("vehicleId is required");
        }

        // Hash the API key server-side — never store plaintext
        String keyHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(request.apiKey().getBytes(StandardCharsets.UTF_8));
            keyHash = HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return ResponseEntity.internalServerError().body("SHA-256 unavailable");
        }

        com.movinsync.tracking.entity.Vehicle vehicle = new com.movinsync.tracking.entity.Vehicle();
        vehicle.setId(request.vehicleId());

        com.movinsync.tracking.entity.Device device = com.movinsync.tracking.entity.Device.builder()
                .deviceId(request.deviceId())
                .apiKeyHash(keyHash)
                .vehicle(vehicle)
                .build();

        com.movinsync.tracking.entity.Device saved = deviceRepository.save(device);
        deviceService.evictCache();
        return ResponseEntity.ok(toDTO(saved));
    }
}
