package com.amalitech.labresultsvalidator.domain.standup.controller;

import com.amalitech.labresultsvalidator.domain.standup.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.standup.service.CohortGate4Service;
import com.amalitech.labresultsvalidator.domain.standup.service.Gate4EventService;
import com.amalitech.labresultsvalidator.domain.standup.service.SseGateEventStreamer;
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

        List<StandupGateEvent> events = eventService.getEvents(handle.jobId());
        return sseStreamer.stream(handle.jobId(), handle.running(), "gate4.done", events, lastEventId, "sse-gate4");
    }
}
