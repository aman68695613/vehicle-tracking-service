package com.movinsync.tracking.domain.geo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Time window for a geofence zone. Wraps midnight when start > end.
 * Judged by the ping's timestamp in the zone's timezone.
 */
public record TimeWindow(LocalTime start, LocalTime end, ZoneId timezone) {

    /**
     * Whether the given instant falls inside this time window.
     * Wraps midnight when start > end (e.g., 22:00 to 06:00).
     */
    public boolean isActiveAt(Instant instant) {
        ZonedDateTime zdt = instant.atZone(timezone);
        LocalTime t = zdt.toLocalTime();

        if (start.isBefore(end) || start.equals(end)) {
            // Normal window: e.g. 08:00–17:00
            return !t.isBefore(start) && t.isBefore(end);
        } else {
            // Wraps midnight: e.g. 22:00–06:00 → active if t >= 22:00 OR t < 06:00
            return !t.isBefore(start) || t.isBefore(end);
        }
    }
}
