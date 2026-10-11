package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.movinsync.tracking.entity.GeofenceShape;

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
        // Validate required fields
        if (geofence.getShape() == null) {
            return ResponseEntity.badRequest().body("shape is required (CIRCLE or RECTANGLE)");
        }
        if (geofence.getPurpose() == null) {
            return ResponseEntity.badRequest().body("purpose is required");
        }
        if (geofence.getName() == null || geofence.getName().isBlank()) {
            return ResponseEntity.badRequest().body("name is required");
        }

        // Shape-specific validation
        if (geofence.getShape() == GeofenceShape.CIRCLE) {
            if (geofence.getCentreLat() == null || geofence.getCentreLon() == null) {
                return ResponseEntity.badRequest().body("centreLat and centreLon are required for CIRCLE shape");
            }
            if (geofence.getRadiusMetres() == null || geofence.getRadiusMetres() <= 0) {
                return ResponseEntity.badRequest().body("radiusMetres > 0 is required for CIRCLE shape");
            }
        } else if (geofence.getShape() == GeofenceShape.RECTANGLE) {
            if (geofence.getMinLat() == null || geofence.getMaxLat() == null
                    || geofence.getMinLon() == null || geofence.getMaxLon() == null) {
                return ResponseEntity.badRequest().body("minLat, maxLat, minLon, maxLon are required for RECTANGLE shape");
            }
            if (geofence.getMinLat() >= geofence.getMaxLat() || geofence.getMinLon() >= geofence.getMaxLon()) {
                return ResponseEntity.badRequest().body("minLat must be < maxLat and minLon must be < maxLon");
            }
        }

        com.movinsync.tracking.entity.GeofenceEntity saved = geofenceRepository.save(geofence);
        geofenceService.reloadZones();
        return ResponseEntity.ok(saved);
    }
}
