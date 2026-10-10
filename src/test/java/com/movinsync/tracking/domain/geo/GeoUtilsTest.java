package com.movinsync.tracking.domain.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GeoUtils: haversine, metres-to-degrees, and projection.
 */
class GeoUtilsTest {

    @Test
    void haversine_samePoint_isZero() {
        assertEquals(0.0, GeoUtils.haversineMetres(12.9, 77.6, 12.9, 77.6), 0.01);
    }

    @Test
    void haversine_knownDistance() {
        // Bangalore (12.9716, 77.5946) to Chennai (13.0827, 80.2707) ≈ 290 km
        double d = GeoUtils.haversineMetres(12.9716, 77.5946, 13.0827, 80.2707);
        assertEquals(290_000, d, 5000); // within 5 km tolerance
    }

    @Test
    void haversine_shortDistance() {
        // ~111 m for 0.001 degrees of latitude at equator
        double d = GeoUtils.haversineMetres(0.0, 0.0, 0.001, 0.0);
        assertEquals(111.0, d, 2.0);
    }

    @Test
    void haversine_geoPointOverload() {
        GeoPoint p1 = new GeoPoint(12.9, 77.6);
        GeoPoint p2 = new GeoPoint(12.9, 77.6);
        assertEquals(0.0, GeoUtils.haversineMetres(p1, p2), 0.01);
    }

    @Test
    void metresToDegreesLat() {
        // 111,000 m ≈ 1 degree of latitude
        double deg = GeoUtils.metresToDegreesLat(111_000);
        assertEquals(1.0, deg, 0.01);
    }

    @Test
    void metresToDegreesLon_atEquator() {
        double deg = GeoUtils.metresToDegreesLon(111_000, 0.0);
        assertEquals(1.0, deg, 0.01);
    }

    @Test
    void metresToDegreesLon_atHighLatitude() {
        // At 60°, 1 degree of longitude ≈ 55.5 km
        double deg = GeoUtils.metresToDegreesLon(55_500, 60.0);
        assertEquals(1.0, deg, 0.02);
    }

    @Test
    void projectOntoSegment_midpoint() {
        GeoPoint p1 = new GeoPoint(0.0, 0.0);
        GeoPoint p2 = new GeoPoint(0.0, 0.01);
        GeoPoint point = new GeoPoint(0.001, 0.005);

        GeoUtils.ProjectionResult result = GeoUtils.projectOntoSegment(point, p1, p2);
        assertEquals(0.5, result.t(), 0.01);
        assertEquals(0.005, result.projected().lon(), 0.0001);
    }

    @Test
    void projectOntoSegment_beforeStart() {
        GeoPoint p1 = new GeoPoint(0.0, 0.01);
        GeoPoint p2 = new GeoPoint(0.0, 0.02);
        GeoPoint point = new GeoPoint(0.0, 0.0);

        GeoUtils.ProjectionResult result = GeoUtils.projectOntoSegment(point, p1, p2);
        assertEquals(0.0, result.t(), 0.01);
    }

    @Test
    void projectOntoSegment_afterEnd() {
        GeoPoint p1 = new GeoPoint(0.0, 0.0);
        GeoPoint p2 = new GeoPoint(0.0, 0.01);
        GeoPoint point = new GeoPoint(0.0, 0.05);

        GeoUtils.ProjectionResult result = GeoUtils.projectOntoSegment(point, p1, p2);
        assertEquals(1.0, result.t(), 0.01);
    }

    @Test
    void projectOntoSegment_degenerateSegment() {
        GeoPoint p1 = new GeoPoint(1.0, 1.0);
        GeoPoint point = new GeoPoint(1.001, 1.001);

        GeoUtils.ProjectionResult result = GeoUtils.projectOntoSegment(point, p1, p1);
        assertEquals(0.0, result.t(), 0.01);
    }
}
