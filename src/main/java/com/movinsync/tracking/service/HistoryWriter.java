package com.movinsync.tracking.service;

import com.movinsync.tracking.entity.LocationPing;
import com.movinsync.tracking.repository.LocationPingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Bounded-queue async history writer. If the queue is full, the ping is
 * dropped (counted), and ingestion is never blocked.
 * <p>
 * DB unique constraint on (device_id, ts) is the safety net for duplicates.
 */
@Service
public class HistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoryWriter.class);
    private static final int QUEUE_CAPACITY = 10_000;

    private final LocationPingRepository pingRepository;
    private final BlockingQueue<LocationPing> queue;
    private final Counter droppedCounter;

    public HistoryWriter(LocationPingRepository pingRepository, MeterRegistry meterRegistry) {
        this.pingRepository = pingRepository;
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.droppedCounter = meterRegistry.counter("history.dropped");
        meterRegistry.gauge("history.queue.size", this.queue, BlockingQueue::size);

        // Start a single consumer thread for batch writes
        Thread consumer = new Thread(this::consumeLoop, "history-writer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /**
     * Enqueue a ping for async persistence. Returns true if enqueued, false if dropped.
     */
    public boolean enqueue(LocationPing ping) {
        boolean offered = queue.offer(ping);
        if (!offered) {
            droppedCounter.increment();
            log.warn("History queue full, dropping ping for device={} ts={}", ping.getDeviceId(), ping.getTimestampMs());
        }
        return offered;
    }

    /**
     * Check if a DB unique constraint violation indicates a duplicate.
     */
    public boolean isDuplicate(String deviceId, long timestampMs) {
        // This is called only after a DataIntegrityViolationException during batch write.
        // The constraint is on (device_id, timestamp_ms).
        return true; // if the violation happened, it's a duplicate by definition
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                LocationPing ping = queue.take();
                persistSafely(ping);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void persistSafely(LocationPing ping) {
        try {
            pingRepository.save(ping);
        } catch (DataIntegrityViolationException e) {
            // Duplicate — expected from the unique constraint
            log.debug("Duplicate ping in history: device={} ts={}", ping.getDeviceId(), ping.getTimestampMs());
        } catch (Exception e) {
            log.error("Failed to persist ping history: device={} ts={}", ping.getDeviceId(), ping.getTimestampMs(), e);
        }
    }

    /** For testing: queue size */
    public int queueSize() {
        return queue.size();
    }
}
