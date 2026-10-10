package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.geo.GeoUtils;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.entity.Route;
import com.movinsync.tracking.entity.RouteStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ETA service: snap vehicle position to route polyline, compute per-stop ETA.
 * <p>
 * Route is modelled as a list of stops with cumulative distances.
 * Snapping projects the current position onto segments and uses the cumulative
 * distance at the snapped point.
 */
@Service
public class EtaService {

    private static final Logger log = LoggerFactory.getLogger(EtaService.class);

    private final TrackingProperties props;
    private final EtaStrategy etaStrategy;

    public EtaService(TrackingProperties props, EtaStrategy etaStrategy) {
        this.props = props;
        this.etaStrategy = etaStrategy;
    }

    /**
     * Update ETAs for all remaining stops on the vehicle's active route.
     *
     * @param position   current ping position
     * @param state      vehicle state (modified in place: ETA map, snap index)
     * @param route      the vehicle's active route (with stops eagerly loaded)
     * @param gap        whether a gap was detected
     */
    public void updateEta(GeoPoint position, VehicleState state, Route route, boolean gap) {
        if (route == null || route.getStops() == null || route.getStops().isEmpty()) {
            return;
        }

        List<RouteStop> stops = route.getStops().stream()
                .sorted(Comparator.comparingInt(RouteStop::getSequenceIndex))
                .toList();

        if (stops.size() < 2) {
            return; // need at least 2 stops to form segments
        }

        // 1. Snap to route
        SnapResult snap = snapToRoute(position, stops, state.getLastSnappedIndex());
        state.setLastSnappedIndex(snap.segmentIndex);

        // 2. Compute effective speed
        double effectiveSpeed = Math.max(state.getSpeedEma(), props.minSpeedMs());

        // 3. For each remaining stop, compute ETA
        Map<Long, VehicleState.EtaEntry> newEtaMap = new LinkedHashMap<>();

        for (RouteStop stop : stops) {
            double remaining = stop.getCumulativeDistanceMetres() - snap.cumulativeDistance;

            if (remaining <= 0) {
                // Stop is behind the vehicle → PASSED
                newEtaMap.put(stop.getId(), new VehicleState.EtaEntry(
                        0, 0, VehicleState.EtaStatus.PASSED));
                continue;
            }

            double etaSeconds = etaStrategy.computeEtaSeconds(remaining, effectiveSpeed, props.trafficFactor());

            // Check publish threshold
            VehicleState.EtaEntry prev = state.getEtaMap().get(stop.getId());
            double lastPublished = (prev != null && prev.status() == VehicleState.EtaStatus.PENDING)
                    ? prev.lastPublishedEtaSeconds() : 0;
            double delta = Math.abs(etaSeconds - lastPublished);

            if (delta > props.etaPublishThresholdSeconds() || prev == null
                    || prev.status() == VehicleState.EtaStatus.PASSED) {
                // Publish new ETA
                newEtaMap.put(stop.getId(), new VehicleState.EtaEntry(
                        etaSeconds, etaSeconds, VehicleState.EtaStatus.PENDING));
                log.debug("ETA published: stop={} eta={}s remaining={}m speed={}m/s",
                        stop.getId(), etaSeconds, remaining, effectiveSpeed);
            } else {
                // Keep last published value
                newEtaMap.put(stop.getId(), new VehicleState.EtaEntry(
                        etaSeconds, lastPublished, VehicleState.EtaStatus.PENDING));
            }
        }

        state.getEtaMap().clear();
        state.getEtaMap().putAll(newEtaMap);
    }

    /**
     * Snap a point to the route polyline.
     * Searches a window around the last known index, falls back to full scan.
     */
    SnapResult snapToRoute(GeoPoint point, List<RouteStop> stops, int lastIndex) {
        int n = stops.size();
        double bestDist = Double.MAX_VALUE;
        int bestSeg = 0;
        double bestCumDist = 0;

        // Window search: ±3 segments around lastIndex
        int windowStart = Math.max(0, lastIndex - 3);
        int windowEnd = Math.min(n - 2, lastIndex + 3);

        for (int i = windowStart; i <= windowEnd; i++) {
            SegmentSnapResult ssr = snapToSegment(point, stops, i);
            if (ssr.distance < bestDist) {
                bestDist = ssr.distance;
                bestSeg = i;
                bestCumDist = ssr.cumulativeDistance;
            }
        }

        // If the best distance is > 500m, do a full scan (off-route fallback)
        if (bestDist > 500) {
            for (int i = 0; i < n - 1; i++) {
                if (i >= windowStart && i <= windowEnd) continue;
                SegmentSnapResult ssr = snapToSegment(point, stops, i);
                if (ssr.distance < bestDist) {
                    bestDist = ssr.distance;
                    bestSeg = i;
                    bestCumDist = ssr.cumulativeDistance;
                }
            }
        }

        return new SnapResult(bestSeg, bestCumDist, bestDist);
    }

    /**
     * Snap to a single segment between stops[i] and stops[i+1].
     */
    private SegmentSnapResult snapToSegment(GeoPoint point, List<RouteStop> stops, int i) {
        RouteStop s1 = stops.get(i);
        RouteStop s2 = stops.get(i + 1);
        GeoPoint p1 = new GeoPoint(s1.getLatitude(), s1.getLongitude());
        GeoPoint p2 = new GeoPoint(s2.getLatitude(), s2.getLongitude());

        GeoUtils.ProjectionResult proj = GeoUtils.projectOntoSegment(point, p1, p2);

        double segLength = s2.getCumulativeDistanceMetres() - s1.getCumulativeDistanceMetres();
        double cumDist = s1.getCumulativeDistanceMetres() + proj.t() * segLength;

        return new SegmentSnapResult(proj.distanceMetres(), cumDist);
    }

    record SnapResult(int segmentIndex, double cumulativeDistance, double distanceToRoute) {}
    record SegmentSnapResult(double distance, double cumulativeDistance) {}
}
