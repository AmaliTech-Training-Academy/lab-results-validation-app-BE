package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortStandUpService;
import com.amalitech.labresultsvalidator.domain.cohort.service.SseGateEventStreamer;
import com.amalitech.labresultsvalidator.domain.cohort.service.StandupEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohorts", description = "Cohort management and stand-up pipeline")
public class StandupStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(StandupStreamController.class);

    private final CohortStandUpService standUpService;
    private final StandupEventService eventService;
    private final SseGateEventStreamer sseStreamer;

    @Operation(
        summary = "Stream stand-up gate events",
        description = "Opens an SSE stream for the most recent stand-up job on a cohort. "
            + "Replays all stored gate events from Last-Event-ID+1 on reconnect. "
            + "Pass the JWT via ?token= since browser EventSource cannot send Authorization headers."
    )
    @GetMapping(value = "/{cohortId}/standup/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStandupEvents(
        @PathVariable UUID cohortId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        StreamJobHandle handle = standUpService.getLatestJobForStream(cohortId);
        LOG.debug("[sse] cohort={} job={} client connected lastEventId={}", cohortId, handle.jobId(), lastEventId);

        List<StandupGateEvent> events = eventService.getEvents(handle.jobId());
        return sseStreamer.stream(handle.jobId(), handle.running(), "pipeline.done", events, lastEventId, "sse");
    }
}
