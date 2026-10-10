package com.movinsync.tracking.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Persisted ENTRY/EXIT event with outbox status for notification retry.
 */
@Entity
@Table(name = "geofence_events")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class GeofenceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeofenceEventType eventType;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(nullable = false)
    private Long zoneId;

    /** The ping timestamp (epoch ms) that triggered this event */
    @Column(nullable = false)
    private Long timestampMs;

    @Column(nullable = false)
    @Builder.Default
    private Boolean detectedAfterGap = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EventStatus status = EventStatus.PENDING;
}
