package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import com.amalitech.labresultsvalidator.domain.notification.event.SyncJobNotificationsStagedEvent;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationStagingServiceTest {

    @Mock
    private IngestionRunRepository ingestionRunRepository;
    @Mock
    private InstructorContactRepository instructorContactRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationSettingsService notificationSettingsService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private NotificationStagingService service;

    private UUID cohortId;
    private UUID syncJobId;
    private UUID actorId;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new NotificationStagingService(ingestionRunRepository, instructorContactRepository,
            userRepository, notificationRepository, notificationSettingsService, eventPublisher,
            new ObjectMapper());

        cohortId = UUID.randomUUID();
        syncJobId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        admin = User.builder().id(UUID.randomUUID()).email("admin@example.com").role(UserRole.ADMIN).build();

        lenient().when(notificationSettingsService.getSettings())
            .thenReturn(NotificationSettings.builder().autoSendInstructorEmails(false).build());
    }

    private IngestionRun highFailureRun(String filename) {
        return IngestionRun.builder()
            .id(UUID.randomUUID())
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .workbookFilename(filename)
            .highFailureRate(true)
            .failureRatePercent(66.7)
            .rowsRead(9)
            .skippedInvalid(6)
            .errorReportJson(null)
            .build();
    }

    @Test
    void stageForSyncJob_highFailureRateRun_stagesAdminNotificationEvenWithNoRowIssues() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(highFailureRun("scores.xlsx")));
        when(userRepository.findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(UserRole.ADMIN))
            .thenReturn(Optional.of(admin));
        when(notificationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.stageForSyncJob(cohortId, syncJobId, actorId);

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        List<Notification> staged = captor.getValue();

        assertThat(staged).hasSize(1);
        Notification notification = staged.get(0);
        assertThat(notification.getType()).isEqualTo("high_failure");
        assertThat(notification.getRecipientKind()).isEqualTo("admin");
        assertThat(notification.getRecipientUserId()).isEqualTo(admin.getId());
        assertThat(notification.getDispatchPolicy()).isEqualTo("AUTO");
        assertThat(notification.getStatus()).isEqualTo("PENDING");
        assertThat(notification.getSubject()).contains("High failure rate");
        assertThat(notification.getBody()).contains("scores.xlsx").contains("66.7%");
        verify(eventPublisher).publishEvent(any(SyncJobNotificationsStagedEvent.class));
    }

    @Test
    void stageForSyncJob_highFailureRateButNoActiveAdmin_stagesNothingAndPublishesNothing() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(highFailureRun("scores.xlsx")));
        when(userRepository.findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(UserRole.ADMIN))
            .thenReturn(Optional.empty());

        service.stageForSyncJob(cohortId, syncJobId, actorId);

        verify(notificationRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void stageForSyncJob_noIssuesAndNoHighFailureRate_stagesNothing() {
        IngestionRun cleanRun = IngestionRun.builder()
            .id(UUID.randomUUID())
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .workbookFilename("clean.xlsx")
            .highFailureRate(false)
            .errorReportJson(null)
            .build();
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(cleanRun));

        service.stageForSyncJob(cohortId, syncJobId, actorId);

        verify(notificationRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(userRepository, never()).findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(any());
    }

    @Test
    void stageForSyncJob_highFailureRateAndUnattributedIssue_stagesTwoDistinctAdminNotifications() {
        String issuesJson = "[{\"file\":\"scores.xlsx\",\"location\":\"A2\",\"rule\":\"R5-UNKNOWN-REVIEWER\","
            + "\"message\":\"Unknown reviewer\",\"instructorContactId\":null}]";
        IngestionRun run = highFailureRun("scores.xlsx");
        run.setErrorReportJson(issuesJson);

        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run));
        when(userRepository.findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(UserRole.ADMIN))
            .thenReturn(Optional.of(admin));
        when(notificationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.stageForSyncJob(cohortId, syncJobId, actorId);

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        List<Notification> staged = captor.getValue();

        assertThat(staged).extracting(Notification::getType)
            .containsExactlyInAnyOrder("admin_run_digest", "high_failure");
        assertThat(staged).allMatch(n -> n.getRecipientUserId().equals(admin.getId()));
    }
}
