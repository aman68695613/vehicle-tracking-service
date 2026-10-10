# Vehicle Tracking Service

This is a Spring Boot application providing an end-to-end vehicle tracking and geofencing service. It processes high-throughput GPS location pings from devices, executes geofencing rules for pickups, calculates route ETAs, and persists state.

## Core Features
- **High-throughput Ingestion**: GPS ping processing with future/stale validation, duplicate removal, and strict monotonicity enforcement using striped locking.
- **Geofence Engine**: Evaluates spatial constraints based on Haversine distance and geometric shapes. Detects Zone `ENTRY` and `EXIT` events, with gap compensation handling signal loss.
- **ETA Service**: Calculates ETA along polyline geometries and snaps pings to route sequences with caching.
- **REST APIs**: Provides secured API endpoints for Devices, Routes, Pickups, Geofences, and role-based administration.
- **Simulation**: Included simulator generates traffic scenarios, gaps, and tests concurrency rules.

## ER Diagram

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
        string polyline_json
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
        double margin_metres
    }
    
    APP_USER {
        bigint id PK
        string username
        string password
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
        bigint vehicle_id FK
        bigint zone_id FK
        string event_type
        bigint timestamp_ms
        double latitude
        double longitude
        boolean detected_after_gap
    }
    
    VEHICLE ||--o{ DEVICE : "has"
    ROUTE ||--o{ ROUTE_STOP : "contains"
    VEHICLE }o--|| ROUTE : "assigned to"
    ROUTE_STOP ||--o{ GEOFENCE_ZONE : "associated with"
    APP_USER ||--o{ PICKUP_REQUEST : "makes"
    ROUTE_STOP ||--o{ PICKUP_REQUEST : "is target of"
    VEHICLE ||--o{ GEOFENCE_EVENT : "triggers"
    GEOFENCE_ZONE ||--o{ GEOFENCE_EVENT : "records"
```

## Running the Application
Ensure Postgres is running or run in H2 mode for tests.
Run the application via:
```bash
./mvnw spring-boot:run
```
For tests:
```bash
./mvnw test
```
