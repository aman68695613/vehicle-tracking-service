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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.util.concurrent.ConcurrentHashMap<Long, List<PolylinePoint>> polylineCache = new java.util.concurrent.ConcurrentHashMap<>();

    public EtaService(TrackingProperties props, EtaStrategy etaStrategy) {
        this.props = props;
        this.etaStrategy = etaStrategy;
    }

    public record EtaUpdateResult(Map<Long, VehicleState.EtaEntry> etaMap, int snappedIndex) {}

    /**
     * Update ETAs for all remaining stops on the vehicle's active route.
     * Returns a new EtaUpdateResult, DOES NOT mutate state.
     */
    public EtaUpdateResult updateEta(GeoPoint position, VehicleState state, Route route, boolean gap) {
        if (route == null || route.getStops() == null || route.getStops().isEmpty()) {
            return new EtaUpdateResult(new java.util.HashMap<>(state.getEtaMap()), state.getLastSnappedIndex());
        }

        List<RouteStop> stops = route.getStops().stream()
                .sorted(Comparator.comparingInt(RouteStop::getSequenceIndex))
                .toList();

        if (stops.size() < 2) {
            return new EtaUpdateResult(new java.util.HashMap<>(state.getEtaMap()), state.getLastSnappedIndex());
        }

        // 1. Parse polyline and snap to route (cached)
        List<PolylinePoint> polyline = polylineCache.computeIfAbsent(route.getId(), 
                id -> parsePolyline(route.getPolylineJson()));
                
        if (polyline.size() < 2) {
            return new EtaUpdateResult(new java.util.HashMap<>(state.getEtaMap()), state.getLastSnappedIndex());
        }

        SnapResult snap = snapToRoute(position, polyline, state.getLastSnappedIndex());
        int newSnappedIndex = snap.segmentIndex();

        // 2. Compute effective speed
        double effectiveSpeed = Math.max(state.getSpeedEma(), props.minSpeedMs());

        // 3. For each remaining stop, compute ETA
        Map<Long, VehicleState.EtaEntry> newEtaMap = new LinkedHashMap<>();

        for (RouteStop stop : stops) {
            double remaining = stop.getCumulativeDistanceMetres() - snap.cumulativeDistance();

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

        return new EtaUpdateResult(newEtaMap, newSnappedIndex);
    }

    /**
     * Snap a point to the route polyline.
     * Searches a window around the last known index, falls back to full scan.
     */
    SnapResult snapToRoute(GeoPoint point, List<PolylinePoint> polyline, int lastIndex) {
        int n = polyline.size();
        double bestDist = Double.MAX_VALUE;
        int bestSeg = 0;
        double bestCumDist = 0;

        // Window search: ±10 segments around lastIndex
        int windowStart = Math.max(0, lastIndex - 10);
        int windowEnd = Math.min(n - 2, lastIndex + 10);

        for (int i = windowStart; i <= windowEnd; i++) {
            SegmentSnapResult ssr = snapToSegment(point, polyline, i);
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
                SegmentSnapResult ssr = snapToSegment(point, polyline, i);
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
     * Snap to a single segment between polyline[i] and polyline[i+1].
     */
    private SegmentSnapResult snapToSegment(GeoPoint point, List<PolylinePoint> polyline, int i) {
        PolylinePoint p1 = polyline.get(i);
        PolylinePoint p2 = polyline.get(i + 1);

        GeoUtils.ProjectionResult proj = GeoUtils.projectOntoSegment(point, p1.point(), p2.point());

        double segLength = p2.cumulativeDistance() - p1.cumulativeDistance();
        double cumDist = p1.cumulativeDistance() + proj.t() * segLength;

        return new SegmentSnapResult(proj.distanceMetres(), cumDist);
    }

    private List<PolylinePoint> parsePolyline(String json) {
        List<PolylinePoint> points = new ArrayList<>();
        if (json == null || json.isBlank()) return points;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            double[][] coords = mapper.readValue(json, double[][].class);
            double cumDist = 0;
            for (int i = 0; i < coords.length; i++) {
                GeoPoint pt = new GeoPoint(coords[i][0], coords[i][1]);
                if (i > 0) {
                    cumDist += GeoUtils.haversineMetres(points.get(i - 1).point(), pt);
                }
                points.add(new PolylinePoint(pt, cumDist));
            }
        } catch (Exception e) {
            log.error("Failed to parse polyline", e);
        }
        return points;
    }

    record PolylinePoint(GeoPoint point, double cumulativeDistance) {}
    record SnapResult(int segmentIndex, double cumulativeDistance, double distanceToRoute) {}
    record SegmentSnapResult(double distance, double cumulativeDistance) {}

    public void clearPolylineCache(Long routeId) {
        polylineCache.remove(routeId);
    }
}
