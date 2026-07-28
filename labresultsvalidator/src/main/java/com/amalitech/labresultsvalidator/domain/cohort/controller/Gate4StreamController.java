package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4Job;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4JobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortGate4JobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.Gate4EventService;
import com.amalitech.labresultsvalidator.domain.cohort.service.StandupSseRegistry;
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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohorts", description = "Cohort management and stand-up pipeline")
public class Gate4StreamController {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4StreamController.class);

    private final CohortGate4JobRepository jobRepository;
    private final Gate4EventService eventService;
    private final StandupSseRegistry sseRegistry;

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
        CohortGate4Job job = jobRepository.findTopByCohortIdOrderByStartedAtDesc(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No Gate 4 job found for cohort " + cohortId));

        UUID jobId = job.getId();
        LOG.debug("[sse-gate4] cohort={} job={} client connected lastEventId={}", cohortId, jobId, lastEventId);

        SseEmitter emitter = sseRegistry.register(jobId);
        List<StandupGateEvent> allEvents = eventService.getEvents(jobId);

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
                    LOG.debug("[sse-gate4] cohort={} job={} replay failed — client disconnected", cohortId, jobId);
                    emitter.completeWithError(ex);
                    return emitter;
                }
            }
        }

        boolean alreadyDone = allEvents.stream().anyMatch(e -> "gate4.done".equals(e.event()));
        if (alreadyDone || job.getStatus() != CohortGate4JobStatus.RUNNING) {
            emitter.complete();
        }

        return emitter;
    }
}
