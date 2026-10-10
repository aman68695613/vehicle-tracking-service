package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private final com.movinsync.tracking.service.GeofenceService geofenceService;

    public GeofenceController(com.movinsync.tracking.service.GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getGeofences() {
        return ResponseEntity.ok(java.util.Collections.emptyList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createGeofence(@RequestBody Object geofence) {
        // Implement save logic here...
        geofenceService.reloadZones();
        return ResponseEntity.ok().build();
    }
}
