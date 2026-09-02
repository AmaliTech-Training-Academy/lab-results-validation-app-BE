package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.notification.NotificationTypes;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private InstructorContactRepository instructorContactRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private NotificationSseRegistry sseRegistry;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AuditEventService auditEventService;

    @InjectMocks
    private NotificationDispatchService notificationDispatchService;

    private UUID notificationId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        notificationId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private Notification pendingNotification() {
        return Notification.builder()
            .id(notificationId)
            .type("instructor_digest")
            .recipientKind("instructor")
            .dispatchPolicy("HELD")
            .status("PENDING")
            .build();
    }

    @Test
    void dismiss_pendingNotification_marksSkippedAndStampsActor() {
        Notification notification = pendingNotification();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification dismissed = notificationDispatchService.dismiss(notificationId, actorId);

        assertThat(dismissed.getStatus()).isEqualTo("SKIPPED");
        assertThat(dismissed.getDismissedBy()).isEqualTo(actorId);
        assertThat(dismissed.getDismissedAt()).isNotNull();
    }

    @Test
    void dismiss_pendingNotification_writesAuditEvent() {
        // FND-53 / C7 AC4 — dismiss must record who dismissed it and when.
        Notification notification = pendingNotification();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationDispatchService.dismiss(notificationId, actorId);

        verify(auditEventService).record(eq("NOTIFICATION_DISMISSED"), any(), eq(actorId), any());
    }

    @Test
    void dismiss_unknownNotification_throwsNotFound() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationDispatchService.dismiss(notificationId, actorId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dismiss_alreadySent_throwsUnprocessableAndDoesNotSave() {
        Notification notification = pendingNotification();
        notification.setStatus("SENT");
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationDispatchService.dismiss(notificationId, actorId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void sendNow_pendingInstructorNotification_locksRowBeforeSending() {
        // Regression test: sendNow must take the row lock (findByIdForUpdate) rather than a plain
        // findById, so two concurrent send attempts for the same notification serialize instead of
        // both passing the "not already SENT" check and emailing twice.
        Notification notification = pendingNotification();
        notification.setType(NotificationTypes.INSTRUCTOR_DIGEST);
        notification.setRecipientInstructorId(UUID.randomUUID());
        InstructorContact instructor = InstructorContact.builder().email("instructor@example.com").build();
        when(notificationRepository.findByIdForUpdate(notificationId)).thenReturn(Optional.of(notification));
        when(instructorContactRepository.findById(notification.getRecipientInstructorId()))
            .thenReturn(Optional.of(instructor));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification sent = notificationDispatchService.sendNow(notificationId, actorId);

        assertThat(sent.getStatus()).isEqualTo("SENT");
        verify(notificationRepository).findByIdForUpdate(notificationId);
        verify(notificationRepository, never()).findById(notificationId);
        verify(emailService).sendPlainEmailSync("instructor@example.com", null, null);
    }

    @Test
    void sendNow_manualSendByAnAdmin_writesAuditEvent() {
        // FND-53 / C7 AC4 — a manual send (a real actorId) must record who clicked and when.
        Notification notification = pendingNotification();
        notification.setType(NotificationTypes.INSTRUCTOR_DIGEST);
        notification.setRecipientInstructorId(UUID.randomUUID());
        InstructorContact instructor = InstructorContact.builder().email("instructor@example.com").build();
        when(notificationRepository.findByIdForUpdate(notificationId)).thenReturn(Optional.of(notification));
        when(instructorContactRepository.findById(notification.getRecipientInstructorId()))
            .thenReturn(Optional.of(instructor));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationDispatchService.sendNow(notificationId, actorId);

        verify(auditEventService).record(eq("NOTIFICATION_SENT"), any(), eq(actorId), any());
    }

    @Test
    void sendNow_systemAutoDispatchWithNoActor_writesNoAuditEvent() {
        // FND-53 / C7 AC4 — auto-dispatch (onNotificationsStaged) passes a null actorId: nobody
        // "clicked", so this must not flood the audit trail with one row per staged digest.
        Notification notification = pendingNotification();
        notification.setType(NotificationTypes.INSTRUCTOR_DIGEST);
        notification.setRecipientInstructorId(UUID.randomUUID());
        InstructorContact instructor = InstructorContact.builder().email("instructor@example.com").build();
        when(notificationRepository.findByIdForUpdate(notificationId)).thenReturn(Optional.of(notification));
        when(instructorContactRepository.findById(notification.getRecipientInstructorId()))
            .thenReturn(Optional.of(instructor));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationDispatchService.sendNow(notificationId, null);

        verifyNoInteractions(auditEventService);
    }

    @Test
    void sendNow_alreadySent_doesNotSendAgain() {
        Notification notification = pendingNotification();
        notification.setStatus("SENT");
        when(notificationRepository.findByIdForUpdate(notificationId)).thenReturn(Optional.of(notification));

        Notification result = notificationDispatchService.sendNow(notificationId, actorId);

        assertThat(result.getStatus()).isEqualTo("SENT");
        verify(emailService, never()).sendPlainEmailSync(any(), any(), any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void sendNow_nullRecipientInstructorId_marksFailedWithoutCrashing() {
        // Regression test: resolveRecipientEmail must null-check before findById(...) — passing null
        // straight through previously threw InvalidDataAccessApiUsageException instead of cleanly
        // marking the notification FAILED.
        Notification notification = pendingNotification();
        notification.setType(NotificationTypes.INSTRUCTOR_DIGEST);
        notification.setRecipientInstructorId(null);
        when(notificationRepository.findByIdForUpdate(notificationId)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification result = notificationDispatchService.sendNow(notificationId, actorId);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        verify(instructorContactRepository, never()).findById(any());
        verify(emailService, never()).sendPlainEmailSync(any(), any(), any());
    }
}
