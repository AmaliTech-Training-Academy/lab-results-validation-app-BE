package com.amalitech.labresultsvalidator.domain.sync.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictResolutionAction;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ResolveConflictRequest;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortSyncServiceTest {

    @Mock
    private CohortRepository cohortRepository;
    @Mock
    private CohortSyncJobRepository syncJobRepository;
    @Mock
    private IngestionRunRepository ingestionRunRepository;
    @Mock
    private IngestionConflictRepository ingestionConflictRepository;
    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private LabReferenceAuditLogRepository labReferenceAuditLogRepository;
    @Mock
    private CohortSyncJobRunner syncJobRunner;
    @Mock
    private SyncEventService syncEventService;
    @Mock
    private AuditEventService auditEventService;

    @InjectMocks
    private CohortSyncService cohortSyncService;

    private final User actor = User.builder()
        .id(UUID.randomUUID())
        .email("admin@test.com")
        .passwordHash("hashed")
        .role(UserRole.ADMIN)
        .isActive(true)
        .build();

    private UUID cohortId;
    private UUID conflictId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        cohortId = UUID.randomUUID();
        conflictId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private IngestionConflict pendingConflict(UUID existingResultId) {
        return IngestionConflict.builder()
            .id(conflictId)
            .ingestionRunId(UUID.randomUUID())
            .learnerId(UUID.randomUUID())
            .labId(UUID.randomUUID())
            .existingResultId(existingResultId)
            .incomingPayloadJson(
                "{\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"95.00\"}")
            .status("PENDING")
            .build();
    }

    @Test
    void resolveConflict_keepExisting_marksResolvedWithoutTouchingLabResults() {
        IngestionConflict conflict = pendingConflict(UUID.randomUUID());
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestionConflictResponse response = cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_EXISTING).build());

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolvedBy()).isEqualTo(actor.getId());
        verify(labResultRepository, never()).save(any());
        verify(labReferenceAuditLogRepository, never()).save(any());
        verify(auditEventService).record(eq("CONFLICT_RESOLVED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void resolveConflict_keepIncoming_updatesExistingLabResultAndLogsAudit() {
        UUID existingResultId = UUID.randomUUID();
        IngestionConflict conflict = pendingConflict(existingResultId);
        LabResult existing = LabResult.builder()
            .id(existingResultId)
            .score(new BigDecimal("80.00"))
            .submittedOn(LocalDate.of(2026, 1, 1))
            .build();

        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(labResultRepository.findById(existingResultId)).thenReturn(Optional.of(existing));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestionConflictResponse response = cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build());

        assertThat(response.status()).isEqualTo("RESOLVED");

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getScore()).isEqualByComparingTo("95.00");
        assertThat(resultCaptor.getValue().getSubmittedOn()).isEqualTo(LocalDate.of(2026, 1, 15));

        ArgumentCaptor<LabReferenceAuditLog> auditCaptor = ArgumentCaptor.forClass(LabReferenceAuditLog.class);
        verify(labReferenceAuditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldValue()).isEqualTo("80.00");
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("95.00");

        verify(auditEventService).record(eq("CONFLICT_RESOLVED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void resolveConflict_keepIncomingWithNoExistingResult_createsNewLabResult() {
        IngestionConflict conflict = pendingConflict(null);
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build());

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getId()).isNull();
        assertThat(resultCaptor.getValue().getLearnerId()).isEqualTo(conflict.getLearnerId());
        assertThat(resultCaptor.getValue().getLabId()).isEqualTo(conflict.getLabId());
        assertThat(resultCaptor.getValue().getScore()).isEqualByComparingTo("95.00");
        verify(labReferenceAuditLogRepository, never()).save(any());
        verify(auditEventService).record(eq("CONFLICT_RESOLVED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void resolveConflict_reject_marksDismissedWithoutTouchingLabResults() {
        IngestionConflict conflict = pendingConflict(UUID.randomUUID());
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestionConflictResponse response = cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.REJECT).build());

        assertThat(response.status()).isEqualTo("DISMISSED");
        verify(labResultRepository, never()).save(any());
        verify(auditEventService).record(eq("CONFLICT_DISMISSED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void resolveConflict_unknownConflict_throwsNotFound() {
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_EXISTING).build()))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(auditEventService, never()).record(any(), any(), any(), any());
    }

    @Test
    void resolveConflict_alreadyResolved_throwsUnprocessable() {
        IngestionConflict conflict = pendingConflict(UUID.randomUUID());
        conflict.setStatus("RESOLVED");
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_EXISTING).build()))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(ingestionConflictRepository, never()).save(any());
        verify(auditEventService, never()).record(any(), any(), any(), any());
    }

    /**
     * Reproduces the fallback path in LabResultCommitService#buildPayloadJson: when the incoming
     * row failed to serialize at ingestion time, the conflict's payload is stored as the sentinel
     * {@code {"error":"serialization failed"}} rather than the real row. Resolving such a conflict
     * with KEEP_INCOMING must not crash the request with a raw NPE.
     */
    @Test
    void resolveConflict_keepIncomingWithCorruptedPayload_throwsUnprocessableInsteadOfCrashing() {
        IngestionConflict conflict = IngestionConflict.builder()
            .id(conflictId)
            .ingestionRunId(UUID.randomUUID())
            .learnerId(UUID.randomUUID())
            .labId(UUID.randomUUID())
            .incomingPayloadJson("{\"error\":\"serialization failed\"}")
            .status("PENDING")
            .build();
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId))
            .thenReturn(Optional.of(conflict));

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build()))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(labResultRepository, never()).save(any());
        verify(ingestionConflictRepository, never()).save(any());
    }

    @Test
    void resolveConflict_keepIncomingWithMalformedInstructorContactId_throwsUnprocessableInsteadOfCrashing() {
        IngestionConflict conflict = IngestionConflict.builder()
            .id(conflictId)
            .ingestionRunId(UUID.randomUUID())
            .learnerId(UUID.randomUUID())
            .labId(UUID.randomUUID())
            .incomingPayloadJson(
                "{\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"95.00\","
                    + "\"instructorContactId\":\"not-a-uuid\"}")
            .status("PENDING")
            .build();
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId))
            .thenReturn(Optional.of(conflict));

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build()))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(labResultRepository, never()).save(any());
    }
}
