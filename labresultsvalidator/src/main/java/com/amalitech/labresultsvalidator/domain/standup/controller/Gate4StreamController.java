package com.amalitech.labresultsvalidator.domain.standup.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.standup.dto.Gate4JobResponse;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.standup.service.CohortGate4Service;
import com.amalitech.labresultsvalidator.domain.standup.service.Gate4EventService;
import com.amalitech.labresultsvalidator.domain.standup.service.SseGateEventStreamer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohorts", description = "Cohort management and stand-up pipeline")
public class Gate4StreamController {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4StreamController.class);

    private final CohortGate4Service gate4Service;
    private final Gate4EventService eventService;
    private final SseGateEventStreamer sseStreamer;

    @Operation(
        summary = "Stream Gate 4 score sheet validation events",
        description = "Opens an SSE stream for the most recent Gate 4 job on a cohort. "
            + "Replays all stored events from Last-Event-ID+1 on reconnect. "
            + "Pass the JWT via ?token= since browser EventSource cannot send Authorization headers."
    )
    @GetMapping(value = "/{cohortId}/gate4/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGate4Events(
        @PathVariable UUID cohortId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        StreamJobHandle handle = gate4Service.getLatestJobForStream(cohortId);
        LOG.debug("[sse-gate4] cohort={} job={} client connected lastEventId={}",
            cohortId, handle.jobId(), lastEventId);

        // FND-58: see StandupStreamController — events are read lazily, inside sseStreamer's lock.
        return sseStreamer.stream(handle.jobId(), handle.running(), "gate4.done",
            () -> eventService.getEvents(handle.jobId()), lastEventId, "sse-gate4");
    }

    @Operation(summary = "List Gate 4 runs",
        description = "Returns a paginated list of all Gate 4 jobs for a cohort, newest first. Used by "
            + "the frontend on reload (FND-58) to decide whether to re-attach the Gate 4 stream.")
    @GetMapping("/{cohortId}/gate4/runs")
    public ResponseEntity<ApiResponse<Page<Gate4JobResponse>>> listGate4Runs(
        @PathVariable UUID cohortId,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success("Gate 4 runs retrieved.", gate4Service.listRuns(cohortId, pageable)));
    }
}
