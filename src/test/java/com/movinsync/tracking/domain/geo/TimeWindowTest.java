package com.movinsync.tracking.domain.geo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TimeWindow, including midnight wrapping.
 */
class TimeWindowTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void normalWindow_insideIsActive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), IST);

        // 10:00 IST → active
        Instant at10 = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at10));
    }

    @Test
    void normalWindow_outsideIsInactive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), IST);

        // 07:00 IST → inactive
        Instant at07 = ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at07));

        // 18:00 IST → inactive
        Instant at18 = ZonedDateTime.of(2026, 10, 10, 18, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at18));
    }

    @Test
    void normalWindow_atStartIsActive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), IST);

        Instant at08 = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at08));
    }

    @Test
    void normalWindow_atEndIsInactive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), IST);

        // end is exclusive
        Instant at17 = ZonedDateTime.of(2026, 10, 10, 17, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at17));
    }

    @Test
    void wrappingMidnight_lateNightIsActive() {
        // 22:00 to 06:00 (wraps midnight)
        TimeWindow tw = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);

        // 23:00 → active
        Instant at23 = ZonedDateTime.of(2026, 10, 10, 23, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at23));

        // 02:00 → active
        Instant at02 = ZonedDateTime.of(2026, 10, 11, 2, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at02));
    }

    @Test
    void wrappingMidnight_daytimeIsInactive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);

        // 12:00 → inactive
        Instant at12 = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at12));

        // 07:00 → inactive
        Instant at07 = ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at07));
    }

    @Test
    void wrappingMidnight_atStartIsActive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);

        Instant at22 = ZonedDateTime.of(2026, 10, 10, 22, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at22));
    }

    @Test
    void wrappingMidnight_atEndIsInactive() {
        TimeWindow tw = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);

        Instant at06 = ZonedDateTime.of(2026, 10, 11, 6, 0, 0, 0, IST).toInstant();
        assertFalse(tw.isActiveAt(at06));
    }

    @Test
    void midnightItself_inWrappingWindow() {
        TimeWindow tw = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), IST);

        Instant midnight = ZonedDateTime.of(2026, 10, 11, 0, 0, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(midnight));
    }

    @Test
    void differentTimezone_convertsCorrectly() {
        // Window 08:00-17:00 UTC
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), ZoneId.of("UTC"));

        // 13:30 IST = 08:00 UTC → active
        Instant at = ZonedDateTime.of(2026, 10, 10, 13, 30, 0, 0, IST).toInstant();
        assertTrue(tw.isActiveAt(at));
    }

    @Test
    void geofence_noTimeWindow_alwaysActive() {
        CircleGeofence zone = new CircleGeofence(1, "Z1",
                new GeoPoint(12.9, 77.6), 100.0, null, java.util.Set.of());

        assertTrue(zone.isActiveAt(Instant.now()));
    }

    @Test
    void geofence_withTimeWindow_respectsWindow() {
        TimeWindow tw = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), IST);
        CircleGeofence zone = new CircleGeofence(1, "Z1",
                new GeoPoint(12.9, 77.6), 100.0, tw, java.util.Set.of());

        Instant at10 = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, IST).toInstant();
        assertTrue(zone.isActiveAt(at10));

        Instant at20 = ZonedDateTime.of(2026, 10, 10, 20, 0, 0, 0, IST).toInstant();
        assertFalse(zone.isActiveAt(at20));
    }
}
