package com.amalitech.labresultsvalidator.domain.user.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.UpdateUserRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.UserResponseDTO;
import com.amalitech.labresultsvalidator.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "User Management", description = "Admin operations for managing user accounts")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(
        summary = "Provision instructor account",
        description = "Creates a new INSTRUCTOR account. A 12-character secure password is "
            + "auto-generated, BCrypt-hashed, and emailed to the instructor. "
            + "The account is created with mustChangePassword = true, blocking access to all "
            + "other endpoints until the instructor changes their password via "
            + "POST /api/v1/auth/change-password. "
            + "Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Instructor account created — welcome email sent with temporary credentials"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — email missing, malformed, or not @amalitech.com",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Missing or invalid JWT token",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Insufficient role — requires ADMIN or SUPER_ADMIN",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "An account with this email already exists",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/instructors")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ProvisionInstructorResponse>> provisionInstructor(
            @Valid @RequestBody ProvisionInstructorRequest request
    ) {
        ProvisionInstructorResponse response = userService.provisionInstructor(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Instructor account created successfully", response));
    }

    @Operation(
        summary = "Update instructor info",
        description = "Updates email and/or active status of an instructor. "
            + "Only provided fields are changed. Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Instructor updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error or user is not an instructor",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Instructor not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Email already in use",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/instructors/{instructorId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponseDTO>> updateInstructor(
            @PathVariable UUID instructorId,
            @Valid @RequestBody UpdateUserRequest request) {

        UserResponseDTO response = userService.updateInstructor(instructorId, request);
        return ResponseEntity.ok(ApiResponse.success("Instructor updated successfully", response));
    }

    @Operation(
        summary = "List instructors",
        description = "Returns a paginated list of INSTRUCTOR accounts with their email, active status, "
            + "and assigned modules. Default: page 0, size 10, sorted by email ASC. "
            + "Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Instructor list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Missing or invalid JWT token",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Insufficient role — requires ADMIN or SUPER_ADMIN",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/instructors")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PagedResponse<UserResponseDTO>>> listInstructors(
            @Parameter(hidden = true)
            @PageableDefault(size = 10, sort = "createdAt",
                direction = Sort.Direction.ASC) Pageable pageable) {
        PagedResponse<UserResponseDTO> instructors = userService.listInstructors(pageable);
        return ResponseEntity.ok(
                ApiResponse.success("Instructors retrieved successfully", instructors)
        );
    }
}
