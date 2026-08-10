package com.amalitech.labresultsvalidator.common.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness probe served under /api/v1 so it is reachable through the frontend's /api proxy
 * (nginx forwards /api/ unchanged). The actuator health endpoint sits at /actuator/health,
 * outside the /api prefix, so it is not proxied — hence this one.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Liveness probe reachable via the /api proxy")
public class HealthController {

    @Operation(summary = "Liveness check", description = "Returns 200 with the standard envelope when the API is up.")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        return ResponseEntity.ok(ApiResponse.success("Service is healthy", Map.of("status", "UP")));
    }
}
