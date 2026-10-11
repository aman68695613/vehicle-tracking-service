# Vehicle Tracking Service

A Spring Boot 4.x micro-service that processes high-throughput GPS pings, evaluates geofence rules, computes route ETAs, and exposes a secured REST API. Built with PostgreSQL, Caffeine caching, Micrometer metrics, and stateless JWT auth.

## Quick Start (Docker)

```bash
export DB_PASSWORD=yourpassword
docker compose up --build
```

| Service | Address |
|---------|---------|
| App (REST API) | http://localhost:8081 |
| PostgreSQL | localhost:5433 |

> Tests run automatically against an in-memory H2 database via `application-test.properties` — no Postgres needed for `./mvnw test`.

## Core Features

- **High-throughput Ingestion** — Future/stale validation, dedup cache, strict monotonicity, striped locking for per-vehicle concurrency.
- **Geofence Engine** — Circle + Rectangle shapes, hysteresis margins, time-windowed access rules, vehicle-level filters, gap detection.
- **Spatial Grid Index** — O(1) candidate lookup using a pre-built spatial grid; rebuilt on every geofence reload.
- **ETA Service** — Projects vehicle position onto a polyline, computes per-stop ETA with EMA speed smoothing and a publish-threshold to suppress jitter.
- **State Recovery** — Snapshots persisted to `vehicle_state_snapshots`; replayed on restart.
- **Outbox pattern** — Geofence events written with `PENDING` status; a `@Scheduled` publisher marks them `SENT` by logging (simulated delivery — no real Kafka/SNS integration).
- **Security** — JWT for human users (ADMIN / APPROVER / EMPLOYEE), SHA-256 device API key for devices.
- **Observability** — Micrometer `Timer` for ping processing latency, `Counter` for dropped history entries, and a `Gauge` for queue size. Exposed at `/actuator/metrics` and `/actuator/prometheus`.

## ER Diagram

> **Note:** `GEOFENCE_EVENT` and `LOCATION_PING` store plain `vehicle_id`/`zone_id` columns — these are logical foreign keys only, not database-enforced constraints.

```mermaid
erDiagram
    VEHICLE {
        bigint id PK
        string registration_number
        string business_unit
        bigint active_route_id FK
    }

    DEVICE {
        bigint id PK
        string device_id
        string api_key_hash
        bigint vehicle_id FK
    }

    ROUTE {
        bigint id PK
        string name
        text polyline_json
    }

    ROUTE_STOP {
        bigint id PK
        bigint route_id FK
        int sequence_index
        string name
        double latitude
        double longitude
        double cumulative_distance_metres
    }

    GEOFENCE_ZONE {
        bigint id PK
        string name
        string shape
        string purpose
        bigint stop_id FK
        double centre_lat
        double centre_lon
        double radius_metres
        double min_lat
        double max_lat
        double min_lon
        double max_lon
        double margin_metres
        time window_start
        time window_end
        string window_timezone
        text allowed_vehicle_ids
    }

    APP_USER {
        bigint id PK
        string username
        string password_hash
        string role
    }

    PICKUP_REQUEST {
        bigint id PK
        bigint employee_id FK
        bigint route_stop_id FK
        boolean active
    }

    GEOFENCE_EVENT {
        bigint id PK
        bigint vehicle_id
        bigint zone_id
        string event_type
        bigint timestamp_ms
        boolean detected_after_gap
        string status
    }

    LOCATION_PING {
        bigint id PK
        string device_id
        bigint vehicle_id
        double latitude
        double longitude
        double speed
        double bearing
        bigint timestamp_ms
    }

    VEHICLE_STATE_SNAPSHOT {
        bigint vehicle_id PK
        double last_latitude
        double last_longitude
        bigint last_timestamp_ms
        double speed_ema
        int last_snapped_index
        text zone_states_json
        text eta_map_json
    }

    VEHICLE ||--o| DEVICE : "has"
    ROUTE ||--o{ ROUTE_STOP : "contains"
    VEHICLE }o--|| ROUTE : "assigned to"
    ROUTE_STOP ||--o{ GEOFENCE_ZONE : "associated with"
    APP_USER ||--o{ PICKUP_REQUEST : "makes"
    ROUTE_STOP ||--o{ PICKUP_REQUEST : "is target of"
    VEHICLE ||--o{ GEOFENCE_EVENT : "triggers"
    GEOFENCE_ZONE ||--o{ GEOFENCE_EVENT : "records"
    VEHICLE ||--o{ LOCATION_PING : "produces"
    VEHICLE ||--o| VEHICLE_STATE_SNAPSHOT : "snapshots"
```

## API Quick Reference

Ping auth requires **both** `X-Device-ID` and `X-Api-Key` headers. All other endpoints (except `/api/auth/login` and `/actuator/health`) require `Authorization: Bearer <token>`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/login` | — | Returns JWT token |
| `POST` | `/api/pings` | `X-Device-ID` + `X-Api-Key` | Ingest GPS ping |
| `GET` | `/api/vehicles/{id}/position` | ADMIN, APPROVER | Last known position |
| `GET` | `/api/vehicles/{id}/events` | ADMIN, APPROVER | Geofence events |
| `GET` | `/api/pickups/me/eta` | EMPLOYEE | Employee's ETA to pickup stop |
| `GET` | `/api/routes` | ADMIN | List all routes |
| `POST` | `/api/routes` | ADMIN | Create route |
| `GET` | `/api/geofences` | ADMIN | List all geofence zones |
| `POST` | `/api/geofences` | ADMIN | Create geofence zone |
| `GET` | `/api/devices` | ADMIN | List devices (no key hash exposed) |
| `POST` | `/api/devices` | ADMIN | Register device (hashes API key server-side) |
| `GET` | `/actuator/health` | — | Health check |
| `GET` | `/actuator/metrics` | ADMIN | Metrics |
| `GET` | `/actuator/prometheus` | ADMIN | Prometheus scrape |

## curl Walkthrough

Run from a clean `docker compose up --build` with the seeded data.

```bash
# 1. Login as admin (captures token)
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

# 2. Send a GPS ping — BOTH X-Device-ID and X-Api-Key are required
curl -X POST http://localhost:8081/api/pings \
  -H "X-Device-ID: DEV-001" \
  -H "X-Api-Key: test-api-key-001" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"DEV-001","lat":12.935,"lon":77.610,"speed":10.0,"bearing":90.0,"ts":'$(date +%s000)'}'

# 3. Get vehicle position (send at least one ping first)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/vehicles/1/position

# 4. Get geofence events
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/vehicles/1/events

# 5. Employee ETA (requires a ping so the vehicle has a position)
EMP_TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"employee1","password":"emp123"}' | jq -r .token)
curl -H "Authorization: Bearer $EMP_TOKEN" http://localhost:8081/api/pickups/me/eta

# 6. List routes
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/routes
```

## Seeded Test Data

| Entity | Value |
|--------|-------|
| Vehicle | `KA-01-AB-1234`, route `Route-EC-MG` |
| Device | `DEV-001`, API key `test-api-key-001` |
| Admin user | `admin` / `admin123` |
| Approver user | `approver` / `approver123` |
| Employee user | `employee1` / `emp123` |

## Running Tests

```bash
./mvnw test
```

Uses H2 in-memory via `src/test/resources/application-test.properties`. No Postgres needed.

## Running Locally (without Docker)

```bash
# 1. Start Postgres on port 5433 first
docker compose up -d postgres

# 2. Run the application
export DB_PASSWORD=yourpassword
./mvnw spring-boot:run
```

## Known Limitations

| Area | Note |
|------|------|
| Outbox publishing | Events are marked SENT after a log statement — no real Kafka/SNS/webhook integration. |
| Periodic state flush | Every vehicle snapshot is written every 5 seconds regardless of changes (no dirty flag). Acceptable for this scale. |
| Geofence/Device validation | Shape-specific fields are validated in the controller; bean-validation (`@Valid`) annotations are not yet on the request body. |
