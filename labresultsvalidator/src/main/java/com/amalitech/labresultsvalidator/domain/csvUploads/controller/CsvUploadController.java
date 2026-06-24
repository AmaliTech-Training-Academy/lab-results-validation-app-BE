package com.amalitech.labresultsvalidator.domain.csvUploads.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadFilterRequest;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.service.CsvUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "CSV Uploads", description = "CSV upload history and error reports")
@RestController
@RequestMapping("/api/v1/admin/csv-uploads")
@RequiredArgsConstructor
public class CsvUploadController {

    private final CsvUploadService csvUploadService;

    @Operation(
            summary = "List all CSV uploads",
            description = "Returns a paginated, filtered list of CSV uploads. "
                    + "Supports filtering by date range (startDate/endDate), "
                    + "instructor email (uploadedByEmail), status, "
                    + "and a search term matched against filename or upload ID."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Uploads retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<CsvUploadResponse>>> listUploads(
            @ModelAttribute CsvUploadFilterRequest filter,
            @ParameterObject @PageableDefault(size = 10, sort = "uploadedAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        PagedResponse<CsvUploadResponse> response = csvUploadService.listUploads(filter, pageable);
        return ResponseEntity.ok(ApiResponse.success("CSV uploads retrieved successfully", response));
    }

    @Operation(
            summary = "Get a CSV upload by ID",
            description = "Returns details of a single CSV upload record."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Upload retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Upload not found",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CsvUploadResponse>> getUploadById(@PathVariable UUID id) {
        CsvUploadResponse response = csvUploadService.getUploadById(id);
        return ResponseEntity.ok(ApiResponse.success("CSV upload retrieved successfully", response));
    }

    @Operation(
            summary = "Get error report for a CSV upload",
            description = "Returns the structured error report JSON for a failed or partially rejected upload."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Error report retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Upload not found or no error report available",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/error-report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getErrorReport(@PathVariable UUID id) {
        Map<String, Object> report = csvUploadService.getErrorReport(id);
        return ResponseEntity.ok(ApiResponse.success("Error report retrieved successfully", report));
    }
}