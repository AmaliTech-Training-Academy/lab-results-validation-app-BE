package com.amalitech.labresultsvalidator.domain.lab_result.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.service.LabResultUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Tag(name = "Lab Results",
    description = "Bulk upload of lab results — INSTRUCTOR (scoped to assigned modules) and ADMIN")
@RestController
@RequestMapping("/api/v1/lab-results")
@RequiredArgsConstructor
public class LabResultController {

    private final LabResultUploadService labResultUploadService;

    @Operation(summary = "Bulk CSV upload of lab results",
        description = "Upload a CSV to record lab results. Valid rows are committed (new rows inserted, "
            + "corrected scores updated in place); invalid rows are rejected and returned in a row-level "
            + "report with the failing field and rule. Instructors may only upload for their assigned "
            + "modules. 5 MB / 10,000-row cap. Download the template first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Upload processed — see inserted/updated/skipped/rejected counts"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "This exact file was already uploaded",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422", description = "Whole-file structural failure (missing headers, too large, etc.)",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<LabResultUploadResponse>> bulkUpload(
            @Parameter(description = "CSV file — use GET /template to download the correct format")
            @RequestParam("file") MultipartFile file) {
        LabResultUploadResponse result = labResultUploadService.bulkUpload(file);
        return ResponseEntity.ok(ApiResponse.success("Bulk upload complete", result));
    }

    @Operation(summary = "Download CSV template",
        description = "Returns a header-only CSV file with the correct column names for bulk upload.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200", description = "CSV template file")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        labResultUploadService.downloadTemplate(response);
    }

    @Operation(summary = "Get lab results for a module",
        description = "Returns all uploaded lab results for a given module.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Results retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Module not found",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/modules/{moduleId}")
    public ResponseEntity<ApiResponse<List<LabResultResponse>>> getResultsByModule(
            @PathVariable UUID moduleId) {
        List<LabResultResponse> results = labResultUploadService.getLabResultsByModule(moduleId);
        return ResponseEntity.ok(ApiResponse.success("Lab results retrieved successfully", results));
    }
}
