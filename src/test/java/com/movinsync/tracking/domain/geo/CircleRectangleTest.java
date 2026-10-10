package com.movinsync.tracking.domain.geo;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CircleGeofence and RectangleGeofence geometry.
 */
class CircleRectangleTest {

    // ---- Circle ----

    @Test
    void circle_pointInside() {
        CircleGeofence zone = new CircleGeofence(1, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());

        // Centre is inside
        assertTrue(zone.contains(new GeoPoint(12.935, 77.610), 0));
    }

    @Test
    void circle_pointOnBoundary() {
        GeoPoint centre = new GeoPoint(12.935, 77.610);
        CircleGeofence zone = new CircleGeofence(1, "Z1", centre, 300.0, null, Set.of());

        // Point roughly 300 m north
        double dLat = GeoUtils.metresToDegreesLat(300.0);
        GeoPoint boundary = new GeoPoint(centre.lat() + dLat, centre.lon());

        // Should be on boundary (within rounding)
        assertTrue(zone.contains(boundary, 0));
    }

    @Test
    void circle_pointOutside() {
        GeoPoint centre = new GeoPoint(12.935, 77.610);
        CircleGeofence zone = new CircleGeofence(1, "Z1", centre, 300.0, null, Set.of());

        // Point ~500 m north
        double dLat = GeoUtils.metresToDegreesLat(500.0);
        GeoPoint outside = new GeoPoint(centre.lat() + dLat, centre.lon());

        assertFalse(zone.contains(outside, 0));
    }

    @Test
    void circle_exitMargin_keepsPointInside() {
        GeoPoint centre = new GeoPoint(12.935, 77.610);
        CircleGeofence zone = new CircleGeofence(1, "Z1", centre, 300.0, null, Set.of());

        // Point ~310 m north: outside entry radius, inside exit margin of 15 m
        double dLat = GeoUtils.metresToDegreesLat(310.0);
        GeoPoint point = new GeoPoint(centre.lat() + dLat, centre.lon());

        assertFalse(zone.contains(point, 0));     // outside for entry
        assertTrue(zone.contains(point, 15.0));   // inside for exit (300 + 15 = 315 m)
    }

    @Test
    void circle_boundingBox() {
        GeoPoint centre = new GeoPoint(12.935, 77.610);
        CircleGeofence zone = new CircleGeofence(1, "Z1", centre, 300.0, null, Set.of());

        BoundingBox bb = zone.boundingBox(0);
        assertTrue(bb.minLat() < centre.lat());
        assertTrue(bb.maxLat() > centre.lat());
        assertTrue(bb.minLon() < centre.lon());
        assertTrue(bb.maxLon() > centre.lon());
    }

    @Test
    void circle_vehicleFilter() {
        CircleGeofence zone = new CircleGeofence(1, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of(10L, 20L));

        assertTrue(zone.appliesTo(10L));
        assertFalse(zone.appliesTo(30L));
    }

    @Test
    void circle_emptyVehicleFilter_appliesToAll() {
        CircleGeofence zone = new CircleGeofence(1, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());

        assertTrue(zone.appliesTo(999L));
    }

    // ---- Rectangle ----

    @Test
    void rectangle_pointInside() {
        RectangleGeofence zone = new RectangleGeofence(2, "R1",
                12.970, 12.973, 77.607, 77.612, null, Set.of());

        assertTrue(zone.contains(new GeoPoint(12.971, 77.610), 0));
    }

    @Test
    void rectangle_pointOnCorner() {
        RectangleGeofence zone = new RectangleGeofence(2, "R1",
                12.970, 12.973, 77.607, 77.612, null, Set.of());

        // Min corner
        assertTrue(zone.contains(new GeoPoint(12.970, 77.607), 0));
        // Max corner
        assertTrue(zone.contains(new GeoPoint(12.973, 77.612), 0));
    }

    @Test
    void rectangle_pointOutside() {
        RectangleGeofence zone = new RectangleGeofence(2, "R1",
                12.970, 12.973, 77.607, 77.612, null, Set.of());

        assertFalse(zone.contains(new GeoPoint(12.960, 77.610), 0)); // south
        assertFalse(zone.contains(new GeoPoint(12.971, 77.620), 0)); // east
    }

    @Test
    void rectangle_exitMargin() {
        RectangleGeofence zone = new RectangleGeofence(2, "R1",
                12.970, 12.973, 77.607, 77.612, null, Set.of());

        // Point just south of minLat
        GeoPoint south = new GeoPoint(12.9699, 77.610);
        assertFalse(zone.contains(south, 0));    // outside for entry
        assertTrue(zone.contains(south, 200));   // inside with margin (200 m expands ~0.0018 deg)
    }

    @Test
    void rectangle_boundingBox() {
        RectangleGeofence zone = new RectangleGeofence(2, "R1",
                12.970, 12.973, 77.607, 77.612, null, Set.of());

        BoundingBox bb = zone.boundingBox(10);
        assertTrue(bb.minLat() < 12.970);
        assertTrue(bb.maxLat() > 12.973);
    }
}
