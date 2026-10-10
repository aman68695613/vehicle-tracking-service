package com.movinsync.tracking.domain.state;

import com.movinsync.tracking.entity.GeofenceEventType;

/**
 * Represents a single geofence state transition (ENTRY or EXIT) produced by the engine.
 * Immutable — the engine returns these, it does not mutate state.
 */
public record ZoneTransition(
        long zoneId,
        GeofenceEventType type,
        boolean detectedAfterGap
) {}
