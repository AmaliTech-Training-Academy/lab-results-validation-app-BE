package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortSyncJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncBatchResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncRunResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortSyncService;
import com.amalitech.labresultsvalidator.domain.cohort.service.StandupSseRegistry;
import com.amalitech.labresultsvalidator.domain.cohort.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohort Sync", description = "Score sheet sync — scheduled and manual fetch of a cohort's score sheets")
public class CohortSyncController {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncController.class);

    private final CohortSyncService cohortSyncService;
    private final CohortSyncJobRepository syncJobRepository;
    private final SyncEventService syncEventService;
    private final StandupSseRegistry sseRegistry;

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

    @Operation(summary = "Trigger a sync run for a single file",
        description = "Fetches one score sheet, identified by its SharePoint drive item ID, "
            + "without re-enumerating the whole scores folder.")
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
    @PostMapping("/{id}/sync/files/{itemId}")
    public ResponseEntity<ApiResponse<CohortSyncJobResponse>> syncFile(
        @PathVariable UUID id,
        @PathVariable String itemId
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Sync job started.", cohortSyncService.triggerSyncForFile(id, itemId)));
    }

    @Operation(summary = "List sync runs",
        description = "Returns a paginated list of all sync jobs for a cohort, newest first.")
    @GetMapping("/{id}/sync/runs")
    public ResponseEntity<ApiResponse<Page<SyncRunResponse>>> listSyncRuns(
        @PathVariable UUID id,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<SyncRunResponse> runs = syncJobRepository
            .findByCohortIdOrderByStartedAtDesc(id, pageable)
            .map(SyncRunResponse::from);
        return ResponseEntity.ok(ApiResponse.success("Sync runs retrieved.", runs));
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
        CohortSyncJob job = syncJobRepository.findTopByCohortIdOrderByStartedAtDesc(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("No sync job found for cohort " + cohortId));

        UUID jobId = job.getId();
        LOG.debug("[sse-sync] cohort={} job={} client connected lastEventId={}", cohortId, jobId, lastEventId);

        SseEmitter emitter = sseRegistry.register(jobId);
        List<StandupGateEvent> allEvents = syncEventService.getEvents(jobId);

        int replayFrom = 0;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                replayFrom = Integer.parseInt(lastEventId.trim()) + 1;
            } catch (NumberFormatException ignored) {}
        }

        for (StandupGateEvent e : allEvents) {
            if (e.index() >= replayFrom) {
                try {
                    emitter.send(SseEmitter.event()
                        .id(String.valueOf(e.index()))
                        .name(e.event())
                        .data(e.payload()));
                } catch (IOException ex) {
                    LOG.debug("[sse-sync] cohort={} job={} replay failed — client disconnected", cohortId, jobId);
                    emitter.completeWithError(ex);
                    return emitter;
                }
            }
        }

        boolean alreadyDone = allEvents.stream().anyMatch(e -> "sync.done".equals(e.event()));
        if (alreadyDone || job.getStatus() != CohortSyncJobStatus.RUNNING) {
            emitter.complete();
        }

        return emitter;
    }
}