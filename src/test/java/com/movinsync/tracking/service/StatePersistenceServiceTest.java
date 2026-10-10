package com.movinsync.tracking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.domain.state.VehicleStateStore;
import com.movinsync.tracking.entity.VehicleStateSnapshot;
import com.movinsync.tracking.repository.VehicleStateSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StatePersistenceService.
 */
class StatePersistenceServiceTest {

    private VehicleStateStore stateStore;
    private VehicleStateSnapshotRepository snapshotRepository;
    private StatePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        stateStore = new VehicleStateStore();
        snapshotRepository = mock(VehicleStateSnapshotRepository.class);
        persistenceService = new StatePersistenceService(stateStore, snapshotRepository);
    }

    @Test
    void loadStateOnStartup_loadsSnapshotsIntoStore() throws Exception {
        VehicleStateSnapshot snap = new VehicleStateSnapshot();
        snap.setVehicleId(1L);
        snap.setLastLatitude(12.935);
        snap.setLastLongitude(77.610);
        snap.setLastTimestampMs(1000L);
        snap.setSpeedEma(15.0);
        snap.setLastSnappedIndex(5);
        snap.setZoneStatesJson("{\"10\":true}");
        snap.setEtaMapJson("{\"20\":{\"etaSeconds\":100.0,\"lastPublishedEtaSeconds\":100.0,\"status\":\"PENDING\"}}");

        when(snapshotRepository.findAll()).thenReturn(List.of(snap));

        persistenceService.loadStateOnStartup();

        VehicleState state = stateStore.get(1L);
        assertNotNull(state);
        assertFalse(state.isNewVehicle(), "Loaded vehicle should not be new");
        assertEquals(12.935, state.getLastPosition().lat());
        assertEquals(77.610, state.getLastPosition().lon());
        assertEquals(1000L, state.getLastTimestampMs());
        assertEquals(15.0, state.getSpeedEma());
        assertEquals(5, state.getLastSnappedIndex());

        assertTrue(state.isInside(10L));
        assertNotNull(state.getEtaMap().get(20L));
        assertEquals(100.0, state.getEtaMap().get(20L).etaSeconds());
    }

    @Test
    void flushSynchronously_savesSnapshot() {
        Map<Long, Boolean> zones = Map.of(1L, true);
        Map<Long, VehicleState.EtaEntry> etas = Map.of(2L, new VehicleState.EtaEntry(50.0, 50.0, VehicleState.EtaStatus.PENDING));

        persistenceService.flushSynchronously(1L, new GeoPoint(12.930, 77.600), 2000L, 10.0, 2, zones, etas);

        ArgumentCaptor<VehicleStateSnapshot> captor = ArgumentCaptor.forClass(VehicleStateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());

        VehicleStateSnapshot snap = captor.getValue();
        assertEquals(1L, snap.getVehicleId());
        assertEquals(12.930, snap.getLastLatitude());
        assertEquals(77.600, snap.getLastLongitude());
        assertEquals(2000L, snap.getLastTimestampMs());
        assertEquals(10.0, snap.getSpeedEma());
        assertEquals(2, snap.getLastSnappedIndex());
        assertTrue(snap.getZoneStatesJson().contains("\"1\":true"));
        assertTrue(snap.getEtaMapJson().contains("50.0"));
    }

    @Test
    void periodicFlush_savesOnlyActiveStates() {
        VehicleState active = new VehicleState(1L);
        active.setNewVehicle(false); // active
        active.setLastTimestampMs(1000L);

        VehicleState newVehicle = new VehicleState(2L);
        newVehicle.setNewVehicle(true); // new, should be skipped

        stateStore.put(1L, active);
        stateStore.put(2L, newVehicle);

        persistenceService.periodicFlush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VehicleStateSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).saveAll(captor.capture());

        List<VehicleStateSnapshot> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(1L, saved.get(0).getVehicleId());
    }
}
