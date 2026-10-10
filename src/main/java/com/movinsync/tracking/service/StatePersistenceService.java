package com.movinsync.tracking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.domain.state.VehicleStateStore;
import com.movinsync.tracking.entity.VehicleStateSnapshot;
import com.movinsync.tracking.repository.VehicleStateSnapshotRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Handles state persistence (snapshotting) for crash recovery.
 * <p>
 * Loads state on startup, flushes periodically, and provides synchronous
 * flush for geofence transitions.
 */
@Service
public class StatePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(StatePersistenceService.class);

    private final VehicleStateStore stateStore;
    private final VehicleStateSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public StatePersistenceService(VehicleStateStore stateStore,
                                   VehicleStateSnapshotRepository snapshotRepository) {
        this.stateStore = stateStore;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Load state on startup.
     */
    @PostConstruct
    public void loadStateOnStartup() {
        List<VehicleStateSnapshot> snapshots = snapshotRepository.findAll();
        int loaded = 0;
        for (VehicleStateSnapshot snapshot : snapshots) {
            try {
                VehicleState state = new VehicleState(snapshot.getVehicleId());
                state.setNewVehicle(false); // It has a snapshot, so it's not new

                if (snapshot.getLastLatitude() != null && snapshot.getLastLongitude() != null) {
                    state.setLastPosition(new GeoPoint(snapshot.getLastLatitude(), snapshot.getLastLongitude()));
                }
                if (snapshot.getLastTimestampMs() != null) {
                    state.setLastTimestampMs(snapshot.getLastTimestampMs());
                }
                if (snapshot.getSpeedEma() != null) {
                    state.setSpeedEma(snapshot.getSpeedEma());
                }
                if (snapshot.getLastSnappedIndex() != null) {
                    state.setLastSnappedIndex(snapshot.getLastSnappedIndex());
                }

                if (snapshot.getZoneStatesJson() != null) {
                    Map<Long, Boolean> zoneStates = objectMapper.readValue(
                            snapshot.getZoneStatesJson(), new TypeReference<>() {});
                    state.getZoneStates().putAll(zoneStates);
                }

                if (snapshot.getEtaMapJson() != null) {
                    Map<Long, VehicleState.EtaEntry> etaMap = objectMapper.readValue(
                            snapshot.getEtaMapJson(), new TypeReference<>() {});
                    state.getEtaMap().putAll(etaMap);
                }

                stateStore.put(state.getVehicleId(), state);
                loaded++;
            } catch (Exception e) {
                log.error("Failed to load snapshot for vehicle={}", snapshot.getVehicleId(), e);
            }
        }
        log.info("Loaded {} vehicle state snapshots on startup", loaded);
    }

    /**
     * Persist a single vehicle's state synchronously. Called during geofence transitions.
     */
    @Transactional
    public void flushSynchronously(long vehicleId, GeoPoint pos, long ts, double speedEma, 
                                   int snappedIndex, Map<Long, Boolean> zoneStates, 
                                   Map<Long, VehicleState.EtaEntry> etaMap) {
        try {
            VehicleStateSnapshot snapshot = new VehicleStateSnapshot();
            snapshot.setVehicleId(vehicleId);
            if (pos != null) {
                snapshot.setLastLatitude(pos.lat());
                snapshot.setLastLongitude(pos.lon());
            }
            snapshot.setLastTimestampMs(ts);
            snapshot.setSpeedEma(speedEma);
            snapshot.setLastSnappedIndex(snappedIndex);
            snapshot.setZoneStatesJson(objectMapper.writeValueAsString(zoneStates));
            snapshot.setEtaMapJson(objectMapper.writeValueAsString(etaMap));
            
            snapshotRepository.save(snapshot);
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("Database failure while saving snapshot for vehicle={}", vehicleId, e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to save snapshot for vehicle={}", vehicleId, e);
            throw new RuntimeException("Snapshot DB failure", e);
        }
    }

    /**
     * Periodically flush all dirty states.
     * In a real system, we'd track dirty flags. For this case study, we can flush all active states.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void periodicFlush() {
        // We'll flush all states. In production, this would be chunked/batched or use dirty flags.
        List<VehicleStateSnapshot> snapshots = stateStore.getAll().values().stream()
                .filter(s -> !s.isNewVehicle())
                .map(s -> {
                    try {
                        return buildSnapshot(s);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
            log.trace("Periodic flush: saved {} snapshots", snapshots.size());
        }
    }

    private VehicleStateSnapshot buildSnapshot(VehicleState state) throws JsonProcessingException {
        VehicleStateSnapshot snapshot = new VehicleStateSnapshot();
        snapshot.setVehicleId(state.getVehicleId());
        
        GeoPoint pos = state.getLastPosition();
        if (pos != null) {
            snapshot.setLastLatitude(pos.lat());
            snapshot.setLastLongitude(pos.lon());
        }
        
        snapshot.setLastTimestampMs(state.getLastTimestampMs());
        snapshot.setSpeedEma(state.getSpeedEma());
        snapshot.setLastSnappedIndex(state.getLastSnappedIndex());

        snapshot.setZoneStatesJson(objectMapper.writeValueAsString(state.getZoneStates()));
        snapshot.setEtaMapJson(objectMapper.writeValueAsString(state.getEtaMap()));

        return snapshot;
    }
}
