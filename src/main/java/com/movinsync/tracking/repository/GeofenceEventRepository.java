package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.GeofenceEvent;
import com.movinsync.tracking.entity.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeofenceEventRepository extends JpaRepository<GeofenceEvent, Long> {
    List<GeofenceEvent> findByVehicleIdOrderByTimestampMsDesc(Long vehicleId);
    List<GeofenceEvent> findByStatus(EventStatus status);
}
