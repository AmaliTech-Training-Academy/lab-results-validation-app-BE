package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
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
     * The single shared send primitive — used by the auto-dispatch listener above and by the
     * manual admin "send"/"retry" endpoint. Idempotent: a notification already {@code SENT} is a
     * no-op. {@code actorId} is null for system-triggered auto-dispatch, set for a manual send.
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