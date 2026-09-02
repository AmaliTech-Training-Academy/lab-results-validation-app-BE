package com.amalitech.labresultsvalidator.domain.sync.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.CohortSyncJobResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.GradingSyncOverviewResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ResolveConflictRequest;
import com.amalitech.labresultsvalidator.domain.standup.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncBatchResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncFileResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncRunResponse;
import com.amalitech.labresultsvalidator.domain.sync.service.CohortSyncService;
import com.amalitech.labresultsvalidator.domain.standup.service.SseGateEventStreamer;
import com.amalitech.labresultsvalidator.domain.sync.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohort Sync", description = "Score sheet sync — scheduled and manual fetch of a cohort's score sheets")
public class CohortSyncController {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncController.class);

    private final CohortSyncService cohortSyncService;
    private final SyncEventService syncEventService;
    private final SseGateEventStreamer sseStreamer;

    @Operation(summary = "Trigger a sync run for all eligible cohorts",
        description = "Runs the same logic as the scheduled sync, immediately, for every STOOD_UP cohort.")
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncBatchResponse>> syncAll() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Sync run started.", cohortSyncService.triggerSyncForAll()));
    }

    @Operation(summary = "Trigger a sync run for one cohort",
        description = "Fetches score sheets for a single cohort. Cohort must be in STOOD_UP state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Sync job created and running"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "A sync job is already running for this cohort"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in STOOD_UP state or missing SharePoint reference")
    })
    @PostMapping("/{id}/sync")
    public ResponseEntity<ApiResponse<CohortSyncJobResponse>> syncCohort(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Sync job started.", cohortSyncService.triggerSyncForCohort(id)));
    }

    @Operation(summary = "List sync runs",
        description = "Returns a paginated list of all sync jobs for a cohort, newest first.")
    @GetMapping("/{id}/sync/runs")
    public ResponseEntity<ApiResponse<Page<SyncRunResponse>>> listSyncRuns(
        @PathVariable UUID id,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success("Sync runs retrieved.", cohortSyncService.listRuns(id, pageable)));
    }

    @Operation(summary = "Get a single sync run",
        description = "Returns the status and details of one sync job for a cohort.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Sync run found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No sync job with that ID for this cohort")
    })
    @GetMapping("/{id}/sync/runs/{jobId}")
    public ResponseEntity<ApiResponse<SyncRunResponse>> getSyncRun(
        @PathVariable UUID id,
        @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success("Sync run retrieved.", cohortSyncService.getRun(id, jobId)));
    }

    @Operation(summary = "Get a sync run's grading overview",
        description = "Summarizes what the grading-ingestion pipeline (B5-B10) consumed for one sync "
            + "run: rows read, new/updated/skipped-invalid/skipped-unchanged/conflicts, aggregated "
            + "and broken down per workbook.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Overview retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No sync job with that ID for this cohort")
    })
    @GetMapping("/{id}/sync/runs/{jobId}/overview")
    public ResponseEntity<ApiResponse<GradingSyncOverviewResponse>> getGradingSyncOverview(
        @PathVariable UUID id,
        @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
            "Grading sync overview retrieved.", cohortSyncService.getGradingSyncOverview(id, jobId)));
    }

    @Operation(summary = "List a sync run's files",
        description = "Returns a paginated list of every file one sync run touched — new, changed, "
            + "unchanged and failed alike — newest first, including the reason for any failure.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Files retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No sync job with that ID for this cohort")
    })
    @GetMapping("/{id}/sync/runs/{jobId}/files")
    public ResponseEntity<ApiResponse<Page<SyncFileResponse>>> listFilesForRun(
        @PathVariable UUID id,
        @PathVariable UUID jobId,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success(
            "Files retrieved.", cohortSyncService.listFilesForRun(id, jobId, pageable)));
    }

    @Operation(summary = "List ingestion conflicts",
        description = "Returns a paginated list of in-file duplicate rows held for manual resolution "
            + "during grading ingestion (B10), newest first, aggregated across every sync run for "
            + "the cohort. Optionally filter by status (PENDING/RESOLVED/DISMISSED).")
    @GetMapping("/{id}/conflicts")
    public ResponseEntity<ApiResponse<Page<IngestionConflictResponse>>> listConflicts(
        @PathVariable UUID id,
        @RequestParam(required = false) String status,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(
            ApiResponse.success("Conflicts retrieved.", cohortSyncService.listConflicts(id, status, pageable)));
    }

    @Operation(summary = "List ingestion conflicts for a sync run",
        description = "Returns a paginated list of in-file duplicate rows held for manual resolution "
            + "during grading ingestion (B10), newest first, narrowed to one sync run. Optionally "
            + "filter by status (PENDING/RESOLVED/DISMISSED).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Conflicts retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No sync job with that ID for this cohort")
    })
    @GetMapping("/{id}/sync/runs/{jobId}/conflicts")
    public ResponseEntity<ApiResponse<Page<IngestionConflictResponse>>> listConflictsForRun(
        @PathVariable UUID id,
        @PathVariable UUID jobId,
        @RequestParam(required = false) String status,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success(
            "Conflicts retrieved.", cohortSyncService.listConflictsForRun(id, jobId, status, pageable)));
    }

    @Operation(summary = "Resolve an ingestion conflict",
        description = "Decides a held in-file duplicate (B10): keep the existing committed row, keep one "
            + "of the conflicting incoming rows (updating or creating the committed row), or reject them "
            + "all. A duplicate is one conflict holding every conflicting copy, so KEEP_INCOMING needs "
            + "chosenRowIndex — the 0-based index of the candidate to keep, as listed in the conflict's "
            + "`candidates`. It may be omitted only when the conflict holds a single candidate. Whatever "
            + "the action, the decision and every discarded mark are written to the grade's audit "
            + "history. Only a PENDING conflict can be decided, and only once.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Conflict resolved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No conflict with that ID for this cohort"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Conflict has already been resolved or dismissed — a second decision is "
                + "refused rather than overwriting the first"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "chosenRowIndex is missing or out of range, or the stored payload is corrupt")
    })
    @PatchMapping("/{id}/conflicts/{conflictId}/resolve")
    public ResponseEntity<ApiResponse<IngestionConflictResponse>> resolveConflict(
        @PathVariable UUID id,
        @PathVariable UUID conflictId,
        @Valid @RequestBody ResolveConflictRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
            "Conflict resolved.", cohortSyncService.resolveConflict(id, conflictId, request)));
    }

    @Operation(
        summary = "Stream score sheet sync events",
        description = "Opens an SSE stream for the most recent sync job on a cohort. "
            + "Replays all stored events from Last-Event-ID+1 on reconnect. "
            + "Pass the JWT via ?token= since browser EventSource cannot send Authorization headers."
    )
    @GetMapping(value = "/{cohortId}/sync/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSyncEvents(
        @PathVariable UUID cohortId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        StreamJobHandle handle = cohortSyncService.getLatestJobForStream(cohortId);
        LOG.debug("[sse-sync] cohort={} job={} client connected lastEventId={}", cohortId, handle.jobId(), lastEventId);

        List<StandupGateEvent> events = syncEventService.getEvents(handle.jobId());
        return sseStreamer.stream(handle.jobId(), handle.running(), "sync.done", events, lastEventId, "sse-sync");
    }
}
