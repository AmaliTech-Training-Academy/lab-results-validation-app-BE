package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabResultCommitServiceTest {

    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 15);
    private static final String NSP_NAME = "ama owusu";

    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private IngestionConflictRepository ingestionConflictRepository;
    @Mock
    private LabReferenceAuditLogRepository labReferenceAuditLogRepository;

    private LabResultCommitService service;

    private UUID cohortId;
    private UUID ingestionRunId;
    private UUID triggeredBy;

    @BeforeEach
    void setUp() {
        service = new LabResultCommitService(
            labResultRepository, ingestionConflictRepository, labReferenceAuditLogRepository);
        cohortId = UUID.randomUUID();
        ingestionRunId = UUID.randomUUID();
        triggeredBy = UUID.randomUUID();
    }

    private ValidatedScoreRow row(BigDecimal score) {
        return new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, UUID.randomUUID(), UUID.randomUUID(),
            null, NSP_NAME, SUBMITTED_ON, score);
    }

    @Test
    void commit_newRow_insertsLabResultWithFingerprintAndActor() {
        ValidatedScoreRow row = row(new BigDecimal("90.00"));
        RowClassification classification = new RowClassification(ClassificationKind.NEW, row, null);

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        assertThat(outcome.committedNew()).isEqualTo(1);
        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(captor.capture());
        LabResult saved = captor.getValue();
        assertThat(saved.getLearnerId()).isEqualTo(row.learnerId());
        assertThat(saved.getLabId()).isEqualTo(row.labId());
        assertThat(saved.getIngestionRunId()).isEqualTo(ingestionRunId);
        assertThat(saved.getNspName()).isEqualTo(NSP_NAME);
        assertThat(saved.getScore()).isEqualTo(row.score());
        assertThat(saved.getRowValueHash()).isEqualTo(RowFingerprint.compute(SUBMITTED_ON, row.score()));
        assertThat(saved.getCreatedBy()).isEqualTo(triggeredBy);
        assertThat(saved.getUpdatedBy()).isEqualTo(triggeredBy);
    }

    @Test
    void commit_unchangedRow_writesNothing() {
        ValidatedScoreRow row = row(new BigDecimal("90.00"));
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).score(row.score()).build();
        RowClassification classification = new RowClassification(ClassificationKind.UNCHANGED, row, existing);

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        assertThat(outcome.skippedUnchanged()).isEqualTo(1);
        verify(labResultRepository, never()).save(any());
        verify(labReferenceAuditLogRepository, never()).save(any());
    }

    @Test
    void commit_changedRow_updatesInPlaceAndLogsPriorScore() {
        BigDecimal oldScore = new BigDecimal("85.00");
        BigDecimal newScore = new BigDecimal("90.00");
        UUID existingId = UUID.randomUUID();
        LabResult existing = LabResult.builder().id(existingId).score(oldScore).submittedOn(SUBMITTED_ON).build();
        ValidatedScoreRow row = row(newScore);
        RowClassification classification = new RowClassification(ClassificationKind.CHANGED, row, existing);

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        assertThat(outcome.updatedCount()).isEqualTo(1);

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getId()).isEqualTo(existingId);
        assertThat(resultCaptor.getValue().getScore()).isEqualTo(newScore);
        assertThat(resultCaptor.getValue().getUpdatedBy()).isEqualTo(triggeredBy);

        ArgumentCaptor<LabReferenceAuditLog> auditCaptor = ArgumentCaptor.forClass(LabReferenceAuditLog.class);
        verify(labReferenceAuditLogRepository).save(auditCaptor.capture());
        LabReferenceAuditLog audit = auditCaptor.getValue();
        assertThat(audit.getTableName()).isEqualTo("lab_results");
        assertThat(audit.getRecordId()).isEqualTo(existingId);
        assertThat(audit.getFieldName()).isEqualTo("score");
        assertThat(audit.getOldValue()).isEqualTo("85.00");
        assertThat(audit.getNewValue()).isEqualTo("90.00");
        assertThat(audit.getChangedBy()).isEqualTo(triggeredBy);
    }

    @Test
    void commit_changedRow_writesPriorValueBeforeApplyingUpdate() {
        // D3 AC1 — with no enclosing transaction, the prior-value record must be durable before
        // the update is applied, so a crash between the two writes can never lose it.
        BigDecimal oldScore = new BigDecimal("85.00");
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).score(oldScore).submittedOn(SUBMITTED_ON)
            .build();
        ValidatedScoreRow row = row(new BigDecimal("90.00"));
        RowClassification classification = new RowClassification(ClassificationKind.CHANGED, row, existing);

        service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        InOrder inOrder = org.mockito.Mockito.inOrder(labReferenceAuditLogRepository, labResultRepository);
        inOrder.verify(labReferenceAuditLogRepository).save(any());
        inOrder.verify(labResultRepository).save(any());
    }

    @Test
    void commit_changedRowOnANewDate_updatesSubmittedOnInPlace() {
        LocalDate regradeDate = SUBMITTED_ON.plusDays(7);
        BigDecimal score = new BigDecimal("90.00");
        UUID existingId = UUID.randomUUID();
        LabResult existing = LabResult.builder().id(existingId).score(score).submittedOn(SUBMITTED_ON).build();
        ValidatedScoreRow row = new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, UUID.randomUUID(),
            UUID.randomUUID(), null, NSP_NAME, regradeDate, score);
        RowClassification classification = new RowClassification(ClassificationKind.CHANGED, row, existing);

        service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getSubmittedOn()).isEqualTo(regradeDate);
    }

    @Test
    void commit_reviewDateCorrectedButMarkUntouched_isNotReportedAsAnUpdateAndLogsNoScoreChange() {
        // A review-date typo fix trips the fingerprint (it hashes submittedOn + score together)
        // exactly like a real re-grade would, but nobody's mark moved — this must not be announced
        // to instructors/admins as a grade change, nor fabricate a "score changed X -> X" history
        // entry.
        LocalDate correctedDate = SUBMITTED_ON.plusDays(1);
        BigDecimal score = new BigDecimal("90.00");
        UUID existingId = UUID.randomUUID();
        LabResult existing = LabResult.builder().id(existingId).score(score).submittedOn(SUBMITTED_ON).build();
        ValidatedScoreRow row = new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, UUID.randomUUID(),
            UUID.randomUUID(), null, NSP_NAME, correctedDate, score);
        RowClassification classification = new RowClassification(ClassificationKind.CHANGED, row, existing);

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(classification), cohortId, ingestionRunId, triggeredBy);

        // Not counted as a re-grade...
        assertThat(outcome.updatedCount()).isZero();
        assertThat(outcome.skippedUnchanged()).isEqualTo(1);
        // ...but the correction is still persisted, including a fresh fingerprint so it isn't
        // re-flagged as CHANGED on the next run.
        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getSubmittedOn()).isEqualTo(correctedDate);
        assertThat(resultCaptor.getValue().getScore()).isEqualTo(score);
        assertThat(resultCaptor.getValue().getRowValueHash())
            .isEqualTo(RowFingerprint.compute(correctedDate, score));
        // ...and no audit-log entry claims the score changed from a value to the same value.
        verify(labReferenceAuditLogRepository, never()).save(any());
    }

    /** The group the classifier now hands over: one DUPLICATE carrying every conflicting copy. */
    private RowClassification duplicateGroup(LabResult existing, ValidatedScoreRow... copies) {
        ValidatedScoreRow[] rows = copies;
        return new RowClassification(ClassificationKind.DUPLICATE, rows[0], existing, List.of(rows));
    }

    private ValidatedScoreRow rowAt(int rowNum, UUID learnerId, UUID labId, BigDecimal score) {
        return new ValidatedScoreRow("Instructor1.xlsx", "BEM01", rowNum, learnerId, labId,
            null, NSP_NAME, SUBMITTED_ON, score);
    }

    @Test
    void commit_duplicatedRow_writesOneConflictHoldingEveryConflictingCopy() {
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).score(new BigDecimal("80.00")).build();
        UUID learnerId = UUID.randomUUID();
        UUID labId = UUID.randomUUID();
        RowClassification group = duplicateGroup(existing,
            rowAt(5, learnerId, labId, new BigDecimal("88.00")),
            rowAt(15, learnerId, labId, new BigDecimal("98.00")));

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(group), cohortId, ingestionRunId, triggeredBy);

        // One duplicate is one conflict needing one decision — not one conflict per copy, which is
        // what let two contradictory resolutions through and made the outcome follow click order.
        assertThat(outcome.conflictsCount()).isEqualTo(1);
        assertThat(outcome.committedNew()).isZero();
        assertThat(outcome.updatedCount()).isZero();

        ArgumentCaptor<IngestionConflict> captor = ArgumentCaptor.forClass(IngestionConflict.class);
        verify(ingestionConflictRepository).save(captor.capture());
        IngestionConflict conflict = captor.getValue();
        assertThat(conflict.getIngestionRunId()).isEqualTo(ingestionRunId);
        assertThat(conflict.getExistingResultId()).isEqualTo(existing.getId());
        assertThat(conflict.getLearnerId()).isEqualTo(learnerId);
        assertThat(conflict.getLabId()).isEqualTo(labId);

        // Both marks are held, so the admin can be shown what they are choosing between.
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(conflict.getIncomingPayloadJson());
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(ConflictCandidate::index).containsExactly(0, 1);
        assertThat(candidates).extracting(c -> c.score().toPlainString())
            .containsExactly("88.00", "98.00");
        assertThat(candidates).extracting(ConflictCandidate::rowNum).containsExactly(5, 15);
        assertThat(conflict.getIncomingPayloadJson()).contains(NSP_NAME);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void commit_duplicateAlreadyPendingFromAnEarlierRun_refreshesItInPlaceRatherThanAddingASecond() {
        UUID learnerId = UUID.randomUUID();
        UUID labId = UUID.randomUUID();
        IngestionConflict pending = IngestionConflict.builder()
            .id(UUID.randomUUID())
            .ingestionRunId(UUID.randomUUID())
            .learnerId(learnerId)
            .labId(labId)
            .status("PENDING")
            .incomingPayloadJson(payloadFor(rowAt(5, learnerId, labId, new BigDecimal("88.00"))))
            .build();
        when(ingestionConflictRepository.findLatestForLearnerAndLab(eq(cohortId), eq(learnerId), eq(labId), any()))
            .thenReturn(List.of(pending));

        // The sheet changed since that run: the second copy now reads 99 rather than 98.
        RowClassification group = duplicateGroup(null,
            rowAt(5, learnerId, labId, new BigDecimal("88.00")),
            rowAt(15, learnerId, labId, new BigDecimal("99.00")));

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(group), cohortId, ingestionRunId, triggeredBy);

        assertThat(outcome.conflictsCount()).isEqualTo(1);
        ArgumentCaptor<IngestionConflict> captor = ArgumentCaptor.forClass(IngestionConflict.class);
        verify(ingestionConflictRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(pending.getId());
        assertThat(captor.getValue().getIngestionRunId()).isEqualTo(ingestionRunId);
        assertThat(ConflictPayloadCodec.read(captor.getValue().getIncomingPayloadJson())).hasSize(2);
    }

    @Test
    void commit_duplicateAlreadyResolvedAndUnchangedInTheSheet_isNotReopened() {
        UUID learnerId = UUID.randomUUID();
        UUID labId = UUID.randomUUID();
        ValidatedScoreRow copyA = rowAt(5, learnerId, labId, new BigDecimal("88.00"));
        ValidatedScoreRow copyB = rowAt(15, learnerId, labId, new BigDecimal("98.00"));
        IngestionConflict resolved = IngestionConflict.builder()
            .id(UUID.randomUUID())
            .ingestionRunId(UUID.randomUUID())
            .learnerId(learnerId)
            .labId(labId)
            .status("RESOLVED")
            .incomingPayloadJson(ConflictPayloadCodec.write(List.of(copyA, copyB)))
            .build();
        when(ingestionConflictRepository.findLatestForLearnerAndLab(eq(cohortId), eq(learnerId), eq(labId), any()))
            .thenReturn(List.of(resolved));

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(duplicateGroup(null, copyA, copyB)), cohortId, ingestionRunId, triggeredBy);

        // The duplicate is still in the workbook — nothing is written back to SharePoint — but the
        // admin already decided it on exactly these rows and marks. Re-raising it would reopen a
        // settled decision and re-send the conflict alert on every run, forever.
        assertThat(outcome.conflictsCount()).isZero();
        verify(ingestionConflictRepository, never()).save(any());
    }

    @Test
    void commit_duplicateResolvedButMarksChangedSince_opensAFreshConflict() {
        UUID learnerId = UUID.randomUUID();
        UUID labId = UUID.randomUUID();
        IngestionConflict resolved = IngestionConflict.builder()
            .id(UUID.randomUUID())
            .ingestionRunId(UUID.randomUUID())
            .learnerId(learnerId)
            .labId(labId)
            .status("RESOLVED")
            .incomingPayloadJson(ConflictPayloadCodec.write(List.of(
                rowAt(5, learnerId, labId, new BigDecimal("88.00")),
                rowAt(15, learnerId, labId, new BigDecimal("98.00")))))
            .build();
        when(ingestionConflictRepository.findLatestForLearnerAndLab(eq(cohortId), eq(learnerId), eq(labId), any()))
            .thenReturn(List.of(resolved));

        RowClassification group = duplicateGroup(null,
            rowAt(5, learnerId, labId, new BigDecimal("88.00")),
            rowAt(15, learnerId, labId, new BigDecimal("70.00")));

        LabResultCommitService.CommitOutcome outcome =
            service.commit(List.of(group), cohortId, ingestionRunId, triggeredBy);

        // A different mark is a different decision, so it goes back to the admin.
        assertThat(outcome.conflictsCount()).isEqualTo(1);
        ArgumentCaptor<IngestionConflict> captor = ArgumentCaptor.forClass(IngestionConflict.class);
        verify(ingestionConflictRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
    }

    /** A stored payload in the legacy pre-fix shape: the bare single row, no candidates envelope. */
    private String payloadFor(ValidatedScoreRow row) {
        return "{\"fileName\":\"" + row.fileName() + "\",\"sheetName\":\"" + row.sheetName()
            + "\",\"rowNum\":" + row.rowNum() + ",\"nspName\":\"" + row.nspName()
            + "\",\"submittedOn\":\"" + row.submittedOn() + "\",\"score\":\""
            + row.score().toPlainString() + "\",\"instructorContactId\":null}";
    }

    @Test
    void commit_oneRowFailsToPersist_doesNotBlockSiblingRows() {
        ValidatedScoreRow goodRow = row(new BigDecimal("90.00"));
        ValidatedScoreRow badRow = row(new BigDecimal("95.00"));
        List<RowClassification> classifications = List.of(
            new RowClassification(ClassificationKind.NEW, badRow, null),
            new RowClassification(ClassificationKind.NEW, goodRow, null));

        when(labResultRepository.save(any())).thenAnswer(invocation -> {
            LabResult arg = invocation.getArgument(0);
            if (arg.getScore().equals(new BigDecimal("95.00"))) {
                throw new RuntimeException("constraint violation");
            }
            return arg;
        });

        LabResultCommitService.CommitOutcome outcome =
            service.commit(classifications, cohortId, ingestionRunId, triggeredBy);

        assertThat(outcome.committedNew()).isEqualTo(1);
        assertThat(outcome.skippedInvalid()).isEqualTo(1);
        assertThat(outcome.rowErrors()).hasSize(1);
        assertThat(outcome.rowErrors().get(0).rule()).isEqualTo("COMMIT-FAILED");
    }
}
