package com.amalitech.labresultsvalidator.domain.user_module_assignment.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleRequest;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.service.UserModuleAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Module Assignments", description = "Assign modules to instructors")
@RestController
@RequestMapping("/api/v1/admin/instructors")
@RequiredArgsConstructor
public class UserModuleAssignmentController {

    private final UserModuleAssignmentService userModuleAssignmentService;

    @Operation(
        summary = "Assign modules to an instructor",
        description = "Assigns one or more modules to an active instructor. Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Modules assigned successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "User is not an instructor",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Instructor or module not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Instructor already assigned to a module",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })

    @PostMapping("/{instructorId}/modules")
    public ResponseEntity<ApiResponse<AssignModuleResponse>> assignModules(
            @PathVariable UUID instructorId,
            @Valid @RequestBody AssignModuleRequest request) {

        AssignModuleResponse response = userModuleAssignmentService.assignModule(instructorId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Modules assigned successfully", response));
    }

    @Operation(
        summary = "Remove module assignments from an instructor",
        description = "Removes specific module assignments from an instructor. "
            + "Returns the remaining assignments. Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Module assignments removed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "User is not an instructor",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Instructor not found or module not assigned",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @DeleteMapping("/{instructorId}/modules")
    public ResponseEntity<ApiResponse<List<AssignedModuleResponse>>> removeModuleAssignments(
            @PathVariable UUID instructorId,
            @Valid @RequestBody AssignModuleRequest request) {

        List<AssignedModuleResponse> remaining =
                userModuleAssignmentService.removeModuleAssignments(instructorId, request);
        return ResponseEntity.ok(
                ApiResponse.success("Module assignments removed successfully", remaining));
    }

    @Operation(
        summary = "Get modules assigned to an instructor",
        description = "Returns all modules assigned to a specific instructor." +
                " Restricted to ADMIN and SUPER_ADMIN roles."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Assigned modules retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "User is not an instructor",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Instructor not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN') or hasRole('INSTRUCTOR')")
    @GetMapping("/{instructorId}/modules")
    public ResponseEntity<ApiResponse<PagedResponse<AssignedModuleResponse>>> getInstructorModules(
            @PathVariable UUID instructorId,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<AssignedModuleResponse> response =
                userModuleAssignmentService.getInstructorModules(instructorId, pageable);
        return ResponseEntity.ok(
                ApiResponse.success("Assigned modules retrieved successfully", response));
    }
}