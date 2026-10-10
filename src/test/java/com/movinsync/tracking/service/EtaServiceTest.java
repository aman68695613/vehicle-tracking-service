package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.entity.Route;
import com.movinsync.tracking.entity.RouteStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EtaService: snapping, per-stop ETA, publish threshold, passed stops.
 */
class EtaServiceTest {

    private EtaService etaService;
    private TrackingProperties props;

    // Route: 4 stops in a line going north
    // Stop 0: (12.930, 77.610) cumDist=0
    // Stop 1: (12.935, 77.610) cumDist=556    (~556m north)
    // Stop 2: (12.940, 77.610) cumDist=1112   (~556m further north)
    // Stop 3: (12.945, 77.610) cumDist=1668   (~556m further north)
    private Route route;

    @BeforeEach
    void setUp() {
        props = new TrackingProperties(
                30_000, 300_000, 120_000,
                2.0,    // minSpeedMs
                1.2,    // trafficFactor
                0.3, 15.0, 64);
        etaService = new EtaService(props, new DefaultEtaStrategy());

        route = new Route();
        route.setId(1L);
        route.setPolylineJson("[[12.930, 77.610], [12.935, 77.610], [12.940, 77.610], [12.945, 77.610]]");
        List<RouteStop> stops = new ArrayList<>();
        stops.add(makeStop(1L, 0, 12.930, 77.610, 0.0));
        stops.add(makeStop(2L, 1, 12.935, 77.610, 556.0));
        stops.add(makeStop(3L, 2, 12.940, 77.610, 1112.0));
        stops.add(makeStop(4L, 3, 12.945, 77.610, 1668.0));
        route.setStops(stops);
    }

    private RouteStop makeStop(long id, int seq, double lat, double lon, double cumDist) {
        RouteStop s = new RouteStop();
        s.setId(id);
        s.setSequenceIndex(seq);
        s.setName("Stop-" + seq);
        s.setLatitude(lat);
        s.setLongitude(lon);
        s.setCumulativeDistanceMetres(cumDist);
        s.setRoute(route);
        return s;
    }

    private List<EtaService.PolylinePoint> toPolylinePoints(List<RouteStop> stops) {
        return stops.stream()
                .map(s -> new EtaService.PolylinePoint(new GeoPoint(s.getLatitude(), s.getLongitude()), s.getCumulativeDistanceMetres()))
                .toList();
    }

    @Test
    void snapToRoute_atFirstStop() {
        List<EtaService.PolylinePoint> points = toPolylinePoints(route.getStops());
        EtaService.SnapResult snap = etaService.snapToRoute(
                new GeoPoint(12.930, 77.610), points, 0);
        assertEquals(0, snap.segmentIndex());
        assertEquals(0.0, snap.cumulativeDistance(), 1.0);
    }

    @Test
    void snapToRoute_midway() {
        List<EtaService.PolylinePoint> points = toPolylinePoints(route.getStops());
        // Point midway between stop 0 and stop 1
        EtaService.SnapResult snap = etaService.snapToRoute(
                new GeoPoint(12.9325, 77.610), points, 0);
        assertEquals(0, snap.segmentIndex());
        assertEquals(278.0, snap.cumulativeDistance(), 10.0); // ~halfway of 556
    }

    @Test
    void snapToRoute_atLastStop() {
        List<EtaService.PolylinePoint> points = toPolylinePoints(route.getStops());
        EtaService.SnapResult snap = etaService.snapToRoute(
                new GeoPoint(12.945, 77.610), points, 0);
        assertEquals(1668.0, snap.cumulativeDistance(), 10.0);
    }

    @Test
    void snapToRoute_offRoute() {
        List<EtaService.PolylinePoint> points = toPolylinePoints(route.getStops());
        // Point 1km east of the route
        EtaService.SnapResult snap = etaService.snapToRoute(
                new GeoPoint(12.935, 77.620), points, 0);
        // Should still snap to nearest segment
        assertTrue(snap.distanceToRoute() > 500);
        assertEquals(556.0, snap.cumulativeDistance(), 20.0);
    }

    @Test
    void updateEta_computesForAllRemainingStops() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(10.0); // 10 m/s

        // Vehicle at stop 0
        var res = etaService.updateEta(new GeoPoint(12.930, 77.610), state, route, false);
        state.getEtaMap().clear();
        state.getEtaMap().putAll(res.etaMap());
        state.setLastSnappedIndex(res.snappedIndex());

        // All stops should have ETAs
        assertEquals(4, state.getEtaMap().size());

        // Stop 0 should be passed or have 0 ETA (vehicle is at it)
        VehicleState.EtaEntry e0 = state.getEtaMap().get(1L);
        assertNotNull(e0);

        // Stop 3 should have ETA = 1668m / 10m/s * 1.2 ≈ 200s
        VehicleState.EtaEntry e3 = state.getEtaMap().get(4L);
        assertNotNull(e3);
        assertEquals(VehicleState.EtaStatus.PENDING, e3.status());
        assertTrue(e3.etaSeconds() > 150 && e3.etaSeconds() < 250);
    }

    @Test
    void updateEta_passedStopsMarkedPassed() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(10.0);

        // Vehicle past stop 1 (between stop 1 and stop 2)
        var res2 = etaService.updateEta(new GeoPoint(12.937, 77.610), state, route, false);
        state.getEtaMap().clear();
        state.getEtaMap().putAll(res2.etaMap());
        state.setLastSnappedIndex(res2.snappedIndex());

        // Stop 0 (cumDist=0) should be PASSED
        VehicleState.EtaEntry e0 = state.getEtaMap().get(1L);
        assertEquals(VehicleState.EtaStatus.PASSED, e0.status());

        // Stop 1 (cumDist=556) should be PASSED (vehicle is past it)
        VehicleState.EtaEntry e1 = state.getEtaMap().get(2L);
        assertEquals(VehicleState.EtaStatus.PASSED, e1.status());

        // Stop 2 (cumDist=1112) should be PENDING
        VehicleState.EtaEntry e2 = state.getEtaMap().get(3L);
        assertEquals(VehicleState.EtaStatus.PENDING, e2.status());
    }

    @Test
    void updateEta_stoppedVehicle_usesSpeedFloor() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(0.5); // very slow, below floor

        var res = etaService.updateEta(new GeoPoint(12.930, 77.610), state, route, false);
        state.getEtaMap().clear();
        state.getEtaMap().putAll(res.etaMap());
        state.setLastSnappedIndex(res.snappedIndex());

        // ETA should use minSpeed (2.0 m/s) not 0.5
        VehicleState.EtaEntry e3 = state.getEtaMap().get(4L);
        // ETA = 1668 / 2.0 * 1.2 = 1000.8s
        assertEquals(1000.8, e3.etaSeconds(), 10.0);
    }

    @Test
    void updateEta_publishThreshold_suppressesSmallChanges() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(10.0);

        // First update: publishes
        var res = etaService.updateEta(new GeoPoint(12.930, 77.610), state, route, false);
        state.getEtaMap().clear();
        state.getEtaMap().putAll(res.etaMap());
        state.setLastSnappedIndex(res.snappedIndex());
        double firstEta = state.getEtaMap().get(4L).lastPublishedEtaSeconds();

        // Second update with very similar position (tiny change)
        var res3 = etaService.updateEta(new GeoPoint(12.9301, 77.610), state, route, false);
        state.getEtaMap().clear();
        state.getEtaMap().putAll(res3.etaMap());
        state.setLastSnappedIndex(res3.snappedIndex());
        double secondPublished = state.getEtaMap().get(4L).lastPublishedEtaSeconds();

        // Change is < 15s so published value should stay the same
        assertEquals(firstEta, secondPublished, 0.01);
    }

    @Test
    void updateEta_nullRoute_noOp() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(10.0);

        // Should not throw
        etaService.updateEta(new GeoPoint(12.930, 77.610), state, null, false);
        assertTrue(state.getEtaMap().isEmpty());
    }

    @Test
    void updateEta_windowedSnapSearch() {
        VehicleState state = new VehicleState(1L);
        state.setSpeedEma(10.0);
        state.setLastSnappedIndex(2); // start near end of route

        // Vehicle near stop 2
        etaService.updateEta(new GeoPoint(12.940, 77.610), state, route, false);

        // Should find a segment near index 2 (could be 1 or 2 since point is on the boundary)
        assertTrue(state.getLastSnappedIndex() >= 1 && state.getLastSnappedIndex() <= 2);
    }
}
