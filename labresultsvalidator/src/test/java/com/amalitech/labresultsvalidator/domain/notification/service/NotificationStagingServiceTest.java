package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.reference.dto.LabModuleName;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationStagingServiceTest {

    @Mock
    private IngestionRunRepository ingestionRunRepository;
    @Mock
    private LabRepository labRepository;
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
    private UUID instructorId;
    private UUID adminOneId;
    private UUID adminTwoId;

    @BeforeEach
    void setUp() {
        service = new NotificationStagingService(ingestionRunRepository, labRepository,
            instructorContactRepository, userRepository, notificationRepository,
            notificationSettingsService, eventPublisher, new ObjectMapper(), true);

        cohortId = UUID.randomUUID();
        syncJobId = UUID.randomUUID();
        instructorId = UUID.randomUUID();
        adminOneId = UUID.randomUUID();
        adminTwoId = UUID.randomUUID();

        when(notificationSettingsService.getSettings())
            .thenReturn(NotificationSettings.builder().autoSendInstructorEmails(false).build());
        when(labRepository.findLabModuleNamesByCohortId(cohortId))
            .thenReturn(List.of(new LabModuleName("REST API Basics", "Backend Module 1")));
        when(instructorContactRepository.findAllById(anyCollection())).thenReturn(List.of(
            InstructorContact.builder().id(instructorId).instructorId("INS-001")
                .fullName("Kofi Mensah").email("kofi@amalitech.com").isActive(true).build()));
        when(userRepository.findAllByRoleAndIsActiveTrue(UserRole.ADMIN)).thenReturn(List.of(
            User.builder().id(adminOneId).email("admin1@amalitech.com").passwordHash("h").build(),
            User.builder().id(adminTwoId).email("admin2@amalitech.com").passwordHash("h").build()));
    }

    /** C4 AC1 — every active admin gets their own digest, not one standing in for the rest. */
    @Test
    void stageForSyncJob_adminDigest_isStagedForEveryActiveAdmin() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run(10, 4, 2, 1, 3, 0)));

        service.stageForSyncJob(cohortId, syncJobId, null);

        assertThat(adminDigests()).hasSize(2);
        assertThat(adminDigests()).extracting(Notification::getRecipientUserId)
            .containsExactlyInAnyOrder(adminOneId, adminTwoId);
        assertThat(adminDigests()).allSatisfy(digest ->
            assertThat(digest.getDispatchPolicy()).isEqualTo("AUTO"));
    }

    /** C4 AC1 — "given a run completes", so a run with nothing wrong still reports its counts. */
    @Test
    void stageForSyncJob_cleanRunWithNoIssues_stillStagesTheAdminDigest() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run(10, 10, 0, 0, 0, 0)));

        service.stageForSyncJob(cohortId, syncJobId, null);

        assertThat(adminDigests()).hasSize(2);
        assertThat(adminDigests().get(0).getBody()).contains("Rows read", "Skipped");
    }

    /** C4 AC1 — all six counts, summed across the run's files, plus the high-failure roll-up. */
    @Test
    void stageForSyncJob_adminDigestBody_carriesAllSixCountsAndHighFailureFiles() {
        IngestionRun flagged = run(6, 1, 0, 4, 1, 2);
        flagged.setHighFailureRate(true);
        flagged.setWorkbookFilename("Messy.xlsx");
        when(ingestionRunRepository.findBySyncJobId(syncJobId))
            .thenReturn(List.of(run(10, 4, 2, 1, 3, 0), flagged));

        service.stageForSyncJob(cohortId, syncJobId, null);

        Notification digest = adminDigests().get(0);
        assertThat(digest.getSubject()).contains("2 file(s)", "16 row(s) read");
        assertThat(digest.getBody())
            .contains("Rows read")
            .contains("Skipped — invalid")
            .contains("Skipped — unchanged")
            .contains("Conflicts awaiting resolution")
            .contains("Files flagged high-failure")
            .contains("Messy.xlsx");
        assertThat(digest.getPayloadJson()).contains("\"rowsRead\":16", "\"skippedUnchanged\":4");
    }

    /** C3 AC2 — rejected rows are grouped under their module, with the run date stated. */
    @Test
    void stageForSyncJob_instructorDigest_groupsRowsByModuleAndStatesTheRunDate() {
        IngestionRun run = run(2, 0, 0, 2, 0, 0);
        run.setRunAt(OffsetDateTime.parse("2026-08-03T10:00:00Z"));
        run.setErrorReportJson("""
            [{"file":"Backend.xlsx","location":"sheet BEM01 row 4","rule":"F2-INVALID-SCORE",
              "message":"not numeric","instructorContactId":"%s","labTitle":"REST API Basics"},
             {"file":"Backend.xlsx","location":"sheet BEM01 row 5","rule":"R1-UNKNOWN-NSP",
              "message":"unknown NSP","instructorContactId":"%s","labTitle":"Mystery Lab"}]
            """.formatted(instructorId, instructorId));
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run));

        service.stageForSyncJob(cohortId, syncJobId, null);

        Notification digest = digestsOfType("instructor_digest").get(0);
        assertThat(digest.getBody())
            .contains("Run of 2026-08-03")
            .contains("Backend Module 1")
            // A lab title matching no configured lab must not be silently dropped.
            .contains("Unmatched labs")
            .contains("Rejected: 1");
    }

    /** C11 AC1 — provisional while the flag is set; nothing when sign-off flips it off. */
    @Test
    void stageForSyncJob_provisionalFlagOff_omitsTheBanner() {
        service = new NotificationStagingService(ingestionRunRepository, labRepository,
            instructorContactRepository, userRepository, notificationRepository,
            notificationSettingsService, eventPublisher, new ObjectMapper(), false);
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run(1, 1, 0, 0, 0, 0)));

        service.stageForSyncJob(cohortId, syncJobId, null);

        assertThat(adminDigests().get(0).getBody()).doesNotContain("PROVISIONAL FORMAT");
    }

    @Test
    void stageForSyncJob_provisionalFlagOn_rendersTheBanner() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of(run(1, 1, 0, 0, 0, 0)));

        service.stageForSyncJob(cohortId, syncJobId, null);

        assertThat(adminDigests().get(0).getBody()).contains("PROVISIONAL FORMAT", "Decision Log Q3");
    }

    @Test
    void stageForSyncJob_noRunsForTheJob_stagesNothing() {
        when(ingestionRunRepository.findBySyncJobId(syncJobId)).thenReturn(List.of());

        service.stageForSyncJob(cohortId, syncJobId, null);

        verify(notificationRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Notification> staged() {
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private List<Notification> adminDigests() {
        return digestsOfType("admin_run_digest");
    }

    private List<Notification> digestsOfType(String type) {
        return staged().stream().filter(n -> type.equals(n.getType())).toList();
    }

    private IngestionRun run(int rowsRead, int committedNew, int updatedCount, int skippedInvalid,
                             int skippedUnchanged, int conflictsCount) {
        IngestionRun run = IngestionRun.builder()
            .id(UUID.randomUUID())
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .workbookFilename("Backend.xlsx")
            .build();
        run.setStatus("completed");
        run.setRowsRead(rowsRead);
        run.setCommittedNew(committedNew);
        run.setUpdatedCount(updatedCount);
        run.setSkippedInvalid(skippedInvalid);
        run.setSkippedUnchanged(skippedUnchanged);
        run.setConflictsCount(conflictsCount);
        return run;
    }
}
