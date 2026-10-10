package com.movinsync.tracking.simulator;

import com.movinsync.tracking.entity.EventStatus;
import com.movinsync.tracking.entity.GeofenceEvent;
import com.movinsync.tracking.entity.GeofenceEventType;
import com.movinsync.tracking.repository.GeofenceEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
public class SimulatorTest {

    @LocalServerPort
    private int port;

    @Autowired
    private GeofenceEventRepository geofenceEventRepository;
    
    @Autowired
    private com.movinsync.tracking.service.GeofenceService geofenceService;

    @Autowired
    private com.movinsync.tracking.domain.state.VehicleStateStore vehicleStateStore;

    private static final String DEVICE_ID = "DEV-001";
    private static final String API_KEY = "test-api-key-001";
    private static final double SPEED_MPS = 10.0; // 10 m/s = 36 km/h
    private static final int PING_INTERVAL_SEC = 5;

    @Test
    public void runSimulation() throws Exception {
        geofenceService.reloadZones();
        
        // Parse polyline roughly matching the seeded route
        String polylineJson = "[[12.8399,77.6770],[12.8450,77.6750],[12.8500,77.6730],"
                + "[12.8600,77.6700],[12.8700,77.6680],[12.8800,77.6650],"
                + "[12.9000,77.6600],[12.9170,77.6230],[12.9350,77.6100],"
                + "[12.9500,77.6050],[12.9716,77.6095]]";

        List<double[]> points = parsePolyline(polylineJson);
        System.out.println("Parsed " + points.size() + " points from polyline.");

        // Start 4.5 minutes ago to fit the entire simulation (and gaps) within the 5-minute stale window
        long baseTs = Instant.now().toEpochMilli() - 270_000;
        long currentTs = baseTs;
        
        int pingsSent = 0;
        boolean gapFired = false;

        for (int i = 0; i < points.size() - 1; i++) {
            double[] start = points.get(i);
            double[] end = points.get(i + 1);

            double dist = haversine(start[0], start[1], end[0], end[1]);
            double timeSec = dist / SPEED_MPS;
            int steps = (int) Math.max(1, timeSec / PING_INTERVAL_SEC);

            for (int step = 0; step <= steps; step++) {
                double fraction = (double) step / steps;
                double lat = start[0] + (end[0] - start[0]) * fraction;
                double lon = start[1] + (end[1] - start[1]) * fraction;

                currentTs += 10L; // 10ms increment to keep strictly monotonic
                
                // Simulate a gap of > 2 mins just upon entering Koramangala
                if (haversine(lat, lon, 12.9350, 77.6100) <= 300 && !gapFired) {
                    currentTs += 150_000; // 2.5 minutes gap
                    gapFired = true;
                }

                HttpResponse<String> response = sendPing(lat, lon, currentTs);
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"status\":\"ACCEPTED\""), "Ping rejected! Body: " + response.body());
                
                // Simulate Duplicate
                if (pingsSent == 20) {
                    HttpResponse<String> dupResp = sendPing(lat, lon, currentTs);
                    assertTrue(dupResp.body().contains("\"status\":\"DUPLICATE\""));
                }
                
                // Simulate Out-of-Order
                if (pingsSent == 30) {
                    HttpResponse<String> lateResp = sendPing(lat, lon, currentTs - 10000); // 10s old
                    assertTrue(lateResp.body().contains("\"status\":\"OUT_OF_ORDER\""));
                }
                
                pingsSent++;
            }
        }

        // Verify exact geofence events
        List<GeofenceEvent> events = geofenceEventRepository.findAll();
        
        long koramangalaEntries = events.stream().filter(e -> e.getEventType() == GeofenceEventType.ENTRY && e.getZoneId() == 1L).count();
        long mgRoadEntries = events.stream().filter(e -> e.getEventType() == GeofenceEventType.ENTRY && e.getZoneId() == 2L).count();
        long koramangalaExits = events.stream().filter(e -> e.getEventType() == GeofenceEventType.EXIT && e.getZoneId() == 1L).count();
        
        assertEquals(1, koramangalaEntries, "Should have exactly one ENTRY for Koramangala");
        assertEquals(1, mgRoadEntries, "Should have exactly one ENTRY for MG Road");
        assertEquals(1, koramangalaExits, "Should have exactly one EXIT for Koramangala");
        
        // Verify gap recovery flag was set on Koramangala ENTRY
        boolean gapDetected = events.stream()
                .anyMatch(e -> e.getZoneId() == 1L && e.getEventType() == GeofenceEventType.ENTRY && Boolean.TRUE.equals(e.getDetectedAfterGap()));
        assertTrue(gapDetected, "Koramangala ENTRY should be marked with detectedAfterGap=true");

        // Verify ETAs
        com.movinsync.tracking.domain.state.VehicleState state = vehicleStateStore.getOrCreate(1L);
        assertTrue(!state.getEtaMap().isEmpty(), "ETAs should be calculated and stored in VehicleState");
        assertTrue(state.getEtaMap().containsKey(3L), "ETA map should contain Koramangala Stop");
    }

    private HttpResponse<String> sendPing(double lat, double lon, long ts) throws Exception {
        String json = String.format("""
                {
                    "deviceId": "%s",
                    "ts": %d,
                    "lat": %f,
                    "lon": %f,
                    "accuracy": 5.0,
                    "speed": %f,
                    "bearing": 0.0
                }
                """, DEVICE_ID, ts, lat, lon, SPEED_MPS);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/pings"))
                .header("Content-Type", "application/json")
                .header("X-Device-ID", DEVICE_ID)
                .header("X-API-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<double[]> parsePolyline(String json) {
        List<double[]> list = new ArrayList<>();
        String inner = json.replaceAll("\\[\\[", "").replaceAll("]]", "");
        String[] pairs = inner.split("],\\[");
        for (String pair : pairs) {
            String[] coords = pair.split(",");
            list.add(new double[]{Double.parseDouble(coords[0]), Double.parseDouble(coords[1])});
        }
        return list;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
