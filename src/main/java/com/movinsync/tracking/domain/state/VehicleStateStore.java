package com.movinsync.tracking.domain.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for vehicle live state.
 * Key = vehicleId, Value = VehicleState.
 */
@Component
public class VehicleStateStore {

    private final Map<Long, VehicleState> states = new ConcurrentHashMap<>();

    public VehicleState getOrCreate(long vehicleId) {
        return states.computeIfAbsent(vehicleId, VehicleState::new);
    }

    public VehicleState get(long vehicleId) {
        return states.get(vehicleId);
    }

    public void put(long vehicleId, VehicleState state) {
        states.put(vehicleId, state);
    }

    public Map<Long, VehicleState> getAll() {
        return states;
    }
}
