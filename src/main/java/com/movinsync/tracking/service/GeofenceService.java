package com.movinsync.tracking.service;

import com.movinsync.tracking.domain.geo.*;
import com.movinsync.tracking.entity.GeofenceEntity;
import com.movinsync.tracking.entity.GeofenceShape;
import com.movinsync.tracking.repository.GeofenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages geofence zone definitions. Loads from DB, converts to domain objects,
 * and provides lookup for the engine. Will be backed by Caffeine cache in step 10.
 */
@Service
public class GeofenceService {

    private static final Logger log = LoggerFactory.getLogger(GeofenceService.class);

    private final GeofenceRepository geofenceRepository;
    private final GridIndex gridIndex;
    private volatile Map<Long, Geofence> zones = new HashMap<>();

    public GeofenceService(GeofenceRepository geofenceRepository, GridIndex gridIndex) {
        this.geofenceRepository = geofenceRepository;
        this.gridIndex = gridIndex;
        reloadZones();
    }

    /**
     * Reload all zones from the database.
     */
    public void reloadZones() {
        List<GeofenceEntity> entities = geofenceRepository.findAll();
        Map<Long, Geofence> newZones = new HashMap<>();
        for (GeofenceEntity entity : entities) {
            Geofence g = toDomain(entity);
            if (g != null) {
                newZones.put(g.getId(), g);
            }
        }
        this.zones = newZones;
        gridIndex.rebuild(new ArrayList<>(newZones.values()));
        log.info("Loaded {} geofence zones, grid rebuilt", newZones.size());
    }

    /**
     * Get all zones.
     */
    public List<Geofence> getAllZones() {
        return new ArrayList<>(zones.values());
    }

    /**
     * Grid-based candidate lookup: O(1) per point.
     */
    public List<Geofence> candidates(GeoPoint point) {
        return gridIndex.candidates(point);
    }

    /**
     * Get a zone by ID.
     */
    public Optional<Geofence> getZone(long zoneId) {
        return Optional.ofNullable(zones.get(zoneId));
    }

    /**
     * Convert a DB entity to a domain Geofence object.
     */
    private Geofence toDomain(GeofenceEntity entity) {
        TimeWindow tw = null;
        if (entity.getWindowStart() != null && entity.getWindowEnd() != null
                && entity.getWindowTimezone() != null) {
            tw = new TimeWindow(
                    entity.getWindowStart(),
                    entity.getWindowEnd(),
                    ZoneId.of(entity.getWindowTimezone()));
        }

        Set<Long> allowedVehicles = parseAllowedVehicles(entity.getAllowedVehicleIds());

        if (entity.getShape() == GeofenceShape.CIRCLE) {
            return new CircleGeofence(
                    entity.getId(), entity.getName(),
                    new GeoPoint(entity.getCentreLat(), entity.getCentreLon()),
                    entity.getRadiusMetres(),
                    tw, allowedVehicles);
        } else if (entity.getShape() == GeofenceShape.RECTANGLE) {
            double margin = entity.getMarginMetres() != null ? entity.getMarginMetres() : 0.0;
            return new RectangleGeofence(
                    entity.getId(), entity.getName(),
                    entity.getMinLat(), entity.getMaxLat(),
                    entity.getMinLon(), entity.getMaxLon(), margin,
                    tw, allowedVehicles);
        }

        log.warn("Unknown geofence shape: {}", entity.getShape());
        return null;
    }

    private Set<Long> parseAllowedVehicles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }
}
