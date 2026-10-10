package com.movinsync.tracking.domain.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GridIndex.
 */
class GridIndexTest {

    private GridIndex grid;

    @BeforeEach
    void setUp() {
        grid = new GridIndex(0.01); // ~1km cells
    }

    @Test
    void emptyGrid_noCandidates() {
        grid.rebuild(List.of());
        List<Geofence> result = grid.candidates(new GeoPoint(12.935, 77.610));
        assertTrue(result.isEmpty());
    }

    @Test
    void singleCircle_pointInside_returnsCandidate() {
        CircleGeofence zone = new CircleGeofence(1L, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());
        grid.rebuild(List.of(zone));

        List<Geofence> result = grid.candidates(new GeoPoint(12.935, 77.610));
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void singleCircle_pointFarAway_noCandidates() {
        CircleGeofence zone = new CircleGeofence(1L, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());
        grid.rebuild(List.of(zone));

        // Far away point
        List<Geofence> result = grid.candidates(new GeoPoint(28.6, 77.2));
        assertTrue(result.isEmpty());
    }

    @Test
    void multipleZones_overlappingCells() {
        CircleGeofence z1 = new CircleGeofence(1L, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());
        CircleGeofence z2 = new CircleGeofence(2L, "Z2",
                new GeoPoint(12.936, 77.611), 200.0, null, Set.of());
        grid.rebuild(List.of(z1, z2));

        List<Geofence> result = grid.candidates(new GeoPoint(12.935, 77.610));
        assertTrue(result.size() >= 1); // at least z1 should be returned
    }

    @Test
    void rectangleZone_pointInside() {
        RectangleGeofence rect = new RectangleGeofence(3L, "R1",
                12.930, 12.940, 77.605, 77.615, null, Set.of());
        grid.rebuild(List.of(rect));

        List<Geofence> result = grid.candidates(new GeoPoint(12.935, 77.610));
        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getId());
    }

    @Test
    void getZone_found() {
        CircleGeofence zone = new CircleGeofence(1L, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());
        grid.rebuild(List.of(zone));

        assertTrue(grid.getZone(1L).isPresent());
        assertFalse(grid.getZone(999L).isPresent());
    }

    @Test
    void rebuild_replacesOldData() {
        CircleGeofence z1 = new CircleGeofence(1L, "Z1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of());
        grid.rebuild(List.of(z1));
        assertEquals(1, grid.allZones().size());

        // Rebuild with different zones
        CircleGeofence z2 = new CircleGeofence(2L, "Z2",
                new GeoPoint(28.6, 77.2), 500.0, null, Set.of());
        grid.rebuild(List.of(z2));
        assertEquals(1, grid.allZones().size());
        assertFalse(grid.getZone(1L).isPresent());
        assertTrue(grid.getZone(2L).isPresent());
    }

    @Test
    void largeZone_spansMultipleCells() {
        // Zone with 5km radius → should register in many cells
        CircleGeofence big = new CircleGeofence(1L, "Big",
                new GeoPoint(12.935, 77.610), 5000.0, null, Set.of());
        grid.rebuild(List.of(big));

        // Point 3km away should still find it as a candidate
        double dLat = GeoUtils.metresToDegreesLat(3000.0);
        List<Geofence> result = grid.candidates(new GeoPoint(12.935 + dLat, 77.610));
        assertEquals(1, result.size());
    }
}
