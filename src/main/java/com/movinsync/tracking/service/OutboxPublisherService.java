package com.movinsync.tracking.service;

import com.movinsync.tracking.entity.EventStatus;
import com.movinsync.tracking.entity.GeofenceEvent;
import com.movinsync.tracking.repository.GeofenceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implements the Outbox pattern.
 * Reads PENDING geofence events, publishes them (simulated via log), and marks them SENT.
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final GeofenceEventRepository eventRepository;

    public OutboxPublisherService(GeofenceEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPendingEvents() {
        // In a real system, you'd use a more robust locking or partitioning strategy 
        // to prevent multiple instances from picking up the same events.
        List<GeofenceEvent> pendingEvents = eventRepository.findByStatus(EventStatus.PENDING, PageRequest.of(0, 100));

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (GeofenceEvent event : pendingEvents) {
            // Simulate publishing to Kafka / SNS
            log.info("PUBLISHED EVENT -> vehicle={} zone={} type={} ts={} gap={}", 
                    event.getVehicleId(), event.getZoneId(), event.getEventType(), 
                    event.getTimestampMs(), event.getDetectedAfterGap());
            
            event.setStatus(EventStatus.SENT);
        }

        eventRepository.saveAll(pendingEvents);
        log.debug("Processed {} outbox events", pendingEvents.size());
    }
}
