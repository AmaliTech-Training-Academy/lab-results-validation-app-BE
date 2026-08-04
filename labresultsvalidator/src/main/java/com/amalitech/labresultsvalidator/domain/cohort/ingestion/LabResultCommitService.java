package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B9 (commit/upsert, partial success) + B10 (conflict-queue write path). No enclosing
 * {@code @Transactional} — matches {@code CohortSyncJobRunner}'s existing no-ambient-transaction
 * convention, so each row's write commits independently and a later row's failure can never roll
 * back an earlier row's commit (B9 AC2). Each row is individually try/caught; a failure is folded
 * into the outcome's row errors rather than propagated.
 */
@Component
public class LabResultCommitService {

    private static final Logger LOG = LoggerFactory.getLogger(LabResultCommitService.class);

    private final LabResultRepository labResultRepository;
    private final IngestionConflictRepository ingestionConflictRepository;
    private final LabReferenceAuditLogRepository labReferenceAuditLogRepository;
    private final ObjectMapper objectMapper;

    public LabResultCommitService(
        LabResultRepository labResultRepository,
        IngestionConflictRepository ingestionConflictRepository,
        LabReferenceAuditLogRepository labReferenceAuditLogRepository,
        ObjectMapper objectMapper
    ) {
        this.labResultRepository = labResultRepository;
        this.ingestionConflictRepository = ingestionConflictRepository;
        this.labReferenceAuditLogRepository = labReferenceAuditLogRepository;
        this.objectMapper = objectMapper;
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
                        commitChanged(classification, ingestionRunId, triggeredBy);
                        updatedCount++;
                    }
                    case DUPLICATE -> {
                        commitDuplicate(classification, cohortId, ingestionRunId);
                        conflictsCount++;
                    }
                    default -> throw new IllegalStateException(
                        "Unexpected classification kind: " + classification.kind());
                }
            } catch (RuntimeException ex) {
                LOG.warn("[ingestion] could not commit row {}: {}", classification.row().location(),
                    ex.getMessage());
                rowErrors.add(new RowError(classification.row().fileName(), classification.row().location(),
                    "COMMIT-FAILED", ex.getMessage(), classification.row().instructorContactId()));
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

    private void commitChanged(RowClassification classification, UUID ingestionRunId, UUID triggeredBy) {
        LabResult existing = classification.existing();
        ValidatedScoreRow row = classification.row();
        BigDecimal oldScore = existing.getScore();

        existing.setScore(row.score());
        existing.setSubmittedOn(row.submittedOn());
        existing.setInstructorContactId(row.instructorContactId());
        existing.setIngestionRunId(ingestionRunId);
        existing.setRowValueHash(RowFingerprint.compute(row.submittedOn(), row.score()));
        existing.setUpdatedBy(triggeredBy);
        labResultRepository.save(existing);

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

    private void commitDuplicate(RowClassification classification, UUID cohortId, UUID ingestionRunId) {
        ValidatedScoreRow row = classification.row();
        LabResult existing = classification.existing();

        ingestionConflictRepository.save(IngestionConflict.builder()
            .ingestionRunId(ingestionRunId)
            .cohortId(cohortId)
            .learnerId(row.learnerId())
            .labId(row.labId())
            .existingResultId(existing != null ? existing.getId() : null)
            .incomingPayloadJson(buildPayloadJson(row))
            .build());
    }

    private String buildPayloadJson(ValidatedScoreRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", row.fileName());
        payload.put("sheetName", row.sheetName());
        payload.put("rowNum", row.rowNum());
        payload.put("nspName", row.nspName());
        payload.put("submittedOn", row.submittedOn().toString());
        payload.put("score", row.score().toPlainString());
        UUID instructorContactId = row.instructorContactId();
        payload.put("instructorContactId", instructorContactId != null ? instructorContactId.toString() : null);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
