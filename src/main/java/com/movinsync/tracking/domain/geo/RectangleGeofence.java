package com.movinsync.tracking.domain.geo;

import java.util.Set;

/**
 * Rectangle geofence: axis-aligned lat/lon bounds.
 * <p>
 * Contains check tests whether the point is inside the rectangle.
 * Exit margin expands the rectangle in all directions by the given metres.
 */
public class RectangleGeofence extends Geofence {

    private final double minLat;
    private final double maxLat;
    private final double minLon;
    private final double maxLon;

    public RectangleGeofence(long id, String name,
                             double minLat, double maxLat, double minLon, double maxLon,
                             TimeWindow timeWindow, Set<Long> allowedVehicleIds) {
        super(id, name, timeWindow, allowedVehicleIds);
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLon = minLon;
        this.maxLon = maxLon;
    }

    public double getMinLat() { return minLat; }
    public double getMaxLat() { return maxLat; }
    public double getMinLon() { return minLon; }
    public double getMaxLon() { return maxLon; }

    @Override
    public boolean contains(GeoPoint point, double marginMetres) {
        double centreLat = (minLat + maxLat) / 2.0;
        double dLat = GeoUtils.metresToDegreesLat(marginMetres);
        double dLon = GeoUtils.metresToDegreesLon(marginMetres, centreLat);

        return point.lat() >= minLat - dLat
            && point.lat() <= maxLat + dLat
            && point.lon() >= minLon - dLon
            && point.lon() <= maxLon + dLon;
    }

    @Override
    public BoundingBox boundingBox(double marginMetres) {
        double centreLat = (minLat + maxLat) / 2.0;
        double dLat = GeoUtils.metresToDegreesLat(marginMetres);
        double dLon = GeoUtils.metresToDegreesLon(marginMetres, centreLat);
        return new BoundingBox(
                minLat - dLat,
                maxLat + dLat,
                minLon - dLon,
                maxLon + dLon
        );
    }
}
