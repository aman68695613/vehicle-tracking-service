# Vehicle Tracking Service: Build Plan (v2)
MoveInSync Case Study 2 (Live Vehicle Tracking, Geofence Events & ETA) | SDE Java Backend | Java 17, Spring Boot 3, PostgreSQL

> This document is the contract for the build. The project skeleton and the PostgreSQL connection already exist; do not recreate them. If anything is ambiguous, ask before guessing.

## 1. Scope
**Must have:** idempotent, ordered ping ingestion that rejects stale pings; geofence ENTRY/EXIT that fires once (circle and rectangle zones, time windows, vehicle-specific zones); ETA with route snapping for every waiting employee; a spatial index; gap recovery with no false events.

**Plus points:** authentication with roles, complexity analysis, failure handling, OOP, documented trade-offs, monitoring, caching, error handling.

**Out of scope (state in README):** real push notifications (we log and store them), a map UI, real road-network routing, multi-node deployment (we describe how it would scale).

## 2. Assumptions (copy into README)
- Pings arrive over HTTP POST, one at a time. Batch endpoint optional. Production would use Kafka or MQTT.
- The route is a stored polyline and stops lie on it. No Google or OSRM calls.
- Timestamps come from the device as UTC epoch milliseconds (`ts` in the JSON is epoch ms).
- One active route per vehicle. There is no trip start/end: a vehicle's route is always active. If an admin changes a vehicle's route, reset its snap index and ETA map.
- Speed is in m/s. If a ping has no speed, derive it from the last two fixes.
- Bearing must satisfy 0 <= bearing < 360. It is validated and stored, not used in ETA.
- Single server instance. State is held in memory and persisted for recovery.
- India deployment: default timezone Asia/Kolkata, no zone crosses the antimeridian.
- Rectangle margins are given in metres and converted to degrees at the zone's latitude.
- All time-dependent logic (stale, future, gap, time windows) uses an injectable `java.time.Clock` bean. Never call `Instant.now()` directly.

## 3. Core rules (the contract)
| Topic | Rule |
|---|---|
| Validation | lat within +-90, lon within +-180, speed >= 0, 0 <= bearing < 360 if present, ts required. Bad input returns 400 |
| Future timestamp | ts > now + 30s returns REJECTED_FUTURE |
| Stale | now - ts > 5 min returns STALE, not stored |
| Duplicate | ts == lastTs, key in dedup cache, or DB unique violation on (device_id, ts) returns DUPLICATE with no side effects |
| Out of order | ts < lastAppliedTs (not duplicate, within stale limit) returns OUT_OF_ORDER. Stored as history only, never changes live state |
| Fire once | ENTRY/EXIT only on a state change per (vehicle, zone) |
| Hysteresis | Entry at entryRadius. Exit at entryRadius + max(10 m, 5%). Rectangles use a fixed metre margin. Configurable, 0 in tests |
| Time window | Judged by the ping's ts in the zone's timezone. Wraps midnight when start > end. Occupancy is always tracked; events fire only if the zone is active at ts |
| Window opens while inside | No ENTRY |
| Window closes while inside | Later EXIT is not fired. Occupancy updates silently |
| New vehicle | `isNewVehicle` = no persisted snapshot exists. First ping initialises each zone state silently (no ENTRY) |
| Restart | A vehicle WITH a snapshot is not new. Load persisted state, compare normally. If ts gap > gap threshold, flag detectedAfterGap |
| Gap > 2 min | Keep state. On resume fire only if state differs, flagged detectedAfterGap=true. Reset the speed EMA |
| ETA distance | Distance along the route polyline from the snapped point to the stop (roads curve; straight-line is optimistic) |
| ETA speed | EMA-smoothed speed, minimum floor, times a configured traffic factor |
| ETA recompute | On every accepted ping. Publish only if change > 15 s |

## 4. Architecture
Layered Spring Boot monolith with in-memory hot state and DB persistence.

```
Device --POST /api/pings (API key)--> PingController
                                        |
                              IngestionService
   validate -> device/vehicle lookup (Caffeine) -> future/stale -> lock(vehicleId)
                                        |
                                        v
                       VehicleStateStore (ConcurrentHashMap)
   latest fix, lastTs, zone states, speed EMA, last snapped index, ETA map
                         |                          |
                         v                          v
                  GeofenceEngine               EtaService
   candidates = grid.lookup(ping)        snap to route -> cumulative distance
     U zones currently INSIDE            -> ETA for every remaining stop
     -> point-in-zone -> vehicle filter
     -> state machine (returns NEW state, does not mutate)
                         |
                         v
        DB transaction: zone state + GeofenceEvent (status PENDING)
        on success -> commit new state to memory; on failure -> discard
                         |
                         v
     AFTER_COMMIT @Async listener -> NotificationService (log 'cab arriving')
     @Scheduled job retries PENDING events (lightweight outbox)
```

## 5. Domain model
- Device maps to Vehicle. Device holds a hashed API key (SHA-256). Vehicle holds businessUnit.
- Vehicle has one active Route. Route has ordered RouteStops with cumulative distance; each stop has a waiting employee (PickupRequest).
- Geofence is abstract: CircleGeofence and RectangleGeofence. Fields: purpose (PICKUP, OFFICE_GATE, RESTRICTED), optional stopId, optional TimeWindow (start, end, timezone), optional allowedVehicleIds (empty = all vehicles).
- LocationPing: raw history, unique constraint on (device_id, ts).
- GeofenceEvent: type, vehicle, zone, ts, detectedAfterGap, status (PENDING or SENT).
- VehicleStateSnapshot and ZoneStateRow: used for recovery.
- AppUser: roles ADMIN, APPROVER, EMPLOYEE.
- In-memory ETA map per vehicle: `stopId -> {eta, lastPublishedEta, status(PENDING|PASSED)}`. `GET /pickups/me/eta` reads from it. It is rebuilt by the next ping after a restart.

OOP: abstract `Geofence` with `contains(point, margin)`, `boundingBox()`, `isActiveAt(ts)`; Circle and Rectangle override geometry (polymorphism). Services depend on interfaces. `EtaStrategy` interface lets the speed model be swapped.

## 6. Key algorithms
### 6.1 Ingestion flow
```
validate(ping); check apiKey.deviceId == ping.deviceId
(device, vehicle, businessUnit) = registry.get(deviceId)       // Caffeine
if ping.ts > now + futureLimit -> REJECTED_FUTURE
if now - ping.ts > staleLimit  -> STALE

lock(vehicleId) {                                              // striped lock
  if ts == state.lastTs or dedupCache.has(deviceId, ts) -> DUPLICATE
  if ts < state.lastTs { enqueueHistory(ping); -> OUT_OF_ORDER }
  gap = ts - state.lastTs > gapThreshold
  newState = state.copyWith(ping)                              // not applied yet
  transitions = engine.evaluate(ping, newState, gap)
  try {
    if transitions not empty: tx.persist(zoneStates, events)   // one transaction
  } catch (DataAccessException) {
    discard newState; -> 503 error (ping NOT applied)
  }
  state.commit(newState)                                       // memory only after DB success
  eta.update(ping, state, gap)
  dedupCache.put(deviceId, ts); enqueueHistory(ping)
  -> ACCEPTED
}
```
- DB unique violation on a history insert maps to DUPLICATE, never a 500.
- Dedup cache is a bounded Caffeine cache (maximumSize, expireAfterWrite). It is an optimisation only; the DB unique constraint is the source of truth.
- History writes go through a BOUNDED queue with a batch writer. If the queue is full, drop the history row, increment `history.dropped`, and never block ingestion.

### 6.2 Geofence engine
```
candidates = grid.lookup(ping) U state.insideZones
for zone in candidates:
  if !zone.appliesTo(vehicleId) continue
  was = state.isInside(zone)                    // absent = OUTSIDE
  now = was ? zone.contains(p, exitMargin) : zone.contains(p, entryMargin)
  if state.isNewVehicle: set(zone, now); continue      // silent init
  if was != now:
    set(zone, now)                              // occupancy always tracked
    if zone.isActiveAt(ping.ts): emit ENTRY/EXIT (detectedAfterGap = gap)
```
Grid lookup and EXIT detection are separate problems: a vehicle that left a zone's cell no longer gets that zone from the grid, so the union with `insideZones` is what makes EXIT fire.

### 6.3 Point-in-zone
- Circle: haversine distance from centre <= radius (entry radius or exit threshold).
- Rectangle: minLat <= lat <= maxLat and minLon <= lon <= maxLon, expanded by the margin for exit tests.
- Time window: t = local time of ping.ts in the zone timezone. Active if start <= t < end; if start > end (wraps midnight) active if t >= start || t < end.

### 6.4 Spatial index (uniform grid)
- dLat = 0.005 deg (about 550 m); dLon = dLat / cos(refLat).
- Cell key = (floor(lat/dLat), floor(lon/dLon)).
- Each zone is registered in every cell its bounding box (expanded by exit margin) overlaps, so a single-cell lookup suffices.
- On any zone change build a new index and swap a volatile reference. Never mutate under live traffic.
- Trade-off vs geohash or R-tree: simpler, O(1) lookup, weaker when zone sizes vary widely.

### 6.5 ETA
- Precompute cumulative distance along the route polyline and each stop's cumulative position.
- Snap by projecting onto segments in a local flat plane. Search a window around the last snapped index; fall back to a full scan if the distance to the route is large.
- remaining(stop) = stop.cumDist - snapped.cumDist. Passed stops are marked PASSED.
- speed = EMA(pingSpeed or derivedSpeed); effective = max(speed, minSpeed).
- eta(stop) = remaining / effective * trafficFactor, for every remaining stop (each waiting employee).
- Publish only if the ETA changes by more than 15 s. Reset the EMA after a gap.

### 6.6 Gap and restart recovery
- Never reset state when a device goes dark.
- On resume compare the new position with the stored state: no difference, no event; difference, one event with detectedAfterGap=true.
- Two fixes cannot prove the path between them, so only the state difference is used.
- On restart load persisted zone state and latest fix before accepting pings.

## 7. Persistence and failure handling
| Data | Frequency | How |
|---|---|---|
| Ping history | every ping (~2,000/s at 10k vehicles) | bounded queue, async batch write, unique constraint as safety net |
| Latest position | every ping | in memory, periodic flush every few seconds |
| ENTRY/EXIT and zone state | rare | one synchronous DB transaction (zone state + event row) |
| Notification | rare | AFTER_COMMIT listener; failure leaves event PENDING, never breaks ingestion |

- A `@Scheduled` job retries PENDING events. Kafka or a full outbox table is the production path.
- A failing notification must never fail or slow a ping.
- DB failure during a transition: the ping is not applied (see 6.1), so memory and DB never diverge.
- Single instance: the striped lock only works within one JVM; the DB constraint is the backstop. Scaling out means partitioning by vehicleId.

## 8. Security
- JWT with Spring Security and `@PreAuthorize`. ADMIN manages geofences, devices, routes and views everything. APPROVER views all vehicle positions and events. EMPLOYEE sees only their own pickup ETA (ownership check).
- `POST /api/pings` uses a device API key, stored hashed (SHA-256), bound to one device. Reject a body whose deviceId does not match the key.
- Passwords hashed with BCrypt.
- Actuator: `/actuator/health` is public; `/actuator/metrics` and `/actuator/prometheus` require ADMIN. Configure this explicitly in SecurityConfig.

## 9. Caching
Caffeine for the device registry and geofence definitions. TTL 5 minutes plus explicit eviction on every admin write. What becomes stale: a changed zone or device mapping can be missed for up to the TTL if eviction fails.

## 10. Monitoring
- Micrometer counters: pings.accepted, pings.duplicate, pings.stale, pings.future, pings.out_of_order, history.dropped, geofence.entry, geofence.exit, gap.recoveries, notifications.failed.
- Latency timer on ingestion. Structured logs with vehicleId in the MDC.
- Never use vehicleId as a metric tag (unbounded cardinality).

## 11. Error handling
Global `@RestControllerAdvice` returns `{code, message, timestamp}` with no stack traces. Bean Validation on all request DTOs. 400 validation, 401/403 auth, 404 unknown resource, 409 conflict, 503 when the DB write fails. DUPLICATE, STALE, OUT_OF_ORDER, REJECTED_FUTURE are normal responses, not errors.

## 12. API plan
| Endpoint | Role | Purpose |
|---|---|---|
| POST /api/auth/login | public | Returns a JWT |
| POST /api/pings | device API key | Returns ACCEPTED, DUPLICATE, STALE, OUT_OF_ORDER or REJECTED_FUTURE |
| GET /api/vehicles/{id}/position | admin, approver | Latest known fix |
| GET /api/vehicles/{id}/events | admin, approver | ENTRY/EXIT history |
| CRUD /api/geofences | admin | Manage zones; evicts cache and rebuilds grid |
| CRUD /api/devices | admin | Device registry |
| CRUD /api/routes | admin | Routes and stops (or seed data) |
| GET /api/pickups/me/eta | employee | Own pickup ETA |
| GET /actuator/metrics | admin | Counters |

## 13. File structure
```
vehicle-tracking-service/
  pom.xml, README.md, .gitignore, docker-compose.yml
  docs/ PLAN.md, architecture.md, er-diagram.png, demo/
  postman/vehicle-tracking.postman_collection.json
  src/main/java/com/movinsync/tracking/
    TrackingApplication.java
    config/      SecurityConfig, CacheConfig, ClockConfig, OpenApiConfig, TrackingProperties
    security/    JwtService, JwtAuthFilter, DeviceApiKeyFilter, Role
    controller/  Auth, Ping, Geofence, Device, Route, Vehicle, Event, Pickup
    service/     IngestionService, GeofenceEngine, EtaService, DeviceRegistryService,
                 GeofenceService, NotificationService, HistoryWriter
    domain/geo/  Geofence (abstract), CircleGeofence, RectangleGeofence, TimeWindow,
                 GeoPoint, GridIndex, GeoUtils
    domain/state/ VehicleState, ZoneState, PingResult
    entity/      Device, Vehicle, GeofenceEntity, GeofenceEvent, LocationPing,
                 Route, RouteStop, PickupRequest, AppUser
    repository/, dto/request|response/, event/, exception/, metrics/
    simulator/   SimulatorRunner (profile "sim")
  src/test/java/ ... GeoUtilsTest, CircleRectangleTest, TimeWindowTest, GridIndexTest,
                 GeofenceEngineTest, IngestionServiceTest, EtaServiceTest,
                 PingControllerTest, SecurityTest, DemoScenarioIT
```

## 14. Test plan
Test database: use Testcontainers PostgreSQL (requires Docker) for integration tests; pure unit tests need no DB. All time-dependent tests use a fixed/controllable Clock.

- Geometry: point on circle boundary, inside, outside; rectangle corner; window wrapping midnight.
- Ingestion: duplicate, older-after-newer, stale, future; 50 concurrent threads on one vehicle; DB unique violation maps to DUPLICATE; DB failure during transition leaves memory unchanged and returns 503.
- Geofence: enter fires once; 10 pings inside fire nothing; exit fires once; EXIT fires after leaving the grid cell; jitter does not flap; first ping inside fires nothing; window opens while inside gives no ENTRY; window closes while inside then exit gives no EXIT; vehicle not in allowlist ignored.
- Gap and restart: inside, dark 2 min, inside again gives no event; outside after gap gives one EXIT with detectedAfterGap; restart with persisted state gives no false events.
- ETA: off-route point snaps correctly; stopped vehicle uses speed floor; passed stop marked PASSED; every remaining stop gets an ETA; route change resets snap index and ETA map.
- Security and caching: device key mismatch rejected; employee gets 403 on someone else's ETA; metrics endpoint requires ADMIN; zone update evicts cache and rebuilds grid.
- Scale: 10,000 vehicles and 1,000 zones, naive scan vs grid index.
- History queue: when full, rows drop, `history.dropped` increments, ingestion not blocked.

Demo scenario (also an integration test, DemoScenarioIT): a cab about 1.2 km away approaches a pickup (it must START outside the zone), crosses the 300 m geofence, ENTRY fires once and 'arriving' is logged. A ping from 3 minutes ago arrives after a fresh one and is ignored.

## 15. Complexity
| Operation | Time | Space |
|---|---|---|
| Ingestion (dedup, monotonic check) | O(1) amortised | bounded dedup cache |
| Grid lookup | O(1 + k), k candidate zones | O(Z*c) index |
| Grid build | O(Z*c), c = cells per zone | |
| Geofence evaluate | O(k + zones currently inside) | O(V + inside pairs) |
| ETA snapping | O(w) near last index, O(S) fallback | O(S) cumulative distances |
| ETA for all stops | O(M), M remaining stops | O(M) |
| Naive baseline | O(Z) per ping | |

## 16. Trade-offs (README section)
- In-memory state, single instance: fast and simple, not horizontally scalable. Scale out by partitioning by vehicleId (e.g. Kafka keys).
- Uniform grid vs geohash or R-tree: simpler and O(1), weaker with widely varying zone sizes.
- Late older pings are history only: a crossing contained only in a late ping is missed. Accepted to keep the live position monotonic.
- Silent init on first ping: avoids a false ENTRY, but a vehicle that starts inside a zone never gets an ENTRY.
- Persist-then-commit-to-memory: keeps DB and memory consistent, at the cost of one DB round trip inside the lock for rare transitions.
- Synchronous transitions, async history with a bounded queue: critical writes are durable, hot path stays fast; history can be dropped under overload (counted), and a few seconds of latest-position state can be lost on crash and is reconciled on the next ping.
- Lightweight outbox instead of Kafka: enough for this scope, with a clear production path.
- Hysteresis removes flapping but delays EXIT slightly.
- Route-polyline distance vs straight-line: polyline, because roads curve.
- Cache TTL vs consistency: explicit eviction on write, TTL as safety net.

## 17. Requirement traceability
| Case study requirement | Where covered |
|---|---|
| Ping fields, device to vehicle and business unit | 5, 6.1 |
| Idempotent, ordered, stale | 3, 6.1 |
| Fire once, circle and rectangle, point-in-zone test | 6.2, 6.3 |
| Time-windowed and device-specific zones | 3, 6.2, 6.3 |
| Arriving notification | 5 (purpose, stopId), 7 |
| Snap to route, ETA per employee | 6.5 |
| Straight-line vs road, recompute frequency | 3, 6.5, 16 |
| 10k vehicles, spatial index | 6.4, 14, 15 |
| Gap recovery | 6.6 |
| Auth, complexity, failure, OOP, trade-offs, monitoring, caching, errors | 8, 15, 7, 5, 16, 10, 9, 11 |
| README, public repo, commit history, demo | 18, 20 |

## 18. Roadmap with commits (steps 1 and the DB connection are already done)
| Step | Work | Commit message |
|---|---|---|
| 1 | Project init, PostgreSQL via docker-compose, README skeleton (DONE) | chore: init project |
| 2 | Entities, repositories, seed data (devices, route, stops, zones) | feat: data model |
| 3 | GeoUtils (haversine, projection), Circle, Rectangle, TimeWindow, Clock bean, tests | feat: geometry |
| 4 | Ingestion: validation, dedup, monotonic, stale, future, striped locks, bounded history queue | feat: idempotent ping ingestion |
| 5 | Geofence state machine, hysteresis, time windows, vehicle filter, silent first ping, persist-then-commit | feat: geofence entry/exit |
| 6 | GridIndex with atomic swap, EXIT union, benchmark | perf: spatial index |
| 7 | ETA with route snapping, cumulative distances, per-stop ETA map | feat: eta |
| 8 | Gap recovery, state persistence, restart reconcile | feat: gap recovery |
| 9 | JWT with roles, device API key, actuator security | feat: security |
| 10 | Caching, metrics, exception handler, AFTER_COMMIT notifications with retry | feat: observability, caching |
| 11 | Remaining tests, Postman collection, SimulatorRunner, load test | test: edge cases, simulator |
| 12 | README (trade-offs, complexity, assumptions), ER diagram, demo | docs: final |

### Simulator (step 11 deliverable)
`SimulatorRunner` (Spring profile `sim` or a standalone main) sends pings over HTTP with the device API key. Script: start about 1.2 km from the pickup (outside the zone) and move toward it every 5 s; send a duplicate ping; send one ping stamped 3 minutes earlier after a fresh one; pause 2 minutes (simulated clock acceptable) and resume; cross the 300 m zone for ENTRY, continue to the office gate for ENTRY/EXIT. Print each response status and the resulting events.

## 19. Resolved decisions
- Notification: log 'cab arriving' and store the event, readable via the events endpoint. Add SSE only if time remains.
- Event handling: AFTER_COMMIT listener with @Async; PENDING events retried by @Scheduled. Mention Kafka in README.
- Gap policy: keep state and fire a flagged event.
- Auth depth: full JWT with three roles.
- Demo: a 2 to 3 minute video of the example use case plus screenshots in docs/demo/.

## 20. Deliverables checklist
- Public GitHub repository with a meaningful commit history
- README: how to run, assumptions, trade-offs, complexity table, what becomes stale, point-in-zone test
- ER diagram in docs/
- Postman collection or curl examples
- Unit and integration tests including the edge cases in section 14
- Simulator and demo video and screenshots

## 21. Working rules for the coding agent
1. Implement ONE roadmap step at a time, with its tests. Run `mvn test`.
2. Make one git commit per step using the message above, then stop and summarise what changed and any deviation.
3. Use constructor injection, an injectable Clock, no business logic in controllers, no stack traces in API responses.
4. If the plan is ambiguous or a deviation seems needed, ask instead of guessing.
