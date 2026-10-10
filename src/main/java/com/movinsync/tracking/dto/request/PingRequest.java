package com.movinsync.tracking.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Ping request from a device.
 */
public record PingRequest(
        @NotNull String deviceId,
        @NotNull @Min(-90) @Max(90) Double lat,
        @NotNull @Min(-180) @Max(180) Double lon,
        @Min(0) Double speed,
        @Min(0) @Max(360) Double bearing,
        @NotNull Long ts
) {}
