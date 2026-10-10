package com.movinsync.tracking.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Raw ping history. Unique constraint on (deviceId, timestamp) prevents duplicates.
 */
@Entity
@Table(name = "location_pings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "timestamp_ms"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class LocationPing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(name = "timestamp_ms", nullable = false)
    private Long timestampMs;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Speed in m/s */
    private Double speed;

    /** Bearing in degrees [0, 360) */
    private Double bearing;
}
