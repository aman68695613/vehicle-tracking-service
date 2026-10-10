package com.movinsync.tracking.controller;

import com.movinsync.tracking.domain.state.PingResult;
import com.movinsync.tracking.dto.request.PingRequest;
import com.movinsync.tracking.dto.response.PingResponse;
import com.movinsync.tracking.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ping endpoint. No business logic — delegates entirely to IngestionService.
 */
@RestController
@RequestMapping("/api/pings")
public class PingController {

    private final IngestionService ingestionService;

    public PingController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<PingResponse> ingestPing(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Device-ID", required = false) String headerDeviceId,
            @Valid @RequestBody PingRequest request) {
        
        java.security.Principal principal = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (principal == null || !("DEVICE_" + request.deviceId()).equals(principal.getName())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
            
        PingResult result = ingestionService.processPing(request);
        PingResponse response = new PingResponse(result.status(), result.message());
        return ResponseEntity.ok(response);
    }
}
