package com.movinsync.tracking.config;

import com.movinsync.tracking.entity.*;
import com.movinsync.tracking.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.HexFormat;

/**
 * Seeds the database with sample devices, vehicles, a route with stops,
 * geofence zones, users, and pickup requests for development and demo.
 * <p>
 * Only runs if no vehicles exist yet (idempotent).
 */
@Configuration
public class SeedDataConfig {

    private static final Logger log = LoggerFactory.getLogger(SeedDataConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CommandLineRunner seedData(
            VehicleRepository vehicleRepo,
            DeviceRepository deviceRepo,
            RouteRepository routeRepo,
            RouteStopRepository routeStopRepo,
            GeofenceRepository geofenceRepo,
            AppUserRepository appUserRepo,
            PickupRequestRepository pickupRequestRepo,
            PasswordEncoder passwordEncoder,
            com.movinsync.tracking.service.GeofenceService geofenceService) {

        return args -> {
            if (vehicleRepo.count() > 0) {
                log.info("Seed data already present, skipping.");
                return;
            }

            log.info("Seeding database...");

            // ---- Route ----
            // A sample route in Bangalore: Electronic City -> Silk Board -> Koramangala -> MG Road
            Route route = routeRepo.save(Route.builder()
                    .name("Route-EC-MG")
                    .polylineJson("[[12.8399,77.6770],[12.8450,77.6750],[12.8500,77.6730],"
                            + "[12.8600,77.6700],[12.8700,77.6680],[12.8800,77.6650],"
                            + "[12.9000,77.6600],[12.9170,77.6230],[12.9350,77.6100],"
                            + "[12.9500,77.6050],[12.9716,77.6095]]")
                    .build());

            // ---- Route Stops ----
            RouteStop stop1 = routeStopRepo.save(RouteStop.builder()
                    .route(route).sequenceIndex(0).name("Electronic City Phase 1")
                    .latitude(12.8399).longitude(77.6770).cumulativeDistanceMetres(0.0)
                    .build());

            RouteStop stop2 = routeStopRepo.save(RouteStop.builder()
                    .route(route).sequenceIndex(1).name("Silk Board Junction")
                    .latitude(12.9170).longitude(77.6230).cumulativeDistanceMetres(11376.0)
                    .build());

            RouteStop stop3 = routeStopRepo.save(RouteStop.builder()
                    .route(route).sequenceIndex(2).name("Koramangala Pickup")
                    .latitude(12.9350).longitude(77.6100).cumulativeDistanceMetres(13824.0)
                    .build());

            RouteStop stop4 = routeStopRepo.save(RouteStop.builder()
                    .route(route).sequenceIndex(3).name("MG Road Office")
                    .latitude(12.9716).longitude(77.6095).cumulativeDistanceMetres(18028.0)
                    .build());

            // ---- Vehicle ----
            Vehicle vehicle = vehicleRepo.save(Vehicle.builder()
                    .registrationNumber("KA-01-AB-1234")
                    .businessUnit("BU-BANGALORE")
                    .activeRoute(route)
                    .build());

            // ---- Device (API key: "test-api-key-001") ----
            deviceRepo.save(Device.builder()
                    .deviceId("DEV-001")
                    .apiKeyHash(sha256("test-api-key-001"))
                    .vehicle(vehicle)
                    .build());

            // ---- Geofence: circle around Koramangala Pickup (300 m) ----
            geofenceRepo.save(GeofenceEntity.builder()
                    .name("Koramangala Pickup Zone")
                    .shape(GeofenceShape.CIRCLE)
                    .purpose(GeofencePurpose.PICKUP)
                    .stopId(stop3.getId())
                    .centreLat(12.9350).centreLon(77.6100)
                    .radiusMetres(300.0)
                    .marginMetres(0.0)
                    .build());

            // ---- Geofence: rectangle around MG Road Office Gate ----
            geofenceRepo.save(GeofenceEntity.builder()
                    .name("MG Road Office Gate")
                    .shape(GeofenceShape.RECTANGLE)
                    .purpose(GeofencePurpose.OFFICE_GATE)
                    .stopId(stop4.getId())
                    .minLat(12.9700).maxLat(12.9730)
                    .minLon(77.6070).maxLon(77.6120)
                    .marginMetres(10.0)
                    .build());

            // ---- Geofence: time-windowed restricted zone (circle) ----
            geofenceRepo.save(GeofenceEntity.builder()
                    .name("Restricted Night Zone")
                    .shape(GeofenceShape.CIRCLE)
                    .purpose(GeofencePurpose.RESTRICTED)
                    .centreLat(12.9500).centreLon(77.6050)
                    .radiusMetres(200.0)
                    .marginMetres(0.0)
                    .windowStart(LocalTime.of(22, 0))
                    .windowEnd(LocalTime.of(6, 0))
                    .windowTimezone("Asia/Kolkata")
                    .build());

            // ---- Users ----
            AppUser admin = appUserRepo.save(AppUser.builder()
                    .username("admin")
                    .passwordHash(passwordEncoder.encode("admin123"))
                    .role(Role.ADMIN)
                    .build());

            AppUser approver = appUserRepo.save(AppUser.builder()
                    .username("approver")
                    .passwordHash(passwordEncoder.encode("approver123"))
                    .role(Role.APPROVER)
                    .build());

            AppUser employee = appUserRepo.save(AppUser.builder()
                    .username("employee1")
                    .passwordHash(passwordEncoder.encode("emp123"))
                    .role(Role.EMPLOYEE)
                    .build());

            // ---- Pickup request: employee1 at Koramangala ----
            pickupRequestRepo.save(PickupRequest.builder()
                    .routeStop(stop3)
                    .employee(employee)
                    .active(true)
                    .build());

            log.info("Seed data loaded: 1 vehicle, 1 device, 1 route with 4 stops, "
                    + "3 geofence zones, 3 users, 1 pickup request.");
                    
            geofenceService.reloadZones();
        };
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
