package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;

import java.time.OffsetDateTime;
import java.util.UUID;

/** D5 list-view row — summary only, no {@code errorReportJson} (see {@link IngestionRunDetailResponse}). */
public record IngestionRunAuditResponse(
    UUID id,
    UUID cohortId,
    UUID syncJobId,
    String workbookFilename,
    String status,
    String triggerType,
    UUID triggeredBy,
    int rowsRead,
    int committedNew,
    int updatedCount,
    int skippedInvalid,
    int skippedUnchanged,
    int conflictsCount,
    boolean highFailureRate,
    double failureRatePercent,
    OffsetDateTime runAt
) {
    public static IngestionRunAuditResponse from(IngestionRun run) {
        return new IngestionRunAuditResponse(
            run.getId(),
            run.getCohortId(),
            run.getSyncJobId(),
            run.getWorkbookFilename(),
            run.getStatus(),
            run.getTriggerType(),
            run.getTriggeredBy(),
            run.getRowsRead(),
            run.getCommittedNew(),
            run.getUpdatedCount(),
            run.getSkippedInvalid(),
            run.getSkippedUnchanged(),
            run.getConflictsCount(),
            run.isHighFailureRate(),
            run.getFailureRatePercent(),
            run.getRunAt());
    }
}
