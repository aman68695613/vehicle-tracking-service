package com.movinsync.tracking.dto.response;

import com.movinsync.tracking.domain.state.PingStatus;

/**
 * Response returned to the device after a ping.
 */
public record PingResponse(PingStatus status, String message) {}
