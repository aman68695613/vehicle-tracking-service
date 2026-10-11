package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.movinsync.tracking.entity.Route;
import com.movinsync.tracking.entity.RouteStop;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.geo.GeoUtils;
import java.util.List;
import java.util.ArrayList;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    
    private final com.movinsync.tracking.repository.RouteRepository routeRepository;
    private final com.movinsync.tracking.service.EtaService etaService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public RouteController(com.movinsync.tracking.repository.RouteRepository routeRepository, com.movinsync.tracking.service.EtaService etaService) {
        this.routeRepository = routeRepository;
        this.etaService = etaService;
    }

    public record RouteDTO(Long id, String name, String polylineJson, List<RouteStopDTO> stops) {}
    public record RouteStopDTO(Long id, int sequenceIndex, String name, double latitude, double longitude, double cumulativeDistanceMetres) {}

    private RouteDTO toDTO(Route r) {
        List<RouteStopDTO> stopDTOs = r.getStops() == null ? List.of() : r.getStops().stream()
            .map(s -> new RouteStopDTO(s.getId(), s.getSequenceIndex(), s.getName(), s.getLatitude(), s.getLongitude(), s.getCumulativeDistanceMetres()))
            .toList();
        return new RouteDTO(r.getId(), r.getName(), r.getPolylineJson(), stopDTOs);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getRoutes() {
        return ResponseEntity.ok(routeRepository.findAll().stream().map(this::toDTO).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> createRoute(@RequestBody Route route) {
        try {
            // Parse polyline
            List<GeoPoint> points = new ArrayList<>();
            if (route.getPolylineJson() != null && !route.getPolylineJson().isBlank()) {
                double[][] coords = mapper.readValue(route.getPolylineJson(), double[][].class);
                for (double[] coord : coords) {
                    points.add(new GeoPoint(coord[0], coord[1]));
                }
            }
            
            // Compute cumulative distance for stops
            if (!points.isEmpty() && route.getStops() != null) {
                for (RouteStop stop : route.getStops()) {
                    GeoPoint stopPt = new GeoPoint(stop.getLatitude(), stop.getLongitude());
                    double bestCumDist = 0;
                    double bestDist = Double.MAX_VALUE;
                    
                    double currentCumDist = 0;
                    for (int i = 0; i < points.size() - 1; i++) {
                        GeoPoint p1 = points.get(i);
                        GeoPoint p2 = points.get(i + 1);
                        GeoUtils.ProjectionResult proj = GeoUtils.projectOntoSegment(stopPt, p1, p2);
                        if (proj.distanceMetres() < bestDist) {
                            bestDist = proj.distanceMetres();
                            double segLen = GeoUtils.haversineMetres(p1, p2);
                            bestCumDist = currentCumDist + proj.t() * segLen;
                        }
                        currentCumDist += GeoUtils.haversineMetres(p1, p2);
                    }
                    stop.setCumulativeDistanceMetres(bestCumDist);
                    stop.setRoute(route);
                }
            }
            
            Route saved = routeRepository.save(route);
            etaService.clearPolylineCache(saved.getId());
            return ResponseEntity.ok(toDTO(saved));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
