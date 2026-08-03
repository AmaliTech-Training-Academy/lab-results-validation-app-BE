package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AuditEventResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.IngestionRunAuditResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.IngestionRunDetailResponse;
import com.amalitech.labresultsvalidator.domain.cohort.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin audit-log view (PRD Epic D, D5) — historical, cross-cohort browsing of {@code ingestion_runs}
 * and {@code audit_event}. Deliberately top-level (not nested under a single cohort) and read-only,
 * distinct from any future per-run Run-Review moderation screen (C6).
 */
@Tag(name = "Audit Log", description = "Historical audit-log browsing across cohorts and runs (admin only)")
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(
        summary = "List ingestion runs",
        description = "Paged, filterable by cohort, status, date range, and instructor (via their graded rows)."
    )
    @GetMapping("/ingestion-runs")
    public ResponseEntity<ApiResponse<Page<IngestionRunAuditResponse>>> listIngestionRuns(
            @RequestParam(required = false) UUID cohortId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) UUID instructorContactId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Ingestion runs retrieved",
            auditLogService.listIngestionRuns(cohortId, status, from, to, instructorContactId, pageable)));
    }

    @Operation(
        summary = "Get an ingestion run's detail",
        description = "Includes the full row-level error report for the run."
    )
    @GetMapping("/ingestion-runs/{id}")
    public ResponseEntity<ApiResponse<IngestionRunDetailResponse>> getIngestionRunDetail(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
            "Ingestion run detail retrieved", auditLogService.getIngestionRunDetail(id)));
    }

    @Operation(
        summary = "List audit events",
        description = "Paged, filterable by cohort, event type, and date range."
    )
    @GetMapping("/audit-events")
    public ResponseEntity<ApiResponse<Page<AuditEventResponse>>> listAuditEvents(
            @RequestParam(required = false) UUID cohortId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Audit events retrieved",
            auditLogService.listAuditEvents(cohortId, eventType, from, to, pageable)));
    }
}
