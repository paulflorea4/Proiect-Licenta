package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.HealthResponse;
import com.gradingplatform.backend.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** `GET /health`: 200 when the service is up and the database answers, 503 when it does not. */
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        if (healthService.isDatabaseUp()) {
            return ResponseEntity.ok(new HealthResponse(HealthResponse.UP));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse(HealthResponse.DOWN));
    }
}
