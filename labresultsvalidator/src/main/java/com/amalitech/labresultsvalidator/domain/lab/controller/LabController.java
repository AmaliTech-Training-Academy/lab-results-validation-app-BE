package com.amalitech.labresultsvalidator.domain.lab.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.lab.dto.CreateLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.dto.LabResponse;
import com.amalitech.labresultsvalidator.domain.lab.dto.PatchLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.service.LabService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Labs", description = "Lab management")
@RestController
@RequestMapping("/api/v1/admin/labs")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class LabController {

    private final LabService labService;

    @Operation(
        summary = "Create a new lab",
        description = "Creates a lab linked to an existing module. "
                + "Title must be unique within the module. Requires ADMIN role."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Lab created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error — missing or invalid fields",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — JWT token missing or invalid",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — ADMIN role required",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Module not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Lab title already exists in this module",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<LabResponse>> createLab(
            @Valid @RequestBody CreateLabRequest request) {
        LabResponse response = labService.createLab(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lab created successfully", response));
    }

    @Operation(
        summary = "List labs",
        description = "Returns labs ordered by title. "
                + "Pass moduleId to filter by module, or omit to return all."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Lab list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — JWT token missing or invalid",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'INSTRUCTOR')")
    public ResponseEntity<ApiResponse<Page<LabResponse>>> listLabs(
            @RequestParam(required = false) UUID moduleId,
            @PageableDefault(size = 20, sort = "title") Pageable pageable) {
        Page<LabResponse> labs = labService.listLabs(moduleId, pageable);
        return ResponseEntity.ok(ApiResponse.success("Labs retrieved successfully", labs));
    }

    @Operation(
        summary = "Update a lab",
        description = "Partially updates a lab. All fields are optional. "
                + "Returns 422 if the lab is immutable. "
                + "Setting immutable to true locks the lab from further edits. Requires ADMIN role."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Lab updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — JWT token missing or invalid",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — ADMIN role required",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Lab not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Lab title already exists in this module",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422", description = "Lab is immutable and cannot be modified",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<LabResponse>> patchLab(
            @PathVariable UUID id,
            @Valid @RequestBody PatchLabRequest request) {
        LabResponse response = labService.patchLab(id, request);
        return ResponseEntity.ok(ApiResponse.success("Lab updated successfully", response));
    }
}
