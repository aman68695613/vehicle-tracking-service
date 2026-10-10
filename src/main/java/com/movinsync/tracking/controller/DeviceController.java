package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final com.movinsync.tracking.service.DeviceRegistryService deviceService;

    public DeviceController(com.movinsync.tracking.service.DeviceRegistryService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getDevices() {
        return ResponseEntity.ok(java.util.Collections.emptyList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createDevice(@RequestBody Object device) {
        deviceService.evictCache();
        return ResponseEntity.ok().build();
    }
}
