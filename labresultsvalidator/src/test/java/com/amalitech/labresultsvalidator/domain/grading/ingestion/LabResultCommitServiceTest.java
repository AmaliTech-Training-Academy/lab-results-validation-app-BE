package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private UUID ingestionRunId;
    private UUID triggeredBy;

    @BeforeEach
    void setUp() {
        service = new LabResultCommitService(
            labResultRepository, ingestionConflictRepository, labReferenceAuditLogRepository, new ObjectMapper());
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
            service.commit(List.of(classification), ingestionRunId, triggeredBy);

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
            service.commit(List.of(classification), ingestionRunId, triggeredBy);

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
            service.commit(List.of(classification), ingestionRunId, triggeredBy);

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

        service.commit(List.of(classification), ingestionRunId, triggeredBy);

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

        service.commit(List.of(classification), ingestionRunId, triggeredBy);

        ArgumentCaptor<LabResult> resultCaptor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getSubmittedOn()).isEqualTo(regradeDate);
    }

    @Test
    void commit_duplicateRows_writeOneConflictEachReferencingTheExistingCommittedRow() {
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).score(new BigDecimal("80.00")).build();
        ValidatedScoreRow first = row(new BigDecimal("90.00"));
        ValidatedScoreRow second = row(new BigDecimal("95.00"));
        List<RowClassification> classifications = List.of(
            new RowClassification(ClassificationKind.DUPLICATE, first, existing),
            new RowClassification(ClassificationKind.DUPLICATE, second, existing));

        LabResultCommitService.CommitOutcome outcome =
            service.commit(classifications, ingestionRunId, triggeredBy);

        assertThat(outcome.conflictsCount()).isEqualTo(2);
        assertThat(outcome.committedNew()).isZero();
        assertThat(outcome.updatedCount()).isZero();

        ArgumentCaptor<IngestionConflict> captor = ArgumentCaptor.forClass(IngestionConflict.class);
        verify(ingestionConflictRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(c -> {
            assertThat(c.getIngestionRunId()).isEqualTo(ingestionRunId);
            assertThat(c.getExistingResultId()).isEqualTo(existing.getId());
            assertThat(c.getIncomingPayloadJson()).contains(NSP_NAME);
        });
        verify(labResultRepository, never()).save(any());
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
            service.commit(classifications, ingestionRunId, triggeredBy);

        assertThat(outcome.committedNew()).isEqualTo(1);
        assertThat(outcome.skippedInvalid()).isEqualTo(1);
        assertThat(outcome.rowErrors()).hasSize(1);
        assertThat(outcome.rowErrors().get(0).rule()).isEqualTo("COMMIT-FAILED");
    }
}
