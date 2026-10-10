package com.movinsync.tracking.service;

import com.movinsync.tracking.entity.EventStatus;
import com.movinsync.tracking.entity.GeofenceEvent;
import com.movinsync.tracking.entity.GeofenceEventType;
import com.movinsync.tracking.repository.GeofenceEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxPublisherServiceTest {

    private GeofenceEventRepository repository;
    private OutboxPublisherService service;

    @BeforeEach
    void setUp() {
        repository = mock(GeofenceEventRepository.class);
        service = new OutboxPublisherService(repository);
    }

    @Test
    void publishPendingEvents_noEvents() {
        when(repository.findByStatus(eq(EventStatus.PENDING), any())).thenReturn(List.of());
        service.publishPendingEvents();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void publishPendingEvents_processesAndMarksSent() {
        GeofenceEvent event1 = new GeofenceEvent();
        event1.setId(1L);
        event1.setStatus(EventStatus.PENDING);
        event1.setEventType(GeofenceEventType.ENTRY);

        GeofenceEvent event2 = new GeofenceEvent();
        event2.setId(2L);
        event2.setStatus(EventStatus.PENDING);
        event2.setEventType(GeofenceEventType.EXIT);

        when(repository.findByStatus(eq(EventStatus.PENDING), any(PageRequest.class)))
                .thenReturn(List.of(event1, event2));

        service.publishPendingEvents();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeofenceEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        List<GeofenceEvent> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertEquals(EventStatus.SENT, saved.get(0).getStatus());
        assertEquals(EventStatus.SENT, saved.get(1).getStatus());
    }
}
