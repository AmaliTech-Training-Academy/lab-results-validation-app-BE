package com.amalitech.labresultsvalidator.domain.auth.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary controller for validating the JWT + RBAC security layer.
 * Remove or gate behind a feature flag before production.
 */
@RestController
@RequestMapping("/api/v1/test")
public class SecurityTestController {

    @GetMapping("/public")
    public ResponseEntity<ApiResponse<String>> publicEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("Public endpoint — no token required", null));
    }

    @GetMapping("/authenticated")
    public ResponseEntity<ApiResponse<String>> authenticatedEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("You have a valid JWT token", null));
    }

    @GetMapping("/super-admin-only")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<String>> superAdminEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("SUPER_ADMIN access confirmed", null));
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<String>> adminEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("ADMIN or SUPER_ADMIN access confirmed", null));
    }

    @GetMapping("/instructor-only")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<String>> instructorEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("INSTRUCTOR access confirmed", null));
    }
}
