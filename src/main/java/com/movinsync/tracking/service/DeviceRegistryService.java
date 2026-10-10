package com.movinsync.tracking.service;

import com.movinsync.tracking.entity.Device;
import com.movinsync.tracking.entity.Vehicle;
import com.movinsync.tracking.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Looks up device-to-vehicle mappings. Will be backed by Caffeine cache in step 10.
 */
@Service
public class DeviceRegistryService {

    private final DeviceRepository deviceRepository;

    public DeviceRegistryService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Look up the device by deviceId.
     */
    public Optional<Device> findDevice(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId);
    }

    /**
     * Resolve deviceId to its Vehicle.
     */
    public record DeviceVehicleInfo(Device device, Vehicle vehicle, String businessUnit) {}

    public Optional<DeviceVehicleInfo> resolve(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(d -> new DeviceVehicleInfo(d, d.getVehicle(), d.getVehicle().getBusinessUnit()));
    }
}
