package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.geo.*;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.domain.state.ZoneTransition;
import com.movinsync.tracking.entity.GeofenceEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeofenceEngine: entry/exit transitions, hysteresis,
 * time windows, vehicle filter, silent first ping.
 */
class GeofenceEngineTest {

    private GeofenceEngine engine;

    // Zone at 12.935, 77.610 with radius 300m
    private CircleGeofence zone;

    // Points
    private static final GeoPoint INSIDE = new GeoPoint(12.935, 77.610);
    private static final GeoPoint OUTSIDE = new GeoPoint(12.940, 77.620); // ~1.2km away

    @BeforeEach
    void setUp() {
        TrackingProperties props = new TrackingProperties(
                30_000, 300_000, 120_000, 2.0, 1.2, 0.3, 15.0, 64);
        engine = new GeofenceEngine(props);

        zone = new CircleGeofence(1L, "TestZone", new GeoPoint(12.935, 77.610),
                300.0, null, Set.of());
    }

    private VehicleState newState(long vehicleId, boolean isNew) {
        VehicleState s = new VehicleState(vehicleId);
        if (!isNew) s.setNewVehicle(false);
        return s;
    }

    @Test
    void silentFirstPing_noTransitions() {
        VehicleState state = newState(1, true); // new vehicle
        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 1000L, 1, state, List.of(zone), false);

        assertTrue(result.transitions().isEmpty(), "First ping should be silent");
        assertTrue(result.newZoneStates().get(1L), "Zone state should be set to INSIDE");
    }

    @Test
    void silentFirstPing_outside_noTransitions() {
        VehicleState state = newState(1, true);
        GeofenceEngine.EvaluationResult result = engine.evaluate(
                OUTSIDE, 1000L, 1, state, List.of(zone), false);

        assertTrue(result.transitions().isEmpty());
        assertFalse(result.newZoneStates().get(1L));
    }

    @Test
    void entry_outsideToInside() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, false); // was outside

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 2000L, 1, state, List.of(zone), false);

        assertEquals(1, result.transitions().size());
        ZoneTransition t = result.transitions().get(0);
        assertEquals(GeofenceEventType.ENTRY, t.type());
        assertEquals(1L, t.zoneId());
        assertFalse(t.detectedAfterGap());
    }

    @Test
    void exit_insideToOutside() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, true); // was inside

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                OUTSIDE, 3000L, 1, state, List.of(zone), false);

        assertEquals(1, result.transitions().size());
        ZoneTransition t = result.transitions().get(0);
        assertEquals(GeofenceEventType.EXIT, t.type());
        assertEquals(1L, t.zoneId());
    }

    @Test
    void noTransition_staysInside() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, true); // was inside

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 4000L, 1, state, List.of(zone), false);

        assertTrue(result.transitions().isEmpty());
        assertTrue(result.newZoneStates().get(1L));
    }

    @Test
    void noTransition_staysOutside() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, false);

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                OUTSIDE, 5000L, 1, state, List.of(zone), false);

        assertTrue(result.transitions().isEmpty());
        assertFalse(result.newZoneStates().get(1L));
    }

    @Test
    void hysteresis_pointInExitMargin_staysInside() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, true); // was inside

        // Point ~305m from centre (outside entry radius 300m, but within hysteresis)
        // Hysteresis for circle: max(10m, 5% of 300) = 15m → exit at 315m
        double dLat = GeoUtils.metresToDegreesLat(305.0);
        GeoPoint inMargin = new GeoPoint(12.935 + dLat, 77.610);

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                inMargin, 6000L, 1, state, List.of(zone), false);

        // Should NOT fire an EXIT because point is within hysteresis margin
        assertTrue(result.transitions().isEmpty());
        assertTrue(result.newZoneStates().get(1L));
    }

    @Test
    void hysteresis_pointBeyondMargin_exits() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, true);

        // Point ~320m from centre (beyond 315m hysteresis)
        double dLat = GeoUtils.metresToDegreesLat(320.0);
        GeoPoint beyondMargin = new GeoPoint(12.935 + dLat, 77.610);

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                beyondMargin, 7000L, 1, state, List.of(zone), false);

        assertEquals(1, result.transitions().size());
        assertEquals(GeofenceEventType.EXIT, result.transitions().get(0).type());
    }

    @Test
    void vehicleFilter_filteredOut() {
        // Zone only for vehicle 99
        CircleGeofence filteredZone = new CircleGeofence(2L, "Filtered",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of(99L));

        VehicleState state = newState(1, false);
        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 8000L, 1, state, List.of(filteredZone), false);

        assertTrue(result.transitions().isEmpty());
        assertFalse(result.newZoneStates().containsKey(2L));
    }

    @Test
    void vehicleFilter_allowed() {
        CircleGeofence filteredZone = new CircleGeofence(2L, "AllowedForVehicle1",
                new GeoPoint(12.935, 77.610), 300.0, null, Set.of(1L));

        VehicleState state = newState(1, false);
        state.setZoneState(2L, false);

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 9000L, 1, state, List.of(filteredZone), false);

        assertEquals(1, result.transitions().size());
        assertEquals(GeofenceEventType.ENTRY, result.transitions().get(0).type());
    }

    @Test
    void timeWindow_activeWindow_firesEvent() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), ist);
        CircleGeofence windowedZone = new CircleGeofence(3L, "Windowed",
                new GeoPoint(12.935, 77.610), 300.0, tw, Set.of());

        VehicleState state = newState(1, false);
        state.setZoneState(3L, false);

        // 10:00 IST → active
        long ts = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, ist)
                .toInstant().toEpochMilli();

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, ts, 1, state, List.of(windowedZone), false);

        assertEquals(1, result.transitions().size());
    }

    @Test
    void timeWindow_inactiveWindow_noEvent_butTracksOccupancy() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), ist);
        CircleGeofence windowedZone = new CircleGeofence(3L, "Windowed",
                new GeoPoint(12.935, 77.610), 300.0, tw, Set.of());

        VehicleState state = newState(1, false);
        state.setZoneState(3L, false);

        // 20:00 IST → inactive
        long ts = ZonedDateTime.of(2026, 10, 10, 20, 0, 0, 0, ist)
                .toInstant().toEpochMilli();

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, ts, 1, state, List.of(windowedZone), false);

        // No event fired (outside time window), but occupancy IS tracked
        assertTrue(result.transitions().isEmpty());
        assertTrue(result.newZoneStates().get(3L), "Occupancy should still be tracked");
    }

    @Test
    void gapFlag_propagatedToTransition() {
        VehicleState state = newState(1, false);
        state.setZoneState(1L, false);

        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 10000L, 1, state, List.of(zone), true); // gap=true

        assertEquals(1, result.transitions().size());
        assertTrue(result.transitions().get(0).detectedAfterGap());
    }

    @Test
    void multipleZones_independentEvaluation() {
        CircleGeofence zone2 = new CircleGeofence(2L, "Zone2",
                new GeoPoint(12.940, 77.620), 300.0, null, Set.of());

        VehicleState state = newState(1, false);
        state.setZoneState(1L, false);
        state.setZoneState(2L, true);

        // INSIDE is inside zone1 but outside zone2
        GeofenceEngine.EvaluationResult result = engine.evaluate(
                INSIDE, 11000L, 1, state, List.of(zone, zone2), false);

        assertEquals(2, result.transitions().size());

        ZoneTransition entry = result.transitions().stream()
                .filter(t -> t.type() == GeofenceEventType.ENTRY).findFirst().orElseThrow();
        assertEquals(1L, entry.zoneId());

        ZoneTransition exit = result.transitions().stream()
                .filter(t -> t.type() == GeofenceEventType.EXIT).findFirst().orElseThrow();
        assertEquals(2L, exit.zoneId());
    }

    @Test
    void rectangleZone_entryAndExit() {
        RectangleGeofence rect = new RectangleGeofence(10L, "Rect",
                12.930, 12.940, 77.605, 77.615, null, Set.of());

        VehicleState state = newState(1, false);
        state.setZoneState(10L, false);

        // INSIDE is within rect bounds
        GeofenceEngine.EvaluationResult entryResult = engine.evaluate(
                INSIDE, 12000L, 1, state, List.of(rect), false);
        assertEquals(1, entryResult.transitions().size());
        assertEquals(GeofenceEventType.ENTRY, entryResult.transitions().get(0).type());

        // Apply state
        state.getZoneStates().putAll(entryResult.newZoneStates());

        // Exit
        GeofenceEngine.EvaluationResult exitResult = engine.evaluate(
                OUTSIDE, 13000L, 1, state, List.of(rect), false);
        assertEquals(1, exitResult.transitions().size());
        assertEquals(GeofenceEventType.EXIT, exitResult.transitions().get(0).type());
    }
}
