package com.movinsync.tracking.domain.geo;

import java.util.Set;

/**
 * Circle geofence: contains check uses haversine distance from centre.
 * <p>
 * Entry: distance <= radius.
 * Exit:  distance <= radius + exitMargin (hysteresis).
 */
public class CircleGeofence extends Geofence {

    private final GeoPoint centre;
    private final double radiusMetres;

    public CircleGeofence(long id, String name, GeoPoint centre, double radiusMetres,
                          TimeWindow timeWindow, Set<Long> allowedVehicleIds) {
        super(id, name, timeWindow, allowedVehicleIds);
        this.centre = centre;
        this.radiusMetres = radiusMetres;
    }

    public GeoPoint getCentre() { return centre; }
    public double getRadiusMetres() { return radiusMetres; }

    @Override
    public boolean contains(GeoPoint point, double marginMetres) {
        double distance = GeoUtils.haversineMetres(centre, point);
        return distance <= radiusMetres + marginMetres;
    }

    @Override
    public BoundingBox boundingBox(double marginMetres) {
        double totalRadius = radiusMetres + marginMetres;
        double dLat = GeoUtils.metresToDegreesLat(totalRadius);
        double dLon = GeoUtils.metresToDegreesLon(totalRadius, centre.lat());
        return new BoundingBox(
                centre.lat() - dLat,
                centre.lat() + dLat,
                centre.lon() - dLon,
                centre.lon() + dLon
        );
    }
}
