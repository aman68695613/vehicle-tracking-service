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

                long currentTs = Instant.now().toEpochMilli();

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
                        """, DEVICE_ID, currentTs, lat, lon, SPEED_MPS);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/pings"))
                        .header("Content-Type", "application/json")
                        .header("X-Device-ID", DEVICE_ID)
                        .header("X-API-Key", API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                
                assertEquals(200, response.statusCode(), "Ping should be accepted");
                assertTrue(response.body().contains("\"status\":\"ACCEPTED\""), "Ping should not be rejected, body: " + response.body());
            }
        }

        // Verify geofence events were generated (e.g. entry into Koramangala and MG Road)
        List<GeofenceEvent> events = geofenceEventRepository.findAll();
        System.out.println("Generated " + events.size() + " geofence events.");
        
        boolean enteredKoramangala = events.stream().anyMatch(e -> e.getEventType() == GeofenceEventType.ENTRY && e.getZoneId() == 1L); // assuming 1L is Koramangala
        // We just assert that AT LEAST ONE entry event was created during the simulation
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == GeofenceEventType.ENTRY), "Should have entered at least one geofence");

        // Verify ETAs
        com.movinsync.tracking.domain.state.VehicleState state = vehicleStateStore.getOrCreate(1L); // assuming vehicle ID is 1L
        System.out.println("Final ETAs: " + state.getEtaMap());
        assertTrue(!state.getEtaMap().isEmpty(), "ETAs should be calculated and stored in VehicleState");
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
