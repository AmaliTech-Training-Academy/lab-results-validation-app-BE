package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * B9 (commit/upsert, partial success) + B10 (conflict-queue write path). No enclosing
 * {@code @Transactional} — matches {@code CohortSyncJobRunner}'s existing no-ambient-transaction
 * convention, so each row's write commits independently and a later row's failure can never roll
 * back an earlier row's commit (B9 AC2). Each row is individually try/caught; a failure is folded
 * into the outcome's row errors rather than propagated.
 *
 * <p>{@code conflictsCount} counts <strong>duplicates</strong>, not duplicated rows — a row appearing
 * twice is one conflict needing one decision (see {@link #holdDuplicate}), so it counts 1.
 */
@Component
public class LabResultCommitService {

    private static final Logger LOG = LoggerFactory.getLogger(LabResultCommitService.class);

    private static final String PENDING = "PENDING";

    private final LabResultRepository labResultRepository;
    private final IngestionConflictRepository ingestionConflictRepository;
    private final LabReferenceAuditLogRepository labReferenceAuditLogRepository;

    public LabResultCommitService(
        LabResultRepository labResultRepository,
        IngestionConflictRepository ingestionConflictRepository,
        LabReferenceAuditLogRepository labReferenceAuditLogRepository
    ) {
        this.labResultRepository = labResultRepository;
        this.ingestionConflictRepository = ingestionConflictRepository;
        this.labReferenceAuditLogRepository = labReferenceAuditLogRepository;
    }

    public record CommitOutcome(
        int committedNew,
        int updatedCount,
        int skippedUnchanged,
        int skippedInvalid,
        int conflictsCount,
        List<RowError> rowErrors
    ) {
    }

    public CommitOutcome commit(List<RowClassification> classifications, UUID cohortId, UUID ingestionRunId,
                                UUID triggeredBy) {
        int committedNew = 0;
        int updatedCount = 0;
        int skippedUnchanged = 0;
        int skippedInvalid = 0;
        int conflictsCount = 0;
        List<RowError> rowErrors = new ArrayList<>();

        for (RowClassification classification : classifications) {
            try {
                switch (classification.kind()) {
                    case NEW -> {
                        commitNew(classification.row(), ingestionRunId, triggeredBy);
                        committedNew++;
                    }
                    case UNCHANGED -> skippedUnchanged++;
                    case CHANGED -> {
                        if (commitChanged(classification, ingestionRunId, triggeredBy)) {
                            updatedCount++;
                        } else {
                            skippedUnchanged++;
                        }
                    }
                    case DUPLICATE -> {
                        if (holdDuplicate(classification, cohortId, ingestionRunId)) {
                            conflictsCount++;
                        }
                    }
                    default -> throw new IllegalStateException(
                        "Unexpected classification kind: " + classification.kind());
                }
            } catch (RuntimeException ex) {

                LOG.error("[ingestion] could not commit row {}: {}", classification.row().location(),
                    ex.getMessage(), ex);
                String errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                rowErrors.add(new RowError(classification.row().fileName(), classification.row().location(),
                    "COMMIT-FAILED", errorMessage, classification.row().instructorContactId(), null));
                skippedInvalid++;
            }
        }

        return new CommitOutcome(committedNew, updatedCount, skippedUnchanged, skippedInvalid,
            conflictsCount, rowErrors);
    }

    private void commitNew(ValidatedScoreRow row, UUID ingestionRunId, UUID triggeredBy) {
        String fingerprint = RowFingerprint.compute(row.submittedOn(), row.score());
        LabResult result = LabResult.builder()
            .learnerId(row.learnerId())
            .labId(row.labId())
            .ingestionRunId(ingestionRunId)
            .instructorContactId(row.instructorContactId())
            .nspName(row.nspName())
            .score(row.score())
            .submittedOn(row.submittedOn())
            .rowValueHash(fingerprint)
            .build();
        result.setCreatedBy(triggeredBy);
        result.setUpdatedBy(triggeredBy);
        labResultRepository.save(result);
    }

    /**
     * @return true if the mark itself changed (a genuine re-grade), false if the fingerprint moved
     *     for some other reason (in practice, {@code submittedOn} alone — a review-date correction).
     *     Either way the row is persisted with the incoming values and a fresh fingerprint, so the
     *     correction sticks and this row is not re-flagged as CHANGED on the next run; only the
     *     {@code true} case is a re-grade worth an audit-log entry and an "updated" count.
     */
    private boolean commitChanged(RowClassification classification, UUID ingestionRunId, UUID triggeredBy) {
        LabResult existing = classification.existing();
        ValidatedScoreRow row = classification.row();
        BigDecimal oldScore = existing.getScore();
        // compareTo, not equals: a rescaled-but-equal score (85 vs 85.00) is not a mark change, and
        // RowFingerprint normalizes to scale 2 for the same reason.
        boolean scoreChanged = oldScore.compareTo(row.score()) != 0;

        if (scoreChanged) {
            labReferenceAuditLogRepository.save(LabReferenceAuditLog.builder()
                .tableName("lab_results")
                .recordId(existing.getId())
                .fieldName("score")
                .oldValue(oldScore.toPlainString())
                .newValue(row.score().toPlainString())
                .changedBy(triggeredBy)
                .reason("Re-grade detected during ingestion run " + ingestionRunId)
                .build());
        }

        existing.setScore(row.score());
        existing.setSubmittedOn(row.submittedOn());
        existing.setInstructorContactId(row.instructorContactId());
        existing.setIngestionRunId(ingestionRunId);
        existing.setRowValueHash(RowFingerprint.compute(row.submittedOn(), row.score()));
        existing.setUpdatedBy(triggeredBy);
        labResultRepository.save(existing);
        return scoreChanged;
    }

    /**
     * Holds one duplicated row as a single conflict carrying every conflicting copy (B10 AC1), and
     * keeps it from multiplying across runs (B10 AC3).
     *
     * <p>Resolving a duplicate cannot remove it from the workbook — there is no write-back to
     * SharePoint — so the same duplicate is read again on every run. The invariant enforced here is
     * <strong>at most one PENDING conflict per (cohort, learner, lab)</strong>:
     *
     * <ul>
     *   <li>a still-{@code PENDING} conflict is refreshed in place, never duplicated, so a second
     *       pending conflict for one duplicate cannot exist and there is only ever one decision to
     *       take;</li>
     *   <li>an already-decided conflict whose candidate set is byte-for-byte the same decision the
     *       admin already took is left alone — it does not reopen and raises no new alert;</li>
     *   <li>an already-decided conflict whose marks or rows have since changed is a genuinely new
     *       decision, so a fresh {@code PENDING} conflict opens.</li>
     * </ul>
     *
     * <p>{@code ingestion_run_id} tracks the run whose data produced the stored candidates, so it is
     * repointed whenever the payload is rewritten. The earlier run's own {@code conflicts_count}
     * stays as the audit of what that run raised (B11 AC1).
     *
     * @return true if this duplicate needs an admin decision (opened or carried over), false if it
     *     was already decided and nothing has changed
     */
    private boolean holdDuplicate(RowClassification classification, UUID cohortId, UUID ingestionRunId) {
        List<ValidatedScoreRow> copies = classification.allRows();
        ValidatedScoreRow row = classification.row();
        LabResult existing = classification.existing();
        UUID existingResultId = existing != null ? existing.getId() : null;
        String fingerprint = DuplicateCandidateFingerprint.ofRows(copies);

        IngestionConflict prior = findLatestConflict(cohortId, row.learnerId(), row.labId());
        if (prior != null) {
            boolean unchanged = fingerprint.equals(
                DuplicateCandidateFingerprint.ofCandidates(ConflictPayloadCodec.read(prior.getIncomingPayloadJson())));

            if (!PENDING.equals(prior.getStatus())) {
                if (unchanged) {
                    LOG.info("[ingestion] duplicate for learner={} lab={} was already {} with the same rows — "
                            + "not reopening; the durable fix is to remove the duplicate row in '{}'",
                        row.learnerId(), row.labId(), prior.getStatus().toLowerCase(), row.fileName());
                    return false;
                }
            } else {
                if (!unchanged) {
                    prior.setIngestionRunId(ingestionRunId);
                    prior.setIncomingPayloadJson(ConflictPayloadCodec.write(copies));
                    prior.setExistingResultId(existingResultId);
                    ingestionConflictRepository.save(prior);
                }
                LOG.info("[ingestion] duplicate for learner={} lab={} still awaiting a decision (conflict {})",
                    row.learnerId(), row.labId(), prior.getId());
                return true;
            }
        }

        ingestionConflictRepository.save(IngestionConflict.builder()
            .ingestionRunId(ingestionRunId)
            .learnerId(row.learnerId())
            .labId(row.labId())
            .existingResultId(existingResultId)
            .incomingPayloadJson(ConflictPayloadCodec.write(copies))
            .build());
        return true;
    }

    private IngestionConflict findLatestConflict(UUID cohortId, UUID learnerId, UUID labId) {
        if (cohortId == null || learnerId == null || labId == null) {
            return null;
        }
        return ingestionConflictRepository
            .findLatestForLearnerAndLab(cohortId, learnerId, labId, PageRequest.of(0, 1))
            .stream().findFirst().orElse(null);
    }
}
