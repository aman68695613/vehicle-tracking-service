package com.movinsync.tracking.domain.state;

import com.movinsync.tracking.domain.geo.GeoPoint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory live state for a single vehicle.
 * Holds latest fix, last applied timestamp, zone occupancy, speed EMA, and ETA map.
 * <p>
 * Thread safety: reads and writes are done under a striped lock keyed by vehicleId.
 */
public class VehicleState {

    private final long vehicleId;
    private volatile GeoPoint lastPosition;
    private volatile long lastTimestampMs;
    private volatile double speedEma;
    private volatile int lastSnappedIndex;
    private volatile boolean newVehicle;

    /** Zone occupancy: zoneId → true if INSIDE */
    private final Map<Long, Boolean> zoneStates = new ConcurrentHashMap<>();

    /** ETA map per stop: stopId → EtaEntry */
    private final Map<Long, EtaEntry> etaMap = new ConcurrentHashMap<>();

    public VehicleState(long vehicleId) {
        this.vehicleId = vehicleId;
        this.lastTimestampMs = 0;
        this.speedEma = 0;
        this.lastSnappedIndex = 0;
        this.newVehicle = true;
    }

    public long getVehicleId() { return vehicleId; }
    public GeoPoint getLastPosition() { return lastPosition; }
    public long getLastTimestampMs() { return lastTimestampMs; }
    public double getSpeedEma() { return speedEma; }
    public int getLastSnappedIndex() { return lastSnappedIndex; }
    public boolean isNewVehicle() { return newVehicle; }
    public Map<Long, Boolean> getZoneStates() { return zoneStates; }
    public Map<Long, EtaEntry> getEtaMap() { return etaMap; }

    public void setLastPosition(GeoPoint p) { this.lastPosition = p; }
    public void setLastTimestampMs(long ts) { this.lastTimestampMs = ts; }
    public void setSpeedEma(double ema) { this.speedEma = ema; }
    public void setLastSnappedIndex(int idx) { this.lastSnappedIndex = idx; }
    public void setNewVehicle(boolean nv) { this.newVehicle = nv; }

    public boolean isInside(long zoneId) {
        return Boolean.TRUE.equals(zoneStates.get(zoneId));
    }

    public void setZoneState(long zoneId, boolean inside) {
        zoneStates.put(zoneId, inside);
    }

    /**
     * Simple ETA entry per stop.
     */
    public record EtaEntry(double etaSeconds, double lastPublishedEtaSeconds, EtaStatus status) {}

    public enum EtaStatus { PENDING, PASSED }
}
