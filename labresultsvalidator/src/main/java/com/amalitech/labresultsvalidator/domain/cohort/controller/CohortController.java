package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.UpdateCohortStatusRequest;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Cohorts", description = "Cohort management")
@RestController
@RequestMapping("/api/v1/admin/cohorts")
@RequiredArgsConstructor
public class CohortController {

    private final CohortService cohortService;

    @Operation(
            summary = "Get paginated list of cohorts",
            description = "Returns a paginated list of all cohorts."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Cohorts retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<CohortResponse>>> getCohorts(
            @PageableDefault(size = 10, sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        PagedResponse<CohortResponse> response = cohortService.getCohorts(pageable);
        return ResponseEntity.ok(
                ApiResponse.success("Cohorts retrieved successfully", response));
    }

    @Operation(
            summary = "Create a new cohort",
            description = "Creates a new cohort. Cohort name must be unique."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Cohort created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Validation error or end date before start date",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Cohort name already exists",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CohortResponse>> createCohort(
            @Valid @RequestBody CreateCohortRequest request) {
        CohortResponse response = cohortService.createCohort(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Cohort created successfully", response));
    }

    @Operation(
            summary = "Activate or deactivate a cohort",
            description = "Sets the cohort's active flag. "
                + "Deactivating a cohort does not delete it or its data."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Cohort status updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Validation error — active field missing",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Cohort not found",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<CohortResponse>> updateCohortStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCohortStatusRequest request) {
        CohortResponse response = cohortService.updateCohortStatus(id, request);
        String message = Boolean.TRUE.equals(request.getActive())
                ? "Cohort activated successfully"
                : "Cohort deactivated successfully";
        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @Operation(
            summary = "Delete a cohort",
            description = "Deletes a cohort. Blocked with 409 if active modules exist."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "204", description = "Cohort deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Cohort not found",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Cohort has active modules",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCohort(@PathVariable UUID id) {
        cohortService.deleteCohort(id);
        return ResponseEntity.noContent().build();
    }
}