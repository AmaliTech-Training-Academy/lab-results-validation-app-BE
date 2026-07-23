package com.amalitech.labresultsvalidator.domain.auth.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Security Tests", description = "Temporary JWT and RBAC validation endpoints — remove before production")
@RestController
@RequestMapping("/api/v1/test")
public class SecurityTestController {

    @Operation(summary = "Public endpoint", description = "No token required")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    })
    @SecurityRequirements
    @GetMapping("/public")
    public ResponseEntity<ApiResponse<String>> publicEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("Public endpoint — no token required", null));
    }

    @Operation(summary = "Authenticated endpoint", description = "Any valid JWT accepted")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token is valid"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Missing or invalid token")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/authenticated")
    public ResponseEntity<ApiResponse<String>> authenticatedEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("You have a valid JWT token", null));
    }

    @Operation(summary = "ADMIN only", description = "Requires ADMIN role")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access granted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Missing or invalid token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin-only")
    public ResponseEntity<ApiResponse<String>> adminEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("ADMIN access confirmed", null));
    }
}
