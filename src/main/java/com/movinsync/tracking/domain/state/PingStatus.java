package com.movinsync.tracking.domain.state;

/**
 * Result of processing a ping.
 */
public enum PingStatus {
    ACCEPTED,
    DUPLICATE,
    STALE,
    OUT_OF_ORDER,
    REJECTED_FUTURE
}
