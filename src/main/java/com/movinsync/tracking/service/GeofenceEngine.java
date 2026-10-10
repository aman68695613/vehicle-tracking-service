package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.geo.Geofence;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.domain.state.ZoneTransition;
import com.movinsync.tracking.entity.GeofenceEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Geofence state machine. For each candidate zone, determines whether the vehicle
 * has entered or exited, applying hysteresis, time windows, vehicle filters,
 * and silent-first-ping logic.
 * <p>
 * The engine returns a list of transitions — it does NOT mutate VehicleState.
 * The caller is responsible for persisting and committing state changes.
 */
@Service
public class GeofenceEngine {

    private static final Logger log = LoggerFactory.getLogger(GeofenceEngine.class);

    private final double defaultExitMarginMetres;

    public GeofenceEngine(TrackingProperties props) {
        // Hysteresis: exit at entryRadius + max(10m, 5%). Configurable; 0 in tests.
        // We store the default margin; per-zone margin overrides it (set to 0 in tests).
        this.defaultExitMarginMetres = 0; // default for now; per-zone margin used
    }

    /**
     * Evaluate all candidate zones for a vehicle at the given ping position.
     *
     * @param ping        the current position
     * @param pingTs      the ping timestamp (epoch ms)
     * @param vehicleId   the vehicle ID
     * @param state       the vehicle's current in-memory state (read-only here)
     * @param candidates  zones to evaluate (from grid lookup ∪ insideZones)
     * @param gap         true if a gap was detected before this ping
     * @return list of transitions (may be empty); also updates zone occupancy in newZoneStates
     */
    public EvaluationResult evaluate(GeoPoint ping, long pingTs, long vehicleId,
                                      VehicleState state, Collection<Geofence> candidates,
                                      boolean gap) {
        List<ZoneTransition> transitions = new ArrayList<>();
        Map<Long, Boolean> newZoneStates = new HashMap<>(state.getZoneStates());

        Instant pingInstant = Instant.ofEpochMilli(pingTs);

        for (Geofence zone : candidates) {
            // Vehicle filter
            if (!zone.appliesTo(vehicleId)) {
                continue;
            }

            boolean wasInside = Boolean.TRUE.equals(state.getZoneStates().get(zone.getId()));
            double exitMargin = computeExitMargin(zone);

            // Current position check: entry uses 0 margin, exit uses hysteresis margin
            boolean isNowInside;
            if (wasInside) {
                // Already inside → check with exit margin (hysteresis)
                isNowInside = zone.contains(ping, exitMargin);
            } else {
                // Outside → check with entry radius (0 margin)
                isNowInside = zone.contains(ping, 0);
            }

            // Silent first ping: new vehicle → set state, no event
            if (state.isNewVehicle()) {
                newZoneStates.put(zone.getId(), isNowInside);
                continue;
            }

            // Always track occupancy
            newZoneStates.put(zone.getId(), isNowInside);

            // State change → potential event
            if (wasInside != isNowInside) {
                // Check time window: occupancy is always tracked, but events fire
                // only if the zone is active at the ping's timestamp
                if (zone.isActiveAt(pingInstant)) {
                    GeofenceEventType eventType = isNowInside
                            ? GeofenceEventType.ENTRY
                            : GeofenceEventType.EXIT;
                    transitions.add(new ZoneTransition(zone.getId(), eventType, gap));
                    log.info("Geofence {}: vehicle={} zone={} at ts={}{}",
                            eventType, vehicleId, zone.getName(), pingTs,
                            gap ? " (after gap)" : "");
                }
            }
        }

        return new EvaluationResult(transitions, newZoneStates);
    }

    /**
     * Compute exit margin for hysteresis.
     * Circle: max(10m, 5% of radius). Rectangle: uses the zone's marginMetres.
     * For testing, zones can set margin to 0.
     */
    private double computeExitMargin(Geofence zone) {
        if (zone instanceof com.movinsync.tracking.domain.geo.CircleGeofence circle) {
            double fivePercent = circle.getRadiusMetres() * 0.05;
            return Math.max(10.0, fivePercent);
        }
        if (zone instanceof com.movinsync.tracking.domain.geo.RectangleGeofence rect) {
            return rect.getMarginMetres();
        }
        return 0;
    }

    /**
     * Result of evaluating all zones for a ping.
     */
    public record EvaluationResult(
            List<ZoneTransition> transitions,
            Map<Long, Boolean> newZoneStates
    ) {}
}
