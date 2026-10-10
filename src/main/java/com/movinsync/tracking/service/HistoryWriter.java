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



    private void consumeLoop() {
        java.util.List<LocationPing> batch = new java.util.ArrayList<>();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                LocationPing first = queue.take(); // block until at least 1 is available
                batch.add(first);
                queue.drainTo(batch, 99); // take up to 99 more (batch size 100)
                
                persistSafely(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void persistSafely(java.util.List<LocationPing> batch) {
        try {
            pingRepository.saveAll(batch);
        } catch (DataIntegrityViolationException e) {
            // A duplicate exists in the batch — save one by one to isolate it
            for (LocationPing ping : batch) {
                try {
                    pingRepository.save(ping);
                } catch (DataIntegrityViolationException ex) {
                    log.debug("Duplicate ping in history: device={} ts={}", ping.getDeviceId(), ping.getTimestampMs());
                } catch (Exception ex) {
                    log.error("Failed to persist ping history: device={} ts={}", ping.getDeviceId(), ping.getTimestampMs(), ex);
                }
            }
        } catch (Exception e) {
            log.error("Failed to persist ping batch of size {}", batch.size(), e);
        }
    }

    /** For testing: queue size */
    public int queueSize() {
        return queue.size();
    }
}
