package com.movinsync.tracking.domain.geo;

/**
 * Immutable lat/lon point.
 */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Latitude must be in [-90, 90], got " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Longitude must be in [-180, 180], got " + lon);
        }
    }
}
