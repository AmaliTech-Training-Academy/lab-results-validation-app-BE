package com.amalitech.labresultsvalidator.domain.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcast SSE registry for live notification status updates.
 *
 * <p>Deliberately different shape from {@code StandupSseRegistry}/{@code SyncEventService} etc.,
 * which key one emitter per job ID because exactly one client cares about one job's progress.
 * Here, every admin viewing the notifications list needs every status change regardless of who
 * (or what) triggered it — auto-dispatch after a sync run, a manual send/retry, send-all, or a
 * dismiss — so this fans a single event out to every currently-open connection instead of
 * addressing one by key.
 *
 * <p>No persisted event log / {@code Last-Event-ID} replay here (unlike the job streams): a
 * client that misses an event during a brief disconnect just reconciles against
 * {@code GET /api/v1/notifications}, which already reflects the latest status. A periodic
 * heartbeat comment keeps the connection alive through proxies that close idle connections.
 *
 * <p>In-memory and single-instance: if this ever runs behind multiple replicas, a status change
 * handled by instance A won't reach a client connected to instance B. Fine at current scale;
 * would need a shared pub/sub (e.g. Redis) behind this registry if that changes.
 */
@Component
public class NotificationSseRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationSseRegistry.class);

    // Long-lived: unlike a job stream, there's no natural "done" event to close on, so the client
    // is expected to keep this open for as long as the notifications page is; EventSource
    // reconnects transparently if this timeout is ever hit.
    private static final long TIMEOUT_MS = 30 * 60 * 1_000L; // 30 minutes

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        LOG.debug("[notification-sse] client connected, {} active", emitters.size());
        return emitter;
    }

    /** Pushes {@code eventName}/{@code payload} to every currently-connected client. */
    public void broadcast(String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception ex) {
                // Broad on purpose: a stale/disconnected emitter can surface as IOException,
                // IllegalStateException, or Spring's AsyncRequestNotUsableException depending on
                // exactly when the client dropped — none of those should stop the broadcast to
                // everyone else, or bubble up as an application error.
                LOG.debug("[notification-sse] client disconnected during broadcast: {}", ex.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    /** Keep-alive comment so idle connections survive proxy/load-balancer timeouts. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception ex) {
                LOG.debug("[notification-sse] pruning dead connection during heartbeat: {}", ex.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
