package com.movinsync.tracking.domain.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spatial grid index for O(1) geofence candidate lookup.
 * <p>
 * Divides the world into cells of {@code cellSizeDeg × cellSizeDeg}.
 * Each zone is registered into every cell that overlaps its bounding box.
 * A point query returns the union of zones in the point's cell.
 * <p>
 * Thread-safe: rebuilt atomically on zone reload, reads via volatile reference.
 */
@Component
public class GridIndex {

    private static final Logger log = LoggerFactory.getLogger(GridIndex.class);

    /** Cell size in degrees. ~1 km at the equator. */
    private static final double DEFAULT_CELL_SIZE = 0.01;

    private final double cellSize;
    private volatile Map<Long, Set<Long>> grid; // cellKey → Set<zoneId>
    private volatile Map<Long, Geofence> zoneMap;

    public GridIndex() {
        this(DEFAULT_CELL_SIZE);
    }

    public GridIndex(double cellSize) {
        this.cellSize = cellSize;
        this.grid = new ConcurrentHashMap<>();
        this.zoneMap = new ConcurrentHashMap<>();
    }

    /**
     * Rebuild the grid from the given list of zones. Called on zone reload.
     * Atomic: builds a new grid then swaps the reference.
     */
    public void rebuild(Collection<? extends Geofence> zones) {
        Map<Long, Set<Long>> newGrid = new HashMap<>();
        Map<Long, Geofence> newZoneMap = new HashMap<>();

        for (Geofence zone : zones) {
            newZoneMap.put(zone.getId(), zone);

            BoundingBox bb = zone.boundingBox(15.0); // include hysteresis margin
            long minRow = cellRow(bb.minLat());
            long maxRow = cellRow(bb.maxLat());
            long minCol = cellCol(bb.minLon());
            long maxCol = cellCol(bb.maxLon());

            for (long row = minRow; row <= maxRow; row++) {
                for (long col = minCol; col <= maxCol; col++) {
                    long key = cellKey(row, col);
                    newGrid.computeIfAbsent(key, k -> new HashSet<>()).add(zone.getId());
                }
            }
        }

        this.grid = newGrid;
        this.zoneMap = newZoneMap;
        log.info("Grid rebuilt: {} zones in {} cells", zones.size(), newGrid.size());
    }

    /**
     * Query: return candidate zones for the given point.
     * Returns only zones registered in the point's grid cell.
     */
    public List<Geofence> candidates(GeoPoint point) {
        long row = cellRow(point.lat());
        long col = cellCol(point.lon());
        long key = cellKey(row, col);

        Set<Long> zoneIds = grid.getOrDefault(key, Set.of());
        List<Geofence> result = new ArrayList<>(zoneIds.size());
        for (Long id : zoneIds) {
            Geofence z = zoneMap.get(id);
            if (z != null) result.add(z);
        }
        return result;
    }

    /**
     * Get a zone by ID (for union with insideZones set).
     */
    public Optional<Geofence> getZone(long id) {
        return Optional.ofNullable(zoneMap.get(id));
    }

    /**
     * Return all zones in the index.
     */
    public Collection<Geofence> allZones() {
        return zoneMap.values();
    }

    private long cellRow(double lat) {
        return (long) Math.floor(lat / cellSize);
    }

    private long cellCol(double lon) {
        return (long) Math.floor(lon / cellSize);
    }

    /**
     * Pack row and col into a single long key using Cantor-like pairing.
     */
    private long cellKey(long row, long col) {
        // Shift to positive space to avoid negative pairing issues
        long r = row + 18_000; // max rows ~18000 for ±180°
        long c = col + 36_000;
        return r * 72_001L + c;
    }
}
