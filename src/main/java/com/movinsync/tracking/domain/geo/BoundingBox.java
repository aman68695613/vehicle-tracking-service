package com.movinsync.tracking.domain.geo;

/**
 * Axis-aligned bounding box for spatial indexing.
 */
public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

    public BoundingBox {
        if (minLat > maxLat) throw new IllegalArgumentException("minLat > maxLat");
        if (minLon > maxLon) throw new IllegalArgumentException("minLon > maxLon");
    }
}
