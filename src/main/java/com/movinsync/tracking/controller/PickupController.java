package com.movinsync.tracking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/pickups")
public class PickupController {

    private final com.movinsync.tracking.repository.AppUserRepository userRepo;
    private final com.movinsync.tracking.repository.PickupRequestRepository pickupRepo;
    private final com.movinsync.tracking.repository.VehicleRepository vehicleRepo;
    private final com.movinsync.tracking.domain.state.VehicleStateStore stateStore;

    public PickupController(com.movinsync.tracking.repository.AppUserRepository userRepo,
                            com.movinsync.tracking.repository.PickupRequestRepository pickupRepo,
                            com.movinsync.tracking.repository.VehicleRepository vehicleRepo,
                            com.movinsync.tracking.domain.state.VehicleStateStore stateStore) {
        this.userRepo = userRepo;
        this.pickupRepo = pickupRepo;
        this.vehicleRepo = vehicleRepo;
        this.stateStore = stateStore;
    }

    @GetMapping("/me/eta")
    @PreAuthorize("hasRole('EMPLOYEE')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<Object> getMyEta(java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        var userOpt = userRepo.findByUsername(principal.getName());
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        var pickupOpt = pickupRepo.findByEmployeeIdAndActiveTrue(userOpt.get().getId());
        if (pickupOpt.isEmpty()) return ResponseEntity.notFound().build();

        com.movinsync.tracking.entity.PickupRequest pickup = pickupOpt.get();
        Long routeId = pickup.getRouteStop().getRoute().getId();
        
        var vehicleOpt = vehicleRepo.findByActiveRouteId(routeId);
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();

        com.movinsync.tracking.domain.state.VehicleState state = stateStore.getOrCreate(vehicleOpt.get().getId());
        var etaEntry = state.getEtaMap().get(pickup.getRouteStop().getId());

        if (etaEntry == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(etaEntry);
    }
}
