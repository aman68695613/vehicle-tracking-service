package com.movinsync.tracking.repository;

import com.movinsync.tracking.entity.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {
    List<RouteStop> findByRouteIdOrderBySequenceIndexAsc(Long routeId);
}
