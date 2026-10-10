package com.movinsync.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable properties for the tracking service.
 */
@ConfigurationProperties(prefix = "tracking")
public record TrackingProperties(
        /** Max future tolerance in milliseconds (default 30s) */
        long futureLimitMs,
        /** Stale threshold in milliseconds (default 5 min) */
        long staleLimitMs,
        /** Gap threshold in milliseconds (default 2 min) */
        long gapThresholdMs,
        /** Minimum speed floor for ETA in m/s (default 2.0) */
        double minSpeedMs,
        /** Traffic factor multiplier for ETA (default 1.2) */
        double trafficFactor,
        /** EMA smoothing factor alpha (default 0.3) */
        double emaAlpha,
        /** ETA publish threshold in seconds (default 15) */
        double etaPublishThresholdSeconds,
        /** Number of striped locks (default 64) */
        int stripedLockCount
) {
    public TrackingProperties {
        if (futureLimitMs <= 0) futureLimitMs = 30_000;
        if (staleLimitMs <= 0) staleLimitMs = 300_000;
        if (gapThresholdMs <= 0) gapThresholdMs = 120_000;
        if (minSpeedMs <= 0) minSpeedMs = 2.0;
        if (trafficFactor <= 0) trafficFactor = 1.2;
        if (emaAlpha <= 0 || emaAlpha > 1) emaAlpha = 0.3;
        if (etaPublishThresholdSeconds <= 0) etaPublishThresholdSeconds = 15.0;
        if (stripedLockCount <= 0) stripedLockCount = 64;
    }
}
