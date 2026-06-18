package com.amalitech.labresultsvalidator.domain.csvUploads.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.ProgramStructureUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.service.ProgramStructureUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Tag(name = "Program Structure", description = "Bulk program structure upload — ADMIN and SUPER_ADMIN only")
@RestController
@RequestMapping("/api/v1/admin/program-structure")
@RequiredArgsConstructor
public class ProgramStructureUploadController {

    private final ProgramStructureUploadService programStructureUploadService;

    @Operation(summary = "Download CSV template",
        description = "Returns a header-only CSV file with the correct column names for bulk program structure upload.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200", description = "CSV template file")
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        programStructureUploadService.downloadTemplate(response);
    }

    @Operation(
        summary = "Bulk upload program structure",
        description = "Accepts a 5-column CSV (COHORT_NAME, SPECIALIZATION_NAME, SPECIALIZATION_CODE, "
            + "MODULE_NAME, LAB_TITLE) and creates the full cohort hierarchy — specializations, modules, "
            + "and labs — atomically. The cohort must already exist before uploading. "
            + "If any row fails validation the entire upload is rejected and nothing is persisted."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Upload accepted — specializationsCreated / modulesCreated / labsCreated populated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Upload rejected — errors list populated, nothing was persisted",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Whole-file structural failure (missing headers, wrong file type, too large)",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProgramStructureUploadResponse>> upload(
            @Parameter(description = "CSV file with columns: COHORT_NAME, SPECIALIZATION_NAME, "
                + "SPECIALIZATION_CODE, MODULE_NAME, LAB_TITLE")
            @RequestParam("file") MultipartFile file) {
        ProgramStructureUploadResponse result = programStructureUploadService.upload(file);

        if (result.getErrors() != null && !result.getErrors().isEmpty()) {
            return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.<ProgramStructureUploadResponse>builder()
                    .success(false)
                    .message("Program structure upload failed — no data was imported")
                    .data(result)
                    .build());
        }

        return ResponseEntity.ok(ApiResponse.success(
            String.format("Program structure upload complete — %d specialization(s), %d module(s), %d lab(s) created",
                result.getSpecializationsCreated(), result.getModulesCreated(), result.getLabsCreated()),
            result));
    }
}