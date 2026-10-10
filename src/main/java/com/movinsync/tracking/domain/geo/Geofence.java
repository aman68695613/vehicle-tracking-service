package com.movinsync.tracking.domain.geo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * Abstract geofence zone with polymorphic geometry.
 * Subclasses override {@link #contains(GeoPoint, double)} and {@link #boundingBox(double)}.
 */
public abstract class Geofence {

    private final long id;
    private final String name;
    private final TimeWindow timeWindow;  // nullable
    private final Set<Long> allowedVehicleIds; // empty = all vehicles

    protected Geofence(long id, String name, TimeWindow timeWindow, Set<Long> allowedVehicleIds) {
        this.id = id;
        this.name = name;
        this.timeWindow = timeWindow;
        this.allowedVehicleIds = allowedVehicleIds == null ? Set.of() : allowedVehicleIds;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public TimeWindow getTimeWindow() { return timeWindow; }
    public Set<Long> getAllowedVehicleIds() { return allowedVehicleIds; }

    /**
     * Whether the point is inside this zone using the given margin (metres).
     * For entry checks, margin = 0. For exit checks, margin = hysteresis margin.
     */
    public abstract boolean contains(GeoPoint point, double marginMetres);

    /**
     * Bounding box expanded by the given margin, used for grid-index registration.
     */
    public abstract BoundingBox boundingBox(double marginMetres);

    /**
     * Whether this zone applies to the given vehicle.
     */
    public boolean appliesTo(long vehicleId) {
        return allowedVehicleIds.isEmpty() || allowedVehicleIds.contains(vehicleId);
    }

    /**
     * Whether this zone's time window is active at the given instant.
     * If no time window is set, always active.
     */
    public boolean isActiveAt(Instant pingTimestamp) {
        if (timeWindow == null) {
            return true;
        }
        return timeWindow.isActiveAt(pingTimestamp);
    }
}
