package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.LocationPing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationPingRepository extends JpaRepository<LocationPing, Long> {
}
