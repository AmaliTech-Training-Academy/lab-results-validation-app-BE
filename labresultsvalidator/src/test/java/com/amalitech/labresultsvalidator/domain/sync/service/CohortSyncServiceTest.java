package com.amalitech.labresultsvalidator.domain.sync.service;

import com.amalitech.labresultsvalidator.common.exceptions.ConflictStateException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictResolutionAction;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ResolveConflictRequest;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.ConflictPayloadCodec;
import com.amalitech.labresultsvalidator.domain.grading.service.IngestionConflictViewAssembler;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncFileRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    private CohortSyncFileRepository syncFileRepository;
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
    @Mock
    private IngestionConflictViewAssembler conflictViewAssembler;

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
        stubAssembler();
    }

    /**
     * The assembler only adds reference data (learner name, lab title, stored score) on top of
     * IngestionConflictResponse.from, so tests stub it with exactly that base view and assert on the
     * conflict state the service itself decided.
     */
    private void stubAssembler() {
        lenient().when(conflictViewAssembler.assemble(any(IngestionConflict.class), eq(cohortId)))
            .thenAnswer(inv -> IngestionConflictResponse.from(inv.getArgument(0), cohortId));
        // JpaRepository.save returns the persisted entity, which the service uses to attach the
        // grade-history record to the affected row.
        lenient().when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
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

    /** A duplicate held as one conflict with two copies of the row at different marks — QA's case. */
    private IngestionConflict pendingDuplicate(UUID existingResultId) {
        return IngestionConflict.builder()
            .id(conflictId)
            .ingestionRunId(UUID.randomUUID())
            .learnerId(UUID.randomUUID())
            .labId(UUID.randomUUID())
            .existingResultId(existingResultId)
            .incomingPayloadJson("{\"candidates\":["
                + "{\"fileName\":\"Module 1 Grading.xlsx\",\"sheetName\":\"Module-1\",\"rowNum\":5,"
                + "\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"88.00\","
                + "\"instructorContactId\":null},"
                + "{\"fileName\":\"Module 1 Grading.xlsx\",\"sheetName\":\"Module-1\",\"rowNum\":15,"
                + "\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"98.00\","
                + "\"instructorContactId\":null}]}")
            .status("PENDING")
            .build();
    }

    private LabResult storedResult(UUID id, String score) {
        return LabResult.builder().id(id).score(new BigDecimal(score)).submittedOn(LocalDate.of(2026, 1, 1)).build();
    }

    @Test
    void resolveConflict_keepExisting_leavesTheScoreAloneButRecordsTheDiscardedMarks() {
        UUID existingResultId = UUID.randomUUID();
        IngestionConflict conflict = pendingDuplicate(existingResultId);
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(labResultRepository.findById(existingResultId)).thenReturn(Optional.of(storedResult(existingResultId, "88.00")));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestionConflictResponse response = cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_EXISTING).build());

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolvedBy()).isEqualTo(actor.getId());
        verify(labResultRepository, never()).save(any());

        // The score legitimately doesn't change, but a decision between 88 and 98 was taken. Writing
        // nothing at all is what left a dropped 98 with no trace in the grade's own history.
        ArgumentCaptor<LabReferenceAuditLog> auditCaptor = ArgumentCaptor.forClass(LabReferenceAuditLog.class);
        verify(labReferenceAuditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getRecordId()).isEqualTo(existingResultId);
        assertThat(auditCaptor.getValue().getOldValue()).isEqualTo("88.00");
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("88.00");
        assertThat(auditCaptor.getValue().getReason())
            .contains("kept the stored score 88.00")
            .contains("discarded sheet Module-1 row 5 (88.00), sheet Module-1 row 15 (98.00)");

        verify(auditEventService).record(eq("CONFLICT_RESOLVED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void resolveConflict_keepIncomingOnADuplicatePair_commitsTheChosenRowAndNamesTheDiscardedMark() {
        // QA's exact case: rows 5 and 15 of one sheet at 88 and 98, stored grade currently 88.
        UUID existingResultId = UUID.randomUUID();
        IngestionConflict conflict = pendingDuplicate(existingResultId);
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(labResultRepository.findById(existingResultId)).thenReturn(Optional.of(storedResult(existingResultId, "88.00")));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        cohortSyncService.resolveConflict(cohortId, conflictId, ResolveConflictRequest.builder()
            .action(ConflictResolutionAction.KEEP_INCOMING).chosenRowIndex(1).build());

        // The admin picked row 15, so 98 is stored — not whichever copy happened to be clicked first.
        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getScore()).isEqualByComparingTo("98.00");

        ArgumentCaptor<LabReferenceAuditLog> auditCaptor = ArgumentCaptor.forClass(LabReferenceAuditLog.class);
        verify(labReferenceAuditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldValue()).isEqualTo("88.00");
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("98.00");
        assertThat(auditCaptor.getValue().getReason())
            .contains("kept sheet Module-1 row 15 (98.00")
            .contains("discarded sheet Module-1 row 5 (88.00)");
    }

    @Test
    void resolveConflict_keepIncomingOnADuplicatePairWithNoChosenRow_isRejectedRatherThanGuessed() {
        IngestionConflict conflict = pendingDuplicate(UUID.randomUUID());
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));

        // "Keep the incoming row" cannot name a winner when there are two of them at different marks.
        assertThatThrownBy(() -> cohortSyncService.resolveConflict(cohortId, conflictId,
            ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build()))
            .isInstanceOf(UnprocessableEntityException.class)
            .hasMessageContaining("chosenRowIndex is required")
            .hasMessageContaining("Valid values are 0 to 1");

        verify(labResultRepository, never()).save(any());
        verify(ingestionConflictRepository, never()).save(any());
        verify(auditEventService, never()).record(any(), any(), any(), any());
    }

    @Test
    void resolveConflict_chosenRowIndexOutOfRange_isRejected() {
        IngestionConflict conflict = pendingDuplicate(UUID.randomUUID());
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(cohortId, conflictId,
            ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).chosenRowIndex(7).build()))
            .isInstanceOf(UnprocessableEntityException.class)
            .hasMessageContaining("out of range");

        verify(labResultRepository, never()).save(any());
    }

    @Test
    void resolveConflict_recordsEveryCandidateOnTheAuditEventNotJustTheWinner() {
        UUID existingResultId = UUID.randomUUID();
        IngestionConflict conflict = pendingDuplicate(existingResultId);
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(labResultRepository.findById(existingResultId)).thenReturn(Optional.of(storedResult(existingResultId, "88.00")));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        cohortSyncService.resolveConflict(cohortId, conflictId, ResolveConflictRequest.builder()
            .action(ConflictResolutionAction.KEEP_INCOMING).chosenRowIndex(0).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).record(eq("CONFLICT_RESOLVED"), eq(cohortId), eq(actor.getId()),
            payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("chosenRowIndex", 0).containsEntry("priorScore", "88.00");
        assertThat(payload.get("keptRow")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("score", "88.00").containsEntry("rowNum", 5);
        assertThat((List<?>) payload.get("discardedRows")).hasSize(1);
    }

    @Test
    void resolveConflict_singleCandidate_stillResolvesWithoutAChosenRowIndex() {
        // A legacy conflict stored before duplicates were grouped holds one bare row, not an envelope.
        IngestionConflict conflict = pendingConflict(null);
        assertThat(ConflictPayloadCodec.read(conflict.getIncomingPayloadJson())).hasSize(1);
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));
        when(ingestionConflictRepository.save(any(IngestionConflict.class))).thenAnswer(inv -> inv.getArgument(0));

        cohortSyncService.resolveConflict(cohortId, conflictId,
            ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build());

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getScore()).isEqualByComparingTo("95.00");
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
        // Mirrors the database assigning the id on insert: the returned row carries one, the argument
        // handed to save does not.
        UUID createdResultId = UUID.randomUUID();
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> {
            LabResult toSave = inv.getArgument(0);
            LabResult persisted = LabResult.builder()
                .id(createdResultId)
                .learnerId(toSave.getLearnerId())
                .labId(toSave.getLabId())
                .ingestionRunId(toSave.getIngestionRunId())
                .nspName(toSave.getNspName())
                .score(toSave.getScore())
                .submittedOn(toSave.getSubmittedOn())
                .rowValueHash(toSave.getRowValueHash())
                .build();
            return persisted;
        });

        cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_INCOMING).build());

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getId()).isNull();
        assertThat(resultCaptor.getValue().getLearnerId()).isEqualTo(conflict.getLearnerId());
        assertThat(resultCaptor.getValue().getLabId()).isEqualTo(conflict.getLabId());
        assertThat(resultCaptor.getValue().getScore()).isEqualByComparingTo("95.00");

        // A grade created by a conflict decision also gets a history record — there was no prior
        // value, but "this row exists because a duplicate was resolved this way" is the whole point.
        ArgumentCaptor<LabReferenceAuditLog> auditCaptor = ArgumentCaptor.forClass(LabReferenceAuditLog.class);
        verify(labReferenceAuditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldValue()).isNull();
        assertThat(auditCaptor.getValue().getNewValue()).isEqualTo("95.00");

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
    void resolveConflict_alreadyDecided_throwsConflictStateSoASecondDecisionCannotOverwriteTheFirst() {
        IngestionConflict conflict = pendingConflict(UUID.randomUUID());
        conflict.setStatus("RESOLVED");
        when(ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)).thenReturn(Optional.of(conflict));

        assertThatThrownBy(() -> cohortSyncService.resolveConflict(
            cohortId, conflictId, ResolveConflictRequest.builder().action(ConflictResolutionAction.KEEP_EXISTING).build()))
            .isInstanceOf(ConflictStateException.class);

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
