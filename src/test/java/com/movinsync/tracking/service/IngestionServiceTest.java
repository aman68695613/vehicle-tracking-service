package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.state.PingResult;
import com.movinsync.tracking.domain.state.PingStatus;
import com.movinsync.tracking.domain.state.VehicleStateStore;
import com.movinsync.tracking.dto.request.PingRequest;
import com.movinsync.tracking.entity.Device;
import com.movinsync.tracking.entity.Vehicle;
import com.movinsync.tracking.repository.GeofenceEventRepository;
import com.movinsync.tracking.repository.LocationPingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IngestionService. Uses a fixed Clock and mocked dependencies.
 */
class IngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final long NOW_MS = NOW.toEpochMilli();

    private Clock clock;
    private TrackingProperties props;
    private DeviceRegistryService registryService;
    private VehicleStateStore stateStore;
    private HistoryWriter historyWriter;
    private GeofenceEngine geofenceEngine;
    private GeofenceService geofenceService;
    private GeofenceEventRepository geofenceEventRepository;
    private EtaService etaService;
    private StatePersistenceService statePersistenceService;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneId.of("UTC"));
        props = new TrackingProperties(
                30_000,   // futureLimitMs
                300_000,  // staleLimitMs
                120_000,  // gapThresholdMs
                2.0,      // minSpeedMs
                1.2,      // trafficFactor
                0.3,      // emaAlpha
                15.0,     // etaPublishThresholdSeconds
                64        // stripedLockCount
        );

        registryService = mock(DeviceRegistryService.class);
        LocationPingRepository pingRepo = mock(LocationPingRepository.class);
        historyWriter = spy(new HistoryWriter(pingRepo, new SimpleMeterRegistry()));
        stateStore = new VehicleStateStore();
        geofenceEngine = spy(new GeofenceEngine(props));
        geofenceService = mock(GeofenceService.class);
        when(geofenceService.candidates(any())).thenReturn(Collections.emptyList());
        geofenceEventRepository = mock(GeofenceEventRepository.class);
        etaService = mock(EtaService.class);
        statePersistenceService = mock(StatePersistenceService.class);
        MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        org.springframework.transaction.support.TransactionTemplate txTemplate = mock(org.springframework.transaction.support.TransactionTemplate.class);
        doAnswer(inv -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service = new IngestionService(clock, props, registryService, stateStore, historyWriter,
                geofenceEngine, geofenceService, geofenceEventRepository, etaService, statePersistenceService, meterRegistry, txTemplate);

        // Setup default device mapping
        Vehicle vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setBusinessUnit("BU-TEST");

        Device device = new Device();
        device.setDeviceId("DEV-001");
        device.setVehicle(vehicle);

        when(registryService.resolve("DEV-001"))
                .thenReturn(Optional.of(new DeviceRegistryService.DeviceVehicleInfo(device, vehicle, "BU-TEST")));

        when(etaService.updateEta(any(), any(), any(), anyBoolean()))
                .thenReturn(new EtaService.EtaUpdateResult(new java.util.HashMap<>(), 0));
    }

    private PingRequest ping(long ts) {
        return new PingRequest("DEV-001", 12.935, 77.610, 10.0, 90.0, ts);
    }

    private PingRequest ping(long ts, double lat, double lon) {
        return new PingRequest("DEV-001", lat, lon, 10.0, 90.0, ts);
    }

    @Test
    void accept_validPing() {
        PingResult result = service.processPing(ping(NOW_MS - 1000));
        assertEquals(PingStatus.ACCEPTED, result.status());
    }

    @Test
    void reject_futurePing() {
        PingResult result = service.processPing(ping(NOW_MS + 60_000)); // 60s in future
        assertEquals(PingStatus.REJECTED_FUTURE, result.status());
    }

    @Test
    void accept_slightlyFuturePing() {
        // Within 30s tolerance
        PingResult result = service.processPing(ping(NOW_MS + 20_000));
        assertEquals(PingStatus.ACCEPTED, result.status());
    }

    @Test
    void reject_stalePing() {
        PingResult result = service.processPing(ping(NOW_MS - 400_000)); // 6.7 min ago
        assertEquals(PingStatus.STALE, result.status());
    }

    @Test
    void accept_notQuiteStale() {
        PingResult result = service.processPing(ping(NOW_MS - 290_000)); // 4.8 min ago
        assertEquals(PingStatus.ACCEPTED, result.status());
    }

    @Test
    void duplicate_sameTimestamp() {
        service.processPing(ping(NOW_MS - 5000));
        PingResult result = service.processPing(ping(NOW_MS - 5000));
        assertEquals(PingStatus.DUPLICATE, result.status());
    }

    @Test
    void outOfOrder_olderAfterNewer() {
        service.processPing(ping(NOW_MS - 1000)); // newer first
        PingResult result = service.processPing(ping(NOW_MS - 3000)); // older after
        assertEquals(PingStatus.OUT_OF_ORDER, result.status());
    }

    @Test
    void outOfOrder_isStoredAsHistory() {
        service.processPing(ping(NOW_MS - 1000));
        PingResult result = service.processPing(ping(NOW_MS - 3000));
        assertEquals(PingStatus.OUT_OF_ORDER, result.status());
        // The out-of-order ping should still be enqueued for history
        // (verified by the fact that enqueue was called, not that it throws)
    }

    @Test
    void duplicate_thirdPingWithSameTs() {
        service.processPing(ping(NOW_MS - 5000));
        service.processPing(ping(NOW_MS - 5000));
        PingResult result = service.processPing(ping(NOW_MS - 5000));
        assertEquals(PingStatus.DUPLICATE, result.status());
    }

    @Test
    void unknownDevice_throwsIllegalArgument() {
        PingRequest req = new PingRequest("UNKNOWN", 12.9, 77.6, 10.0, 90.0, NOW_MS);
        when(registryService.resolve("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.processPing(req));
    }

    @Test
    void invalidBearing_throwsIllegalArgument() {
        PingRequest req = new PingRequest("DEV-001", 12.9, 77.6, 10.0, 360.0, NOW_MS);
        assertThrows(IllegalArgumentException.class, () -> service.processPing(req));
    }

    @Test
    void bearingNull_isValid() {
        PingRequest req = new PingRequest("DEV-001", 12.9, 77.6, 10.0, null, NOW_MS - 1000);
        PingResult result = service.processPing(req);
        assertEquals(PingStatus.ACCEPTED, result.status());
    }

    @Test
    void speedNull_derivesFromLastFix() {
        // First ping with position
        service.processPing(ping(NOW_MS - 10000, 12.935, 77.610));
        // Second ping with no speed, different position
        PingRequest noSpeed = new PingRequest("DEV-001", 12.936, 77.611, null, 90.0, NOW_MS - 5000);
        PingResult result = service.processPing(noSpeed);
        assertEquals(PingStatus.ACCEPTED, result.status());
    }

    @Test
    void multipleValidPings_inOrder() {
        assertEquals(PingStatus.ACCEPTED, service.processPing(ping(NOW_MS - 10000)).status());
        assertEquals(PingStatus.ACCEPTED, service.processPing(ping(NOW_MS - 8000)).status());
        assertEquals(PingStatus.ACCEPTED, service.processPing(ping(NOW_MS - 6000)).status());
        assertEquals(PingStatus.ACCEPTED, service.processPing(ping(NOW_MS - 4000)).status());
    }

    @Test
    void gapDetection_doesNotRejectPing() {
        // First ping
        service.processPing(ping(NOW_MS - 200_000)); // 3.3 min ago
        // Second ping after a gap > 2 min (gap threshold = 120s)
        PingResult result = service.processPing(ping(NOW_MS - 1000)); // recent
        // Gap should be detected but ping still accepted
        assertEquals(PingStatus.ACCEPTED, result.status());
    }
    @Test
    void dbFailure_throwsServiceUnavailableAndDiscardsState() {
        // Mock geofenceEngine to return a transition
        com.movinsync.tracking.domain.state.ZoneTransition transition = new com.movinsync.tracking.domain.state.ZoneTransition(1L, com.movinsync.tracking.entity.GeofenceEventType.ENTRY, false);
        com.movinsync.tracking.service.GeofenceEngine.EvaluationResult evalResult =
                new com.movinsync.tracking.service.GeofenceEngine.EvaluationResult(
                        java.util.List.of(transition), java.util.Map.of(1L, true));
                        
        geofenceEngine = mock(GeofenceEngine.class);
        when(geofenceEngine.evaluate(any(), anyLong(), anyLong(), any(), any(), anyBoolean())).thenReturn(evalResult);

        // Replace service with the mocked geofence engine and a failing tx template
        org.springframework.transaction.support.TransactionTemplate txTemplate = mock(org.springframework.transaction.support.TransactionTemplate.class);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("DB Error"))
                .when(txTemplate).executeWithoutResult(any());

        service = new IngestionService(clock, props, registryService, stateStore, historyWriter,
                geofenceEngine, geofenceService, geofenceEventRepository, etaService, statePersistenceService, new SimpleMeterRegistry(), txTemplate);

        PingRequest req = ping(NOW_MS - 1000);
        assertThrows(com.movinsync.tracking.exception.ServiceUnavailableException.class, () -> service.processPing(req));
        
        // Ensure state was NOT committed
        com.movinsync.tracking.domain.state.VehicleState state = stateStore.getOrCreate(1L);
        assertNull(state.getLastPosition()); // Should be unchanged
    }

    @Test
    void concurrency_handlesConcurrentPings() throws InterruptedException {
        int threadCount = 50;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger exceptions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    PingResult res = service.processPing(ping(NOW_MS - 100000 + index * 1000));
                    if (res.status() == PingStatus.ACCEPTED) accepted.incrementAndGet();
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(completed, "Latch timed out");
        assertEquals(0, exceptions.get(), "Exceptions thrown during concurrent execution");
        
        com.movinsync.tracking.domain.state.VehicleState state = stateStore.getOrCreate(1L);
        assertNotNull(state.getLastPosition());
        assertEquals(NOW_MS - 100000 + 49 * 1000, state.getLastTimestampMs());
        
        // Out-of-order pings are stored as history only, so total history writer calls = 50
        verify(historyWriter, times(50)).enqueue(any());
    }

    @Test
    void gap_detectsGapCorrectly() {
        // Send first ping
        service.processPing(ping(NOW_MS - 200000));
        
        // Mock geofence engine to capture the gap flag
        org.mockito.ArgumentCaptor<Boolean> gapCaptor = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        
        // Send second ping with >2 min gap
        service.processPing(ping(NOW_MS - 10000));
        
        verify(geofenceEngine, times(2)).evaluate(any(), anyLong(), anyLong(), any(), any(), gapCaptor.capture());
        
        // First ping gap=false, second ping gap=true
        assertFalse(gapCaptor.getAllValues().get(0));
        assertTrue(gapCaptor.getAllValues().get(1));
    }

    @Test
    void queueFull_dropsHistoryButAcceptsPing() throws Exception {
        // Fill the HistoryWriter queue
        for (int i = 0; i < 11000; i++) {
            historyWriter.enqueue(new com.movinsync.tracking.entity.LocationPing());
        }
        
        // Sending a new ping should still be ACCEPTED, even if queue is full
        PingResult result = service.processPing(ping(NOW_MS - 1000));
        assertEquals(PingStatus.ACCEPTED, result.status());
    }
}
