package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.Device;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    
    @EntityGraph(attributePaths = {"vehicle", "vehicle.activeRoute", "vehicle.activeRoute.stops"})
    Optional<Device> findByDeviceId(String deviceId);
}
