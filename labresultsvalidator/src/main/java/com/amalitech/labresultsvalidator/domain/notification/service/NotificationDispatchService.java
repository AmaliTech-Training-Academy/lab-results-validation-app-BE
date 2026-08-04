package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.event.SyncJobNotificationsStagedEvent;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Separate from {@link EmailService} on purpose — {@code EmailService} stays a pure SMTP-transport
 * concern, while this owns notification retry/status semantics (idempotent send, FAILED capture,
 * log-and-continue batches).
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final NotificationRepository notificationRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    /** Auto-dispatch: fires once the staging transaction that produced these rows has committed. */
    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationsStaged(SyncJobNotificationsStagedEvent event) {
        List<Notification> pending = notificationRepository
            .findBySyncJobIdAndStatusAndDispatchPolicy(event.syncJobId(), "PENDING", "AUTO");
        for (Notification notification : pending) {
            try {
                sendNow(notification.getId(), null);
            } catch (RuntimeException ex) {
                // Log-and-continue — one bad notification must not stop the rest of the batch.
                LOG.warn("[notification] could not auto-dispatch notification {}: {}",
                    notification.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Async front door for the manual admin "send"/"retry" endpoint — hands off to {@link #sendNow}
     * on {@code emailTaskExecutor} so the HTTP request thread never blocks on the SMTP round-trip.
     * Exceptions are logged rather than thrown since nothing is left listening for the result.
     */
    @Async("emailTaskExecutor")
    public void sendAsync(UUID notificationId, UUID actorId) {
        try {
            sendNow(notificationId, actorId);
        } catch (RuntimeException ex) {
            LOG.warn("[notification] manual send failed for {}: {}", notificationId, ex.getMessage());
        }
    }

    /**
     * Count of PENDING/HELD notifications for one sync run a "send all" call would queue — for the
     * response, before firing it.
     */
    @Transactional(readOnly = true)
    public long countHeldPending(UUID syncJobId) {
        return notificationRepository.countBySyncJobIdAndStatusAndDispatchPolicy(syncJobId, "PENDING", "HELD");
    }

    /**
     * Manual admin action: sends every currently PENDING HELD notification for one sync run at
     * once, off-thread so the request returns immediately. Same log-and-continue batching as
     * {@link #onNotificationsStaged} — one bad notification does not stop the rest. Calls
     * {@link #sendNow} directly (not {@link #sendAsync}) so the whole batch runs as a single async
     * unit of work rather than one hop per notification.
     */
    @Async("emailTaskExecutor")
    public void sendAllHeldAsync(UUID syncJobId, UUID actorId) {
        List<Notification> held = notificationRepository
            .findBySyncJobIdAndStatusAndDispatchPolicy(syncJobId, "PENDING", "HELD");
        for (Notification notification : held) {
            try {
                sendNow(notification.getId(), actorId);
            } catch (RuntimeException ex) {
                LOG.warn("[notification] could not send held notification {} via send-all: {}",
                    notification.getId(), ex.getMessage());
            }
        }
    }

    /**
     * The single shared send primitive — used by the auto-dispatch listener above and by
     * {@link #sendAsync}. Idempotent: a notification already {@code SENT} is a no-op.
     * {@code actorId} is null for system-triggered auto-dispatch, set for a manual send.
     */
    @Transactional
    public Notification sendNow(UUID notificationId, UUID actorId) {
        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));

        if ("SENT".equals(notification.getStatus())) {
            return notification;
        }

        String recipientEmail = resolveRecipientEmail(notification);
        notification.setSentBy(actorId);

        if (recipientEmail == null) {
            notification.setStatus("FAILED");
            notification.setErrorDetail("Could not resolve a recipient email address.");
            return notificationRepository.save(notification);
        }

        try {
            // Synchronous — the caller (batch listener or manual endpoint) needs this call to have
            // actually completed before it decides SENT vs FAILED; EmailService's async variant
            // would return before the real outcome is known.
            emailService.sendPlainEmailSync(recipientEmail, notification.getSubject(), notification.getBody());
            notification.setStatus("SENT");
            notification.setSentAt(OffsetDateTime.now());
            notification.setErrorDetail(null);
        } catch (RuntimeException ex) {
            notification.setStatus("FAILED");
            notification.setErrorDetail(ex.getMessage());
        }
        return notificationRepository.save(notification);
    }

    /**
     * Manual admin action: takes a {@code PENDING} notification (one not yet sent) permanently out
     * of the send queue by marking it {@code SKIPPED} — e.g. the digest is no longer relevant.
     * Anything already {@code SENT}/{@code FAILED}/{@code SKIPPED} is rejected rather than silently
     * no-op'd, since dismissing an already-sent notification would be a lie about what happened.
     */
    @Transactional
    public Notification dismiss(UUID notificationId, UUID actorId) {
        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));

        if (!"PENDING".equals(notification.getStatus())) {
            throw new UnprocessableEntityException(
                "Notification " + notificationId + " is " + notification.getStatus()
                    + " and cannot be dismissed; only a PENDING notification can be dismissed.");
        }

        notification.setStatus("SKIPPED");
        notification.setDismissedBy(actorId);
        notification.setDismissedAt(OffsetDateTime.now());
        return notificationRepository.save(notification);
    }

    private String resolveRecipientEmail(Notification notification) {
        if ("instructor".equals(notification.getRecipientKind())) {
            return instructorContactRepository.findById(notification.getRecipientInstructorId())
                .map(InstructorContact::getEmail)
                .orElse(null);
        }
        return userRepository.findById(notification.getRecipientUserId())
            .map(User::getEmail)
            .orElse(null);
    }
}