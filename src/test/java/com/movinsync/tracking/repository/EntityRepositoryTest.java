package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for entities, repositories, and relationships created in Step 2.
 * Uses H2 in-memory database.
 */
@DataJpaTest
@ActiveProfiles("test")
class EntityRepositoryTest {

    @Autowired private VehicleRepository vehicleRepo;
    @Autowired private DeviceRepository deviceRepo;
    @Autowired private RouteRepository routeRepo;
    @Autowired private RouteStopRepository routeStopRepo;
    @Autowired private GeofenceRepository geofenceRepo;
    @Autowired private GeofenceEventRepository eventRepo;
    @Autowired private LocationPingRepository pingRepo;
    @Autowired private AppUserRepository appUserRepo;
    @Autowired private PickupRequestRepository pickupRequestRepo;
    @Autowired private VehicleStateSnapshotRepository snapshotRepo;

    @Test
    void vehicleAndDevice_relationship() {
        Vehicle vehicle = vehicleRepo.save(Vehicle.builder()
                .registrationNumber("KA-01-XX-9999")
                .businessUnit("BU-TEST")
                .build());

        Device device = deviceRepo.save(Device.builder()
                .deviceId("DEV-TEST-001")
                .apiKeyHash("abc123hash")
                .vehicle(vehicle)
                .build());

        Device found = deviceRepo.findByDeviceId("DEV-TEST-001").orElseThrow();
        assertEquals(vehicle.getId(), found.getVehicle().getId());
    }

    @Test
    void device_uniqueDeviceId() {
        Vehicle v1 = vehicleRepo.save(Vehicle.builder()
                .registrationNumber("KA-01-A-0001")
                .businessUnit("BU")
                .build());
        Vehicle v2 = vehicleRepo.save(Vehicle.builder()
                .registrationNumber("KA-01-A-0002")
                .businessUnit("BU")
                .build());

        deviceRepo.save(Device.builder().deviceId("DUPE").apiKeyHash("h1").vehicle(v1).build());
        assertThrows(DataIntegrityViolationException.class, () -> {
            deviceRepo.saveAndFlush(Device.builder().deviceId("DUPE").apiKeyHash("h2").vehicle(v2).build());
        });
    }

    @Test
    void routeWithStops_orderedBySequenceIndex() {
        Route route = routeRepo.save(Route.builder().name("Test Route").build());

        routeStopRepo.save(RouteStop.builder()
                .route(route).sequenceIndex(1).name("Stop B")
                .latitude(12.9).longitude(77.6).cumulativeDistanceMetres(500.0)
                .build());
        routeStopRepo.save(RouteStop.builder()
                .route(route).sequenceIndex(0).name("Stop A")
                .latitude(12.8).longitude(77.5).cumulativeDistanceMetres(0.0)
                .build());

        List<RouteStop> stops = routeStopRepo.findByRouteIdOrderBySequenceIndexAsc(route.getId());
        assertEquals(2, stops.size());
        assertEquals("Stop A", stops.get(0).getName());
        assertEquals("Stop B", stops.get(1).getName());
    }

    @Test
    void vehicleWithActiveRoute() {
        Route route = routeRepo.save(Route.builder().name("Route 1").build());
        Vehicle vehicle = vehicleRepo.save(Vehicle.builder()
                .registrationNumber("KA-02-XX-1111")
                .businessUnit("BU-TEST")
                .activeRoute(route)
                .build());

        Vehicle found = vehicleRepo.findById(vehicle.getId()).orElseThrow();
        assertNotNull(found.getActiveRoute());
        assertEquals("Route 1", found.getActiveRoute().getName());
    }

    @Test
    void circleGeofence_persisted() {
        GeofenceEntity zone = geofenceRepo.save(GeofenceEntity.builder()
                .name("Circle Zone")
                .shape(GeofenceShape.CIRCLE)
                .purpose(GeofencePurpose.PICKUP)
                .centreLat(12.93).centreLon(77.61)
                .radiusMetres(300.0)
                .build());

        GeofenceEntity found = geofenceRepo.findById(zone.getId()).orElseThrow();
        assertEquals(GeofenceShape.CIRCLE, found.getShape());
        assertEquals(300.0, found.getRadiusMetres());
    }

    @Test
    void rectangleGeofence_persisted() {
        GeofenceEntity zone = geofenceRepo.save(GeofenceEntity.builder()
                .name("Rect Zone")
                .shape(GeofenceShape.RECTANGLE)
                .purpose(GeofencePurpose.OFFICE_GATE)
                .minLat(12.97).maxLat(12.98)
                .minLon(77.60).maxLon(77.62)
                .marginMetres(10.0)
                .build());

        GeofenceEntity found = geofenceRepo.findById(zone.getId()).orElseThrow();
        assertEquals(GeofenceShape.RECTANGLE, found.getShape());
        assertEquals(10.0, found.getMarginMetres());
    }

    @Test
    void timeWindowedGeofence_persisted() {
        GeofenceEntity zone = geofenceRepo.save(GeofenceEntity.builder()
                .name("Night Zone")
                .shape(GeofenceShape.CIRCLE)
                .purpose(GeofencePurpose.RESTRICTED)
                .centreLat(12.95).centreLon(77.60)
                .radiusMetres(200.0)
                .windowStart(LocalTime.of(22, 0))
                .windowEnd(LocalTime.of(6, 0))
                .windowTimezone("Asia/Kolkata")
                .build());

        GeofenceEntity found = geofenceRepo.findById(zone.getId()).orElseThrow();
        assertEquals(LocalTime.of(22, 0), found.getWindowStart());
        assertEquals(LocalTime.of(6, 0), found.getWindowEnd());
        assertEquals("Asia/Kolkata", found.getWindowTimezone());
    }

    @Test
    void locationPing_uniqueConstraint() {
        pingRepo.save(LocationPing.builder()
                .deviceId("DEV-1").vehicleId(1L)
                .timestampMs(1000L).latitude(12.9).longitude(77.6)
                .build());

        assertThrows(DataIntegrityViolationException.class, () -> {
            pingRepo.saveAndFlush(LocationPing.builder()
                    .deviceId("DEV-1").vehicleId(1L)
                    .timestampMs(1000L).latitude(12.9).longitude(77.6)
                    .build());
        });
    }

    @Test
    void locationPing_differentTimestamp_allowed() {
        pingRepo.save(LocationPing.builder()
                .deviceId("DEV-1").vehicleId(1L)
                .timestampMs(1000L).latitude(12.9).longitude(77.6)
                .build());
        pingRepo.save(LocationPing.builder()
                .deviceId("DEV-1").vehicleId(1L)
                .timestampMs(2000L).latitude(12.91).longitude(77.61)
                .build());

        assertEquals(2, pingRepo.count());
    }

    @Test
    void geofenceEvent_persisted() {
        GeofenceEvent event = eventRepo.save(GeofenceEvent.builder()
                .eventType(GeofenceEventType.ENTRY)
                .vehicleId(1L).zoneId(1L)
                .timestampMs(5000L)
                .detectedAfterGap(false)
                .status(EventStatus.PENDING)
                .build());

        List<GeofenceEvent> events = eventRepo.findByVehicleIdOrderByTimestampMsDesc(1L);
        assertEquals(1, events.size());
        assertEquals(GeofenceEventType.ENTRY, events.get(0).getEventType());
    }

    @Test
    void pendingEvents_foundByStatus() {
        eventRepo.save(GeofenceEvent.builder()
                .eventType(GeofenceEventType.ENTRY).vehicleId(1L).zoneId(1L)
                .timestampMs(5000L).status(EventStatus.PENDING)
                .build());
        eventRepo.save(GeofenceEvent.builder()
                .eventType(GeofenceEventType.EXIT).vehicleId(1L).zoneId(1L)
                .timestampMs(6000L).status(EventStatus.SENT)
                .build());

        List<GeofenceEvent> pending = eventRepo.findByStatus(EventStatus.PENDING, org.springframework.data.domain.Pageable.unpaged());
        assertEquals(1, pending.size());
        assertEquals(GeofenceEventType.ENTRY, pending.get(0).getEventType());
    }

    @Test
    void appUser_roles() {
        appUserRepo.save(AppUser.builder()
                .username("testadmin").passwordHash("hash").role(Role.ADMIN)
                .build());
        appUserRepo.save(AppUser.builder()
                .username("testemp").passwordHash("hash").role(Role.EMPLOYEE)
                .build());

        AppUser admin = appUserRepo.findByUsername("testadmin").orElseThrow();
        assertEquals(Role.ADMIN, admin.getRole());
    }

    @Test
    void vehicleStateSnapshot_persistAndRecover() {
        VehicleStateSnapshot snapshot = snapshotRepo.save(VehicleStateSnapshot.builder()
                .vehicleId(99L)
                .lastLatitude(12.9).lastLongitude(77.6)
                .lastTimestampMs(10000L)
                .speedEma(8.5)
                .lastSnappedIndex(3)
                .zoneStatesJson("{\"1\":true}")
                .etaMapJson("{\"2\":{\"eta\":120}}")
                .build());

        VehicleStateSnapshot found = snapshotRepo.findById(99L).orElseThrow();
        assertEquals(12.9, found.getLastLatitude());
        assertEquals("{\"1\":true}", found.getZoneStatesJson());
    }

    @Test
    void pickupRequest_byEmployee() {
        AppUser emp = appUserRepo.save(AppUser.builder()
                .username("emp2").passwordHash("hash").role(Role.EMPLOYEE)
                .build());

        Route route = routeRepo.save(Route.builder().name("R1").build());
        RouteStop stop = routeStopRepo.save(RouteStop.builder()
                .route(route).sequenceIndex(0).name("S1")
                .latitude(12.9).longitude(77.6).cumulativeDistanceMetres(0.0)
                .build());

        pickupRequestRepo.save(PickupRequest.builder()
                .routeStop(stop).employee(emp).active(true)
                .build());

        assertTrue(pickupRequestRepo.findByEmployeeIdAndActiveTrue(emp.getId()).isPresent());
    }

    @Test
    void pickupRequest_byRoute() {
        AppUser emp = appUserRepo.save(AppUser.builder()
                .username("emp3").passwordHash("hash").role(Role.EMPLOYEE)
                .build());

        Route route = routeRepo.save(Route.builder().name("R2").build());
        RouteStop stop = routeStopRepo.save(RouteStop.builder()
                .route(route).sequenceIndex(0).name("S1")
                .latitude(12.9).longitude(77.6).cumulativeDistanceMetres(0.0)
                .build());

        pickupRequestRepo.save(PickupRequest.builder()
                .routeStop(stop).employee(emp).active(true)
                .build());

        List<PickupRequest> requests = pickupRequestRepo.findByRouteStopRouteIdAndActiveTrue(route.getId());
        assertEquals(1, requests.size());
    }

    @Test
    void vehicle_findByRegistrationNumber() {
        vehicleRepo.save(Vehicle.builder()
                .registrationNumber("KA-99-ZZ-0001")
                .businessUnit("BU")
                .build());

        assertTrue(vehicleRepo.findByRegistrationNumber("KA-99-ZZ-0001").isPresent());
        assertTrue(vehicleRepo.findByRegistrationNumber("NONEXISTENT").isEmpty());
    }
}
