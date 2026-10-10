package com.movinsync.tracking.service;

import com.movinsync.tracking.config.TrackingProperties;
import com.movinsync.tracking.domain.geo.Geofence;
import com.movinsync.tracking.domain.geo.GeoPoint;
import com.movinsync.tracking.domain.state.PingResult;
import com.movinsync.tracking.domain.state.VehicleState;
import com.movinsync.tracking.domain.state.VehicleStateStore;
import com.movinsync.tracking.domain.state.ZoneTransition;
import com.movinsync.tracking.dto.request.PingRequest;
import com.movinsync.tracking.entity.*;
import com.movinsync.tracking.repository.GeofenceEventRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core ingestion pipeline: validate → device/vehicle lookup → future/stale →
 * lock(vehicleId) → dedup → monotonic → gap detection → commit.
 * <p>
 * Business logic lives here, not in the controller.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Clock clock;
    private final TrackingProperties props;
    private final DeviceRegistryService registryService;
    private final VehicleStateStore stateStore;
    private final HistoryWriter historyWriter;
    private final GeofenceEngine geofenceEngine;
    private final GeofenceService geofenceService;
    private final GeofenceEventRepository geofenceEventRepository;

    // Striped locks keyed by vehicleId
    private final Lock[] stripedLocks;

    // Dedup cache: key = "deviceId:ts" → Boolean.TRUE
    private final Cache<String, Boolean> dedupCache;

    public IngestionService(Clock clock,
                            TrackingProperties props,
                            DeviceRegistryService registryService,
                            VehicleStateStore stateStore,
                            HistoryWriter historyWriter,
                            GeofenceEngine geofenceEngine,
                            GeofenceService geofenceService,
                            GeofenceEventRepository geofenceEventRepository) {
        this.clock = clock;
        this.props = props;
        this.registryService = registryService;
        this.stateStore = stateStore;
        this.historyWriter = historyWriter;
        this.geofenceEngine = geofenceEngine;
        this.geofenceService = geofenceService;
        this.geofenceEventRepository = geofenceEventRepository;

        this.stripedLocks = new Lock[props.stripedLockCount()];
        for (int i = 0; i < stripedLocks.length; i++) {
            stripedLocks[i] = new ReentrantLock();
        }

        this.dedupCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Process a single ping. Returns the result status.
     *
     * @throws IllegalArgumentException if deviceId is unknown or bearing is invalid
     */
    public PingResult processPing(PingRequest request) {
        // 1. Validate bearing (contract: 0 <= bearing < 360)
        if (request.bearing() != null && (request.bearing() < 0 || request.bearing() >= 360)) {
            throw new IllegalArgumentException("Bearing must be in [0, 360), got " + request.bearing());
        }

        // 2. Device/vehicle lookup
        DeviceRegistryService.DeviceVehicleInfo info = registryService.resolve(request.deviceId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown device: " + request.deviceId()));

        Vehicle vehicle = info.vehicle();
        long vehicleId = vehicle.getId();

        try {
            MDC.put("vehicleId", String.valueOf(vehicleId));

            // 3. Future check
            long nowMs = clock.instant().toEpochMilli();
            if (request.ts() > nowMs + props.futureLimitMs()) {
                log.debug("Rejected future ping: ts={} now={}", request.ts(), nowMs);
                return PingResult.rejectedFuture();
            }

            // 4. Stale check
            if (nowMs - request.ts() > props.staleLimitMs()) {
                log.debug("Stale ping: ts={} now={}", request.ts(), nowMs);
                return PingResult.stale();
            }

            // 5. Lock on vehicleId
            Lock lock = stripedLocks[(int) (vehicleId % stripedLocks.length)];
            lock.lock();
            try {
                return processUnderLock(request, vehicle, vehicleId);
            } finally {
                lock.unlock();
            }
        } finally {
            MDC.remove("vehicleId");
        }
    }

    private PingResult processUnderLock(PingRequest request, Vehicle vehicle, long vehicleId) {
        VehicleState state = stateStore.getOrCreate(vehicleId);

        // 6. Dedup: exact timestamp match or in cache
        String dedupKey = request.deviceId() + ":" + request.ts();
        if (request.ts() == state.getLastTimestampMs() || dedupCache.getIfPresent(dedupKey) != null) {
            log.debug("Duplicate ping: ts={}", request.ts());
            return PingResult.duplicate();
        }

        // 7. Out-of-order: ts < lastAppliedTs (not duplicate, within stale limit)
        if (state.getLastTimestampMs() > 0 && request.ts() < state.getLastTimestampMs()) {
            log.debug("Out-of-order ping: ts={} lastTs={}", request.ts(), state.getLastTimestampMs());
            // Store as history only
            enqueueHistory(request, vehicleId);
            dedupCache.put(dedupKey, Boolean.TRUE);
            return PingResult.outOfOrder();
        }

        // 8. Gap detection
        boolean gap = state.getLastTimestampMs() > 0
                && (request.ts() - state.getLastTimestampMs()) > props.gapThresholdMs();

        // 9. Derive speed if not provided
        double speed = deriveSpeed(request, state);

        // 10. Update EMA speed
        if (gap) {
            // Reset EMA after gap
            state.setSpeedEma(speed);
        } else if (state.getLastTimestampMs() > 0) {
            double alpha = props.emaAlpha();
            state.setSpeedEma(alpha * speed + (1 - alpha) * state.getSpeedEma());
        } else {
            state.setSpeedEma(speed);
        }

        // 11. Geofence evaluation
        GeoPoint newPosition = new GeoPoint(request.lat(), request.lon());

        // Candidates = all zones (naive scan; grid index added in step 6)
        // ∪ zones currently INSIDE (so EXIT can fire even if grid misses)
        Set<Long> insideZoneIds = new HashSet<>();
        for (Map.Entry<Long, Boolean> e : state.getZoneStates().entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) insideZoneIds.add(e.getKey());
        }
        List<Geofence> candidates = new ArrayList<>(geofenceService.getAllZones());
        // Ensure inside zones are in the candidate list (they may already be)
        for (Long zid : insideZoneIds) {
            if (candidates.stream().noneMatch(z -> z.getId() == zid)) {
                geofenceService.getZone(zid).ifPresent(candidates::add);
            }
        }

        GeofenceEngine.EvaluationResult evalResult = geofenceEngine.evaluate(
                newPosition, request.ts(), vehicleId, state, candidates, gap);

        // 12. Persist-then-commit: persist transitions in DB, then commit to memory
        if (!evalResult.transitions().isEmpty()) {
            try {
                persistTransitions(evalResult.transitions(), vehicleId, request.ts());
            } catch (DataAccessException ex) {
                // DB failure: discard new state, return 503
                log.error("DB failure during geofence transition persist for vehicle={}", vehicleId);
                throw ex; // will be caught by exception handler as 503
            }
        }

        // Commit to memory only after DB success
        state.getZoneStates().putAll(evalResult.newZoneStates());
        state.setLastPosition(newPosition);
        state.setLastTimestampMs(request.ts());
        if (state.isNewVehicle()) {
            state.setNewVehicle(false);
        }

        // 13. Enqueue history and dedup
        enqueueHistory(request, vehicleId);
        dedupCache.put(dedupKey, Boolean.TRUE);

        log.debug("Accepted ping: ts={} gap={} transitions={}", request.ts(), gap,
                evalResult.transitions().size());
        return PingResult.accepted();
    }

    private double deriveSpeed(PingRequest request, VehicleState state) {
        if (request.speed() != null && request.speed() >= 0) {
            return request.speed();
        }
        // Derive from last two fixes
        if (state.getLastPosition() != null && state.getLastTimestampMs() > 0) {
            double dist = com.movinsync.tracking.domain.geo.GeoUtils.haversineMetres(
                    state.getLastPosition(),
                    new GeoPoint(request.lat(), request.lon()));
            double timeSec = (request.ts() - state.getLastTimestampMs()) / 1000.0;
            if (timeSec > 0) {
                return dist / timeSec;
            }
        }
        return 0.0;
    }

    private void enqueueHistory(PingRequest request, long vehicleId) {
        historyWriter.enqueue(LocationPing.builder()
                .deviceId(request.deviceId())
                .vehicleId(vehicleId)
                .timestampMs(request.ts())
                .latitude(request.lat())
                .longitude(request.lon())
                .speed(request.speed())
                .bearing(request.bearing())
                .build());
    }

    @Transactional
    protected void persistTransitions(List<ZoneTransition> transitions, long vehicleId, long pingTs) {
        for (ZoneTransition t : transitions) {
            GeofenceEvent event = GeofenceEvent.builder()
                    .eventType(t.type())
                    .vehicleId(vehicleId)
                    .zoneId(t.zoneId())
                    .timestampMs(pingTs)
                    .detectedAfterGap(t.detectedAfterGap())
                    .status(EventStatus.PENDING)
                    .build();
            geofenceEventRepository.save(event);
        }
    }
}
