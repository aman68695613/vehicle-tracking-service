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

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    
    private final com.movinsync.tracking.repository.RouteRepository routeRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public RouteController(com.movinsync.tracking.repository.RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> getRoutes() {
        return ResponseEntity.ok(routeRepository.findAll());
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
            
            return ResponseEntity.ok(routeRepository.save(route));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
