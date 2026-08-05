package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
