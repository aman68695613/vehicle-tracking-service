package com.movinsync.tracking.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Persisted snapshot of a vehicle's live state for crash recovery.
 * Stored periodically and on transition events.
 */
@Entity
@Table(name = "vehicle_state_snapshots")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VehicleStateSnapshot {

    @Id
    private Long vehicleId;

    private Double lastLatitude;
    private Double lastLongitude;
    private Long lastTimestampMs;
    private Double speedEma;
    private Integer lastSnappedIndex;

    /** Serialised zone states as JSON */
    @Column(columnDefinition = "TEXT")
    private String zoneStatesJson;

    /** Serialised ETA map as JSON */
    @Column(columnDefinition = "TEXT")
    private String etaMapJson;
}
