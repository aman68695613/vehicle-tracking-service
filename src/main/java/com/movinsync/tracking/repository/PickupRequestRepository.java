package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.PickupRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PickupRequestRepository extends JpaRepository<PickupRequest, Long> {
    List<PickupRequest> findByRouteStopRouteIdAndActiveTrue(Long routeId);
    Optional<PickupRequest> findByEmployeeIdAndActiveTrue(Long employeeId);
}
