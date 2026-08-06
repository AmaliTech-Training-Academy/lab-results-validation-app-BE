package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.standup.gate.GateError;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationAlertServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationDispatchService dispatchService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CohortRepository cohortRepository;

    private NotificationAlertService service;

    private UUID cohortId;
    private UUID syncJobId;
    private UUID adminOneId;
    private UUID adminTwoId;

    @BeforeEach
    void setUp() {
        service = new NotificationAlertService(notificationRepository, dispatchService, userRepository,
            cohortRepository, new ObjectMapper());

        cohortId = UUID.randomUUID();
        syncJobId = UUID.randomUUID();
        adminOneId = UUID.randomUUID();
        adminTwoId = UUID.randomUUID();

        when(cohortRepository.findById(cohortId))
            .thenReturn(Optional.of(Cohort.builder().id(cohortId).name("Cohort 7").build()));
        when(userRepository.findAllByRoleAndIsActiveTrue(UserRole.ADMIN)).thenReturn(List.of(
            User.builder().id(adminOneId).email("admin1@amalitech.com").passwordHash("h").build(),
            User.builder().id(adminTwoId).email("admin2@amalitech.com").passwordHash("h").build()));
        when(notificationRepository.save(any())).thenAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    /** C5 AC1 — staged AUTO and handed straight to dispatch, not held for the digest. */
    @Test
    void alertStandupFailure_isStagedAutoForEveryAdminAndDispatchedImmediately() {
        service.alertStandupFailure(cohortId, "Gate 2 (folder structure)",
            List.of(new GateError("cohort root", "folder", "G2-MISSING-FOLDER", "Lab Scores not found")));

        List<Notification> staged = staged(2);
        assertThat(staged).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo("standup_failure");
            assertThat(notification.getDispatchPolicy()).isEqualTo("AUTO");
            assertThat(notification.getRecipientKind()).isEqualTo("admin");
            assertThat(notification.getSubject()).contains("Cohort 7", "Gate 2");
            assertThat(notification.getBody()).contains("G2-MISSING-FOLDER", "Lab Scores not found");
        });
        assertThat(staged).extracting(Notification::getRecipientUserId)
            .containsExactlyInAnyOrder(adminOneId, adminTwoId);
        // One dispatch per admin, each system-attributed (null actor) since nobody asked for it.
        verify(dispatchService, times(2)).sendAsync(any(), eq(null));
    }

    @Test
    void alertConflictsPending_namesTheConflictCount() {
        service.alertConflictsPending(cohortId, syncJobId, 3);

        Notification notification = staged(2).get(0);
        assertThat(notification.getType()).isEqualTo("conflict_alert");
        assertThat(notification.getSubject()).contains("3 conflict(s)");
    }

    /** C5 AC2 — staged like the rest; suppressing the email is the dispatcher's job. */
    @Test
    void confirmStoodUp_isStagedAsStoodUpForEveryAdmin() {
        service.confirmStoodUp(cohortId);

        List<Notification> staged = staged(2);
        assertThat(staged).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo("stood_up");
            assertThat(notification.getDispatchPolicy()).isEqualTo("AUTO");
            assertThat(notification.getSubject()).contains("Cohort stood up", "Cohort 7");
        });
    }

    @Test
    void alert_noActiveAdmins_stagesNothing() {
        when(userRepository.findAllByRoleAndIsActiveTrue(UserRole.ADMIN)).thenReturn(List.of());

        service.alertConflictsPending(cohortId, syncJobId, 3);

        verify(notificationRepository, never()).save(any());
        verify(dispatchService, never()).sendAsync(any(), any());
    }

    /** A deleted cohort must not cost admins the alert entirely. */
    @Test
    void alert_cohortNoLongerExists_stillStagesUsingTheId() {
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        service.alertConflictsPending(cohortId, syncJobId, 1);

        assertThat(staged(2).get(0).getSubject()).contains(cohortId.toString());
    }

    private List<Notification> staged(int expectedCount) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }
}
