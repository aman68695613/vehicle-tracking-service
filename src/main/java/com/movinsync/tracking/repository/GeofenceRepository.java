package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.GeofenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceRepository extends JpaRepository<GeofenceEntity, Long> {
}
