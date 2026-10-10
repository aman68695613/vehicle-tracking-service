package com.movinsync.tracking.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.movinsync.tracking.entity.Device;
import com.movinsync.tracking.entity.Vehicle;
import com.movinsync.tracking.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Looks up device-to-vehicle mappings. Will be backed by Caffeine cache in step 10.
 */
@Service
public class DeviceRegistryService {

    private final DeviceRepository deviceRepository;
    private final Cache<String, DeviceVehicleInfo> deviceCache;

    public DeviceRegistryService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
        this.deviceCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /**
     * Look up the device by deviceId.
     */
    public Optional<Device> findDevice(String deviceId) {
        return resolve(deviceId).map(DeviceVehicleInfo::device);
    }

    /**
     * Resolve deviceId to its Vehicle.
     */
    public record DeviceVehicleInfo(Device device, Vehicle vehicle, String businessUnit) {}

    public Optional<DeviceVehicleInfo> resolve(String deviceId) {
        DeviceVehicleInfo info = deviceCache.getIfPresent(deviceId);
        if (info != null) {
            return Optional.of(info);
        }
        Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
        if (deviceOpt.isPresent()) {
            Device d = deviceOpt.get();
            DeviceVehicleInfo newInfo = new DeviceVehicleInfo(d, d.getVehicle(), d.getVehicle().getBusinessUnit());
            deviceCache.put(deviceId, newInfo);
            return Optional.of(newInfo);
        }
        return Optional.empty();
    }
}
