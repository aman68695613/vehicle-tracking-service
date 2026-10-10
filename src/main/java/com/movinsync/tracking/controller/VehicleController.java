package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final com.movinsync.tracking.domain.state.VehicleStateStore stateStore;
    private final com.movinsync.tracking.repository.GeofenceEventRepository eventRepository;

    public VehicleController(com.movinsync.tracking.domain.state.VehicleStateStore stateStore,
                             com.movinsync.tracking.repository.GeofenceEventRepository eventRepository) {
        this.stateStore = stateStore;
        this.eventRepository = eventRepository;
    }

    @GetMapping("/{id}/position")
    @PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
    public ResponseEntity<Object> getPosition(@PathVariable Long id) {
        com.movinsync.tracking.domain.state.VehicleState state = stateStore.getOrCreate(id);
        if (state.getLastPosition() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state.getLastPosition());
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
    public ResponseEntity<Object> getEvents(@PathVariable Long id) {
        return ResponseEntity.ok(eventRepository.findByVehicleIdOrderByTimestampMsDesc(id));
    }
}
