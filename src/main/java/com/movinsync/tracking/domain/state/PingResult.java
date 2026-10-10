package com.movinsync.tracking.domain.state;

/**
 * Result of processing a single ping, returned to the controller.
 */
public record PingResult(PingStatus status, String message) {

    public static PingResult accepted() {
        return new PingResult(PingStatus.ACCEPTED, "Ping accepted");
    }

    public static PingResult duplicate() {
        return new PingResult(PingStatus.DUPLICATE, "Duplicate ping");
    }

    public static PingResult stale() {
        return new PingResult(PingStatus.STALE, "Ping is stale (too old)");
    }

    public static PingResult outOfOrder() {
        return new PingResult(PingStatus.OUT_OF_ORDER, "Out-of-order ping stored as history only");
    }

    public static PingResult rejectedFuture() {
        return new PingResult(PingStatus.REJECTED_FUTURE, "Ping timestamp is in the future");
    }
}
