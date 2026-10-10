package com.movinsync.tracking.domain.geo;

/**
 * Utility class for geographic calculations.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_METRES = 6_371_000.0;

    private GeoUtils() {}

    /**
     * Haversine distance in metres between two points.
     */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METRES * c;
    }

    /**
     * Haversine distance between two GeoPoints.
     */
    public static double haversineMetres(GeoPoint p1, GeoPoint p2) {
        return haversineMetres(p1.lat(), p1.lon(), p2.lat(), p2.lon());
    }

    /**
     * Convert metres to approximate degrees of latitude.
     */
    public static double metresToDegreesLat(double metres) {
        return metres / (EARTH_RADIUS_METRES * Math.PI / 180.0);
    }

    /**
     * Convert metres to approximate degrees of longitude at a given latitude.
     */
    public static double metresToDegreesLon(double metres, double atLatitude) {
        double cosLat = Math.cos(Math.toRadians(atLatitude));
        if (cosLat < 1e-10) {
            return 360.0; // at poles, full wrap
        }
        return metres / (EARTH_RADIUS_METRES * Math.PI / 180.0 * cosLat);
    }

    /**
     * Project a point onto a line segment (p1->p2) using a local flat-earth approximation.
     * Returns the parameter t clamped to [0, 1] and the projected point.
     *
     * @return a ProjectionResult with the clamped t, the projected GeoPoint, and distance in metres.
     */
    public static ProjectionResult projectOntoSegment(GeoPoint point, GeoPoint p1, GeoPoint p2) {
        double cosLat = Math.cos(Math.toRadians(point.lat()));

        // Convert to local metres (flat earth approximation)
        double x = (point.lon() - p1.lon()) * cosLat;
        double y = point.lat() - p1.lat();
        double dx = (p2.lon() - p1.lon()) * cosLat;
        double dy = p2.lat() - p1.lat();

        double segLenSq = dx * dx + dy * dy;
        double t;
        if (segLenSq < 1e-20) {
            t = 0.0; // degenerate segment
        } else {
            t = Math.max(0, Math.min(1, (x * dx + y * dy) / segLenSq));
        }

        double projLat = p1.lat() + t * (p2.lat() - p1.lat());
        double projLon = p1.lon() + t * (p2.lon() - p1.lon());
        GeoPoint projected = new GeoPoint(projLat, projLon);
        double distance = haversineMetres(point, projected);

        return new ProjectionResult(t, projected, distance);
    }

    /**
     * Result of projecting a point onto a line segment.
     *
     * @param t          parameter along the segment [0, 1]
     * @param projected  the closest point on the segment
     * @param distanceMetres  haversine distance from the original point to the projected point
     */
    public record ProjectionResult(double t, GeoPoint projected, double distanceMetres) {}
}
