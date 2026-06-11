package com.amalitech.labresultsvalidator.domain.learner.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.learner.dto.BulkUploadResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.CreateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerStatusRequest;
import com.amalitech.labresultsvalidator.domain.learner.service.LearnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Tag(name = "Learners", description = "Learner roster management — ADMIN and SUPER_ADMIN only")
@RestController
@RequestMapping("/api/v1/admin/learners")
@RequiredArgsConstructor
public class LearnerController {

    private final LearnerService learnerService;

    @Operation(summary = "Create a single learner",
        description = "Creates a learner with ACTIVE status. "
            + "Cohort and specialization must exist; specialization must belong to the cohort.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Learner created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error or unknown cohort/specialization",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Email already in use",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<LearnerResponse>> createLearner(
            @Valid @RequestBody CreateLearnerRequest request) {
        LearnerResponse response = learnerService.createLearner(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Learner created successfully", response));
    }

    @Operation(summary = "Bulk CSV upload",
        description = "Upload a CSV file to create multiple learners at once. "
            + "Valid rows are committed; invalid rows are rejected and returned in the error report. "
            + "5 MB / 10,000-row cap. Download the template first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "At least one row accepted — see acceptedCount / rejectedCount"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "All rows rejected, or whole-file structural failure (missing headers, too large, etc.)",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(value = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BulkUploadResponse>> bulkUpload(
            @Parameter(description = "CSV file — use GET /template to download the correct format")
            @RequestParam("file") MultipartFile file) {
        BulkUploadResponse result = learnerService.bulkUpload(file);

        if (result.getAcceptedCount() == 0) {
            return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.<BulkUploadResponse>builder()
                    .success(false)
                    .message("Bulk upload failed — no rows were imported")
                    .data(result)
                    .build());
        }

        String message = result.getRejectedCount() > 0
            ? String.format("Bulk upload complete — %d accepted, %d rejected",
                result.getAcceptedCount(), result.getRejectedCount())
            : String.format("Bulk upload complete — %d rows imported", result.getAcceptedCount());

        return ResponseEntity.ok(ApiResponse.success(message, result));
    }

    @Operation(summary = "Download CSV template",
        description = "Returns a header-only CSV file with the correct column names for bulk upload.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200", description = "CSV template file")
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        learnerService.downloadTemplate(response);
    }

    @Operation(summary = "List learners",
        description = "Paginated, filterable list of all learners. "
            + "Default sort: createdAt DESC.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Learner list returned")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<LearnerResponse>>> getLearners(
            @Parameter(description = "Filter by cohort UUID") @RequestParam(required = false) UUID cohortId,
            @Parameter(description = "Filter by specialization UUID")
                @RequestParam(required = false) UUID specializationId,
            @Parameter(description = "Filter by status: ACTIVE or ARCHIVED")
                @RequestParam(required = false) LearnerStatus status,
            @Parameter(description = "Search by full name or email (case-insensitive)")
                @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt",
                direction = Sort.Direction.DESC) Pageable pageable) {

        PagedResponse<LearnerResponse> page =
            learnerService.getLearners(cohortId, specializationId, status, search, pageable);
        return ResponseEntity.ok(ApiResponse.success("Learners retrieved successfully", page));
    }

    @Operation(summary = "Get a learner by ID")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Learner found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Learner not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LearnerResponse>> getLearner(@PathVariable UUID id) {
        return ResponseEntity.ok(
            ApiResponse.success("Learner retrieved successfully", learnerService.getLearnerById(id)));
    }

    @Operation(summary = "Update a learner",
        description = "Updates full name, cohort, and specialization. Email is not updatable.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Learner updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error or unknown cohort/specialization",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Learner not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LearnerResponse>> updateLearner(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLearnerRequest request) {
        return ResponseEntity.ok(
            ApiResponse.success("Learner updated successfully",
                learnerService.updateLearner(id, request)));
    }

    @Operation(summary = "Archive or reactivate a learner",
        description = "Sets status to ACTIVE or ARCHIVED. "
            + "Archived learners are excluded from validation rule V9.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Status updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Learner not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<LearnerResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLearnerStatusRequest request) {
        return ResponseEntity.ok(
            ApiResponse.success("Learner status updated",
                learnerService.updateLearnerStatus(id, request)));
    }

    @Operation(summary = "Delete a learner",
        description = "Hard-deletes the learner. Returns 409 if the learner has "
            + "associated lab results — archive instead.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "Learner deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Learner not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Learner has lab results — archive instead",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLearner(@PathVariable UUID id) {
        learnerService.deleteLearner(id);
        return ResponseEntity.noContent().build();
    }
}
