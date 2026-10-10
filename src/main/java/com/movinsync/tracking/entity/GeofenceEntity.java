package com.movinsync.tracking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/**
 * Persisted geofence zone. The domain model uses abstract Geofence / CircleGeofence / RectangleGeofence
 * for runtime geometry; this entity holds the DB representation. Shape-specific fields are nullable.
 */
@Entity
@Table(name = "geofences")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class GeofenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeofenceShape shape;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeofencePurpose purpose;

    /** Optional link to a route stop (for PICKUP zones) */
    private Long stopId;

    // ---- Circle fields ----

    /** Centre latitude (circle only) */
    private Double centreLat;

    /** Centre longitude (circle only) */
    private Double centreLon;

    /** Radius in metres (circle only) */
    private Double radiusMetres;

    // ---- Rectangle fields ----

    private Double minLat;
    private Double maxLat;
    private Double minLon;
    private Double maxLon;

    /** Margin in metres for rectangle hysteresis (rectangle only) */
    @Builder.Default
    private Double marginMetres = 0.0;

    // ---- Time window (optional) ----

    private LocalTime windowStart;
    private LocalTime windowEnd;

    /** Timezone for the time window, e.g. "Asia/Kolkata" */
    private String windowTimezone;

    // ---- Vehicle filter (optional) ----

    /** Comma-separated list of allowed vehicle IDs (empty = all vehicles) */
    @Column(columnDefinition = "TEXT")
    private String allowedVehicleIds;
}
