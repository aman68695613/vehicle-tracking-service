package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private final com.movinsync.tracking.service.GeofenceService geofenceService;
    private final com.movinsync.tracking.repository.GeofenceRepository geofenceRepository;

    public GeofenceController(com.movinsync.tracking.service.GeofenceService geofenceService,
                              com.movinsync.tracking.repository.GeofenceRepository geofenceRepository) {
        this.geofenceService = geofenceService;
        this.geofenceRepository = geofenceRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getGeofences() {
        return ResponseEntity.ok(geofenceRepository.findAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createGeofence(@RequestBody com.movinsync.tracking.entity.GeofenceEntity geofence) {
        com.movinsync.tracking.entity.GeofenceEntity saved = geofenceRepository.save(geofence);
        geofenceService.reloadZones();
        return ResponseEntity.ok(saved);
    }
}
