package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.VehicleStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleStateSnapshotRepository extends JpaRepository<VehicleStateSnapshot, Long> {
}
