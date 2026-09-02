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

    // FND-58: a per-job monitor shared by SseGateEventStreamer.stream() (connect + replay stored
    // events) and each *EventService.emit() (append a new event + push it live). Without this, a
    // gate event emitted in the gap between a fresh client reading its stored-event snapshot and
    // that client's emitter being registered is silently lost forever — never in the snapshot
    // (already read), never delivered live (not registered yet) — which is what left gate steps
    // stuck on "Pending" after they'd actually passed. Never removed: one Object per job ever
    // created is a negligible, bounded-in-practice cost, and removing entries risks two callers
    // synchronizing on different objects for the same job (a classic striped-lock hazard) if a
    // removal races a lookup.
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(UUID jobId) {
        return locks.computeIfAbsent(jobId, id -> new Object());
    }

    public SseEmitter register(UUID jobId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(jobId, emitter);
        // Compare-and-remove: if the client reconnected (tab refresh, two tabs) before this
        // emitter's completion/timeout/error callback fires, a newer emitter has already replaced
        // this one in the map — an unconditional remove(jobId) would delete that active emitter
        // instead of this stale one, silently killing the live stream.
        emitter.onCompletion(() -> emitters.remove(jobId, emitter));
        emitter.onTimeout(() -> emitters.remove(jobId, emitter));
        emitter.onError(ex -> emitters.remove(jobId, emitter));
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
            emitters.remove(jobId, emitter);
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
