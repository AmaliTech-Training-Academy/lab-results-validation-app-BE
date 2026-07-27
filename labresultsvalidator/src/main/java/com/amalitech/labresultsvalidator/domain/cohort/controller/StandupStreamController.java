package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandUpJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.StandupEventService;
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
public class StandupStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(StandupStreamController.class);

    private final CohortStandUpJobRepository jobRepository;
    private final StandupEventService eventService;
    private final StandupSseRegistry sseRegistry;

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
        CohortStandUpJob job = jobRepository.findTopByCohortIdOrderByStartedAtDesc(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No stand-up job found for cohort " + cohortId));

        UUID jobId = job.getId();
        LOG.debug("[sse] cohort={} job={} client connected lastEventId={}", cohortId, jobId, lastEventId);

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
                    LOG.debug("[sse] cohort={} job={} replay failed — client disconnected", cohortId, jobId);
                    emitter.completeWithError(ex);
                    return emitter;
                }
            }
        }

        // If the pipeline already finished (pipeline.done event exists), close the stream immediately.
        boolean alreadyDone = allEvents.stream().anyMatch(e -> "pipeline.done".equals(e.event()));
        if (alreadyDone || job.getStatus() != CohortStandUpJobStatus.RUNNING) {
            emitter.complete();
        }

        return emitter;
    }
}
