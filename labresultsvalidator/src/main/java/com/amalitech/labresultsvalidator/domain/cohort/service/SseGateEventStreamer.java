package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Shared replay/emit logic for the cohort pipeline's SSE endpoints (sync, stand-up, Gate 4).
 * Each stream replays stored events from {@code Last-Event-ID + 1} on reconnect, then closes
 * the emitter once the job's terminal event has been seen or the job is no longer running.
 */
@Component
@RequiredArgsConstructor
public class SseGateEventStreamer {

    private static final Logger LOG = LoggerFactory.getLogger(SseGateEventStreamer.class);

    private final StandupSseRegistry sseRegistry;

    public SseEmitter stream(
        UUID jobId,
        boolean jobStillRunning,
        String doneEventName,
        List<StandupGateEvent> events,
        String lastEventId,
        String logTag
    ) {
        SseEmitter emitter = sseRegistry.register(jobId);
        int replayFrom = parseReplayFrom(lastEventId);

        for (StandupGateEvent e : events) {
            if (e.index() < replayFrom) {
                continue;
            }
            try {
                emitter.send(SseEmitter.event()
                    .id(String.valueOf(e.index()))
                    .name(e.event())
                    .data(e.payload()));
            } catch (IOException ex) {
                LOG.debug("[{}] job={} replay failed — client disconnected", logTag, jobId);
                emitter.completeWithError(ex);
                return emitter;
            }
        }

        boolean alreadyDone = events.stream().anyMatch(e -> doneEventName.equals(e.event()));
        if (alreadyDone || !jobStillRunning) {
            emitter.complete();
        }
        return emitter;
    }

    private int parseReplayFrom(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(lastEventId.trim()) + 1;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
