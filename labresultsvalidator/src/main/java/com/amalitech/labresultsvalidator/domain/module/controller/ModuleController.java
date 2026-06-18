package com.amalitech.labresultsvalidator.domain.module.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.module.dto.CreateModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.dto.ModuleResponse;
import com.amalitech.labresultsvalidator.domain.module.dto.PatchModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Modules", description = "Manage modules scoped to cohort and specialization")
@RestController
@RequestMapping("/api/v1/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    @Operation(
        summary = "Create a module",
        description = "Creates a new module under the given cohort and specialization. "
            + "Returns 422 if the cohort and specialization combination does not exist.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Module created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Cohort and specialization combination does not exist",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ModuleResponse>> createModule(
            @Valid @RequestBody CreateModuleRequest request) {
        ModuleResponse response = moduleService.createModule(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Module created successfully", response));
    }

    @Operation(
        summary = "List modules",
        description = "Returns modules filtered by cohort_id and/or specialization_id. "
            + "Both parameters are optional.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Modules retrieved successfully")
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'INSTRUCTOR')")
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ModuleResponse>>> getModules(
            @RequestParam(required = false) UUID cohortId,
            @RequestParam(required = false) UUID specializationId,
            @PageableDefault(size = 20, sort = "sequence", direction = Sort.Direction.ASC) Pageable pageable) {
        PagedResponse<ModuleResponse> response = moduleService.getModules(cohortId, specializationId, pageable);
        return ResponseEntity.ok(ApiResponse.success("Modules retrieved successfully", response));
    }

    @Operation(summary = "Update a module",
               description = "Supports archiving a module via { status: 'ARCHIVED' }.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Module updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Module not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ModuleResponse>> patchModule(
            @PathVariable UUID id,
            @Valid @RequestBody PatchModuleRequest request) {
        ModuleResponse response = moduleService.patchModule(id, request);
        return ResponseEntity.ok(ApiResponse.success("Module updated successfully", response));
    }
}
