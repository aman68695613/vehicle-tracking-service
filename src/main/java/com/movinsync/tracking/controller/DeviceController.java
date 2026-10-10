package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

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

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getDevices() {
        return ResponseEntity.ok(deviceRepository.findAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createDevice(@RequestBody com.movinsync.tracking.entity.Device device) {
        com.movinsync.tracking.entity.Device saved = deviceRepository.save(device);
        deviceService.evictCache();
        return ResponseEntity.ok(saved);
    }
}
