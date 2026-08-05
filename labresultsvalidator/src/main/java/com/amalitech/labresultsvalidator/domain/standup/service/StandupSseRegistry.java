package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.standup.dto.StandupGateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StandupSseRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(StandupSseRegistry.class);
    private static final long TIMEOUT_MS = 10 * 60 * 1_000L; // 10 minutes

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID jobId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(ex -> emitters.remove(jobId));
        return emitter;
    }

    public void send(UUID jobId, StandupGateEvent event) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                .id(String.valueOf(event.index()))
                .name(event.event())
                .data(event.payload()));
        } catch (IOException ex) {
            LOG.debug("[sse] client disconnected for job {}", jobId);
            emitters.remove(jobId);
        }
    }

    public void complete(UUID jobId) {
        SseEmitter emitter = emitters.remove(jobId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }
}
